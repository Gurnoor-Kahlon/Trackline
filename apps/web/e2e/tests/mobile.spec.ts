import { expect, test, type Page } from "@playwright/test";

/** How far the page is wider than the screen; anything above zero is a sideways scroll. */
const overflow = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

test.describe("on a phone", () => {
  test("the home page fits the screen and keeps its navigation", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "What is happening on Toronto transit right now?" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Map" })).toBeVisible();
    expect(await overflow(page)).toBeLessThanOrEqual(0);
  });

  test("the map opens a route in a sheet, and the page does not scroll sideways", async ({ page }) => {
    await page.goto("/map?route=501");

    const open = page.getByRole("button", { name: /Route 501/ });
    await expect(open).toBeVisible();
    await open.click();
    await expect(page.getByRole("dialog").getByRole("heading", { name: "Queen" })).toBeVisible();
    expect(await overflow(page)).toBeLessThanOrEqual(0);
  });

  test("the sign-in form is usable at this size", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByLabel("Email address")).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    expect(await overflow(page)).toBeLessThanOrEqual(0);
  });
});
