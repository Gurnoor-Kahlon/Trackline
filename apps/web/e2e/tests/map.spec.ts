import { expect, test, type Page } from "@playwright/test";

import { setScenario, watchForErrors } from "./support.ts";

const filters = (page: Page) => page.getByRole("complementary", { name: "Route filters" });
const selected = (page: Page) => page.getByRole("complementary", { name: "Selected route" });

test.describe("the map", () => {
  test.afterEach(async () => {
    await setScenario("even");
  });

  test("lists the imported routes, saying which have no live data", async ({ page }) => {
    const problems = watchForErrors(page);

    await page.goto("/map");

    await expect(filters(page).getByText("3 of 3 routes")).toBeVisible();
    await expect(filters(page).getByRole("button", { name: /Subway route 1 Line 1/ })).toContainText("No live data");
    await expect(filters(page).getByRole("button", { name: /Bus route 41 Keele/ })).toBeVisible();
    await expect(filters(page).getByRole("button", { name: /Streetcar route 501 Queen/ })).toBeVisible();
    expect(problems).toEqual([]);
  });

  test("narrows the list by mode and by what is typed, and says how many remain", async ({ page }) => {
    await page.goto("/map");
    await expect(filters(page).getByText("3 of 3 routes")).toBeVisible();

    await filters(page).getByRole("button", { name: "Bus", exact: true }).click();
    await expect(filters(page).getByText("1 of 3 routes")).toBeVisible();
    await expect(filters(page).getByRole("button", { name: /Keele/ })).toBeVisible();
    await expect(filters(page).getByRole("button", { name: /Queen/ })).toHaveCount(0);

    // Back to all, then by name.
    await filters(page).getByRole("button", { name: "Bus", exact: true }).click();
    await filters(page).getByRole("searchbox").fill("queen");
    await expect(filters(page).getByText("1 of 3 routes")).toBeVisible();
    await expect(filters(page).getByRole("button", { name: /Queen/ })).toBeVisible();
  });

  test("opens a route with its live vehicles, its health and its stops", async ({ page }) => {
    await page.goto("/map");

    await filters(page).getByRole("button", { name: /Streetcar route 501 Queen/ }).click();

    const panel = selected(page);
    await expect(panel.getByRole("heading", { name: "Queen" })).toBeVisible();
    await expect(panel).toContainText("5 tracked now");
    await expect(panel).toContainText("Excellent");
    await expect(panel).toContainText("5 vehicles measured, none running together while a gap opens elsewhere.");
    await expect(panel.getByRole("list", { name: "Stops in order" }).getByRole("listitem").first()).toBeVisible();
    // Nothing has been recorded for long enough to say how steady the route has been, and it says so.
    await expect(panel).toContainText("Nothing summarised yet");
  });

  test("opens straight onto a route named in the address", async ({ page }) => {
    await page.goto("/map?route=501");

    await expect(selected(page).getByRole("heading", { name: "Queen" })).toBeVisible();
    await expect(selected(page)).toContainText("5 tracked now");
  });

  test("opens on the plain map when the address names a route the schedule does not have", async ({ page }) => {
    await page.goto("/map?route=no-such-route");

    await expect(filters(page).getByText("3 of 3 routes")).toBeVisible();
    await expect(selected(page)).toContainText("No route selected");
  });

  test("says a subway line publishes no vehicles, instead of showing an empty map as a fault", async ({ page }) => {
    await page.goto("/map");

    await filters(page).getByRole("button", { name: /Subway route 1/ }).click();

    await expect(selected(page)).toContainText("Not published");
  });

  test("notices when the vehicles bunch up, which needs a gap elsewhere and not just close vehicles", async ({ page }) => {
    // The verdict follows several analyses of a feed that moves every two seconds, so this takes about half a minute by design.
    test.slow();
    await page.goto("/map?route=501");
    await expect(selected(page)).toContainText("none running together");

    await setScenario("bunched");

    // The fixture feed now holds three vehicles together and a long gap after them.
    await expect(selected(page)).toContainText(
      /3 running within [\d,]+ m of each other while the largest gap elsewhere is/,
      { timeout: 40_000 },
    );
    await expect(selected(page)).toContainText("Major disruption");
  });

  test("goes back to even spacing and says so once the vehicles have spread out again", async ({ page }) => {
    // Two such waits, one after the other.
    test.slow();
    await setScenario("bunched");
    await page.goto("/map?route=501");
    await expect(selected(page)).toContainText("3 running within", { timeout: 40_000 });

    await setScenario("even");

    await expect(selected(page)).toContainText("none running together", { timeout: 40_000 });
    await expect(selected(page)).toContainText("Excellent");
  });
});
