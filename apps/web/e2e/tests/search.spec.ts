import { expect, test, type Page } from "@playwright/test";

import { URLS } from "../stack.ts";

const searchBox = (page: Page) => page.getByRole("searchbox", { name: "Search routes and stops" });

async function openSearch(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: /route, stop, or station/i }).click();
  await expect(searchBox(page)).toBeFocused();
}

test.describe("search", () => {
  test("finds a route by its number and opens it on the map", async ({ page }) => {
    await openSearch(page);

    await searchBox(page).fill("501");
    await page.getByRole("region", { name: "Routes" }).getByRole("link", { name: /501\s*Queen/ }).click();

    await expect(page).toHaveURL(/\/map\?route=501$/);
    await expect(page.getByRole("complementary", { name: "Selected route" }).getByRole("heading", { name: "Queen" })).toBeVisible();
    // Following a result closes the search.
    await expect(searchBox(page)).toHaveCount(0);
  });

  test("finds a route by part of its name, and a near miss", async ({ page }) => {
    await openSearch(page);

    await searchBox(page).fill("keel");

    await expect(page.getByRole("region", { name: "Routes" }).getByRole("link", { name: /41\s*Keele/ })).toBeVisible();
  });

  test("opens a stop found by name to the routes that call there", async ({ page, request }) => {
    const stops = (await (await request.get(`${URLS.api}/api/v1/routes/501/stops`)).json()) as {
      data: { name: string }[];
    };
    const name = stops.data[0]?.name;
    expect(name).toBeTruthy();
    await openSearch(page);

    await searchBox(page).fill(name ?? "");
    const stopsSection = page.getByRole("region", { name: "Stops" });
    await stopsSection.getByRole("button", { name: name ?? "" }).first().click();
    await stopsSection.getByRole("link", { name: /Queen/ }).first().click();

    await expect(page).toHaveURL(/\/map\?route=501$/);
  });

  test("says plainly when nothing matches", async ({ page }) => {
    await openSearch(page);

    await searchBox(page).fill("zzzzqqqq");

    await expect(page.getByText("No routes or stops match")).toBeVisible();
    await expect(page.getByRole("status")).toContainText("No results for zzzzqqqq.");
  });

  test("opens from the keyboard with the slash key, and closes with escape", async ({ page }) => {
    await page.goto("/alerts");

    await page.keyboard.press("/");
    await expect(searchBox(page)).toBeFocused();
    await page.keyboard.press("Escape");

    await expect(searchBox(page)).toHaveCount(0);
  });
});
