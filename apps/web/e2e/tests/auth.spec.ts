import { expect, test, type Page } from "@playwright/test";

import { URLS } from "../stack.ts";
import { newAccount, watchForErrors } from "./support.ts";

const header = (page: Page) => page.getByRole("banner");
/** The form's own message. Next.js keeps another element with the alert role for announcing route changes. */
const formAlert = (page: Page) => page.locator("form").getByRole("alert");

async function register(page: Page, email: string, password: string) {
  await page.goto("/register");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(header(page).getByText(email)).toBeVisible();
}

async function signOut(page: Page) {
  await header(page).getByRole("button", { name: "Sign out" }).click();
  await expect(header(page).getByRole("link", { name: "Sign in" })).toBeVisible();
}

test.describe("accounts and saved routes", () => {
  test("registers, saves a route from the map, finds it on the home page, and signs out", async ({ page, context }) => {
    const account = newAccount();
    const problems = watchForErrors(page);

    await register(page, account.email, account.password);
    await expect(page.getByRole("heading", { name: "Your saved routes and stops" })).toBeVisible();
    await expect(page.getByText("Nothing saved yet")).toBeVisible();

    // Save the route from the map.
    await page.goto("/map?route=501");
    const save = page.getByRole("button", { name: "Save route 501" });
    await expect(save).toHaveAttribute("aria-pressed", "false");
    await save.click();
    const saved = page.getByRole("button", { name: "Remove route 501 from saved routes" });
    await expect(saved).toHaveAttribute("aria-pressed", "true");
    await expect(saved).toContainText("Saved");

    // It is on the home page with how it is running, and links back to the map.
    await page.goto("/");
    const list = page.getByRole("list", { name: "Saved routes" });
    await expect(list.getByRole("link", { name: "Queen on the map" })).toHaveAttribute("href", "/map?route=501");
    await expect(list).toContainText("Excellent");

    // The long-lived credential is in a cookie the page cannot read, and nothing is in storage.
    const cookie = (await context.cookies()).find((candidate) => candidate.name === "trackline_refresh");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe("Lax");
    expect(cookie?.path).toBe("/api/auth");
    expect(await page.evaluate(() => document.cookie)).not.toContain("trackline_refresh");
    expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({
      local: 0,
      session: 0,
    });

    // A reload keeps the person signed in, by renewing from the cookie.
    await page.reload();
    await expect(header(page).getByText(account.email)).toBeVisible();
    await expect(list.getByRole("link", { name: "Queen on the map" })).toBeVisible();

    // Signing out forgets them at once, and stays signed out after a reload.
    await signOut(page);
    await expect(page.getByRole("heading", { name: "Your saved routes and stops" })).toHaveCount(0);
    await page.reload();
    await expect(header(page).getByRole("link", { name: "Sign in" })).toBeVisible();
    expect(problems).toEqual([]);
  });

  test("signs in again and finds what was saved, then removes it", async ({ page }) => {
    const account = newAccount();
    await register(page, account.email, account.password);
    await page.goto("/map?route=501");
    await page.getByRole("button", { name: "Save route 501" }).click();
    await expect(page.getByRole("button", { name: "Remove route 501 from saved routes" })).toBeVisible();
    await signOut(page);

    await page.goto("/login");
    await page.getByLabel("Email address").fill(account.email);
    await page.getByLabel("Password").fill(account.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(header(page).getByText(account.email)).toBeVisible();
    const list = page.getByRole("list", { name: "Saved routes" });
    await expect(list.getByRole("link", { name: "Queen on the map" })).toBeVisible();

    await list.getByRole("button", { name: "Remove route 501 from saved routes" }).click();
    await expect(page.getByText("Nothing saved yet")).toBeVisible();
  });

  test("saves a stop from the route list, and it is on the home page by name", async ({ page }) => {
    const account = newAccount();
    await register(page, account.email, account.password);
    await page.goto("/map?route=501");
    const firstStop = page.getByRole("list", { name: "Stops in order" }).getByRole("listitem").first();

    // The stop's own button carries its name as a tooltip.
    const name = (await firstStop.locator("button[title]").first().getAttribute("title")) ?? "";
    expect(name).not.toBe("");
    await firstStop.getByRole("button", { name: `Save ${name}` }).click();
    await expect(firstStop.getByRole("button", { name: `Remove ${name} from saved stops` })).toBeVisible();

    await page.goto("/");
    await expect(page.getByRole("list", { name: "Saved stops" })).toContainText(name);
  });

  test("a wrong password says so in words and keeps the address for another go", async ({ page }) => {
    const account = newAccount();
    await register(page, account.email, account.password);
    await signOut(page);

    await page.goto("/login");
    await page.getByLabel("Email address").fill(account.email);
    await page.getByLabel("Password").fill("not-the-right-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(formAlert(page)).toHaveText("Invalid email or password.");
    await expect(page.getByLabel("Email address")).toHaveValue(account.email);
    await expect(page.getByLabel("Password")).toHaveValue("");
  });

  test("an address that is already registered is refused with a reason", async ({ page }) => {
    const account = newAccount();
    await register(page, account.email, account.password);
    await signOut(page);

    await page.goto("/register");
    await page.getByLabel("Email address").fill(account.email.toUpperCase());
    await page.getByLabel("Password").fill(account.password);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(formAlert(page)).toContainText("already exists");
  });

  test("a password that is too short is refused with the rule", async ({ page }) => {
    const account = newAccount();
    await page.goto("/register");
    await page.getByLabel("Email address").fill(account.email);
    await page.getByLabel("Password").fill("short");
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(formAlert(page)).toContainText("at least 10 characters");
  });

  test("goes back to where the person was after signing in, and never to another site", async ({ page }) => {
    const account = newAccount();
    await register(page, account.email, account.password);
    await signOut(page);

    await page.goto("/login?next=%2Fmap%3Froute%3D501");
    await page.getByLabel("Email address").fill(account.email);
    await page.getByLabel("Password").fill(account.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/map\?route=501$/);
    await signOut(page);

    await page.goto("/login?next=https%3A%2F%2Fevil.example%2F");
    await page.getByLabel("Email address").fill(account.email);
    await page.getByLabel("Password").fill(account.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(header(page).getByText(account.email)).toBeVisible();
    await expect(page).toHaveURL(`${URLS.web}/`);
  });

  test("the API gives nothing to a visitor, and public reads work with a stale token in hand", async ({ request }) => {
    expect((await request.get(`${URLS.api}/api/v1/favourites`)).status()).toBe(401);
    expect((await request.get(`${URLS.api}/api/v1/auth/me`)).status()).toBe(401);
    const publicRead = await request.get(`${URLS.api}/api/v1/routes`, {
      headers: { Authorization: "Bearer stale.or.invalid" },
    });
    expect(publicRead.status()).toBe(200);
  });

  test("two people never see each other's saved routes", async ({ browser }) => {
    const first = newAccount();
    const second = newAccount();
    const one = await browser.newContext({ baseURL: URLS.web });
    const pageOne = await one.newPage();
    await register(pageOne, first.email, first.password);
    await pageOne.goto("/map?route=501");
    await pageOne.getByRole("button", { name: "Save route 501" }).click();
    await expect(pageOne.getByRole("button", { name: "Remove route 501 from saved routes" })).toBeVisible();

    const two = await browser.newContext({ baseURL: URLS.web });
    const pageTwo = await two.newPage();
    await register(pageTwo, second.email, second.password);
    await pageTwo.goto("/");

    await expect(pageTwo.getByText("Nothing saved yet")).toBeVisible();
    await expect(pageTwo.getByRole("list", { name: "Saved routes" })).toHaveCount(0);
    await one.close();
    await two.close();
  });
});
