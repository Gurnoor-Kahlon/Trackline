import { expect, test } from "@playwright/test";

import { watchForErrors } from "./support.ts";

test.describe("the home page", () => {
  test("says what the fleet is doing and how old that is", async ({ page }) => {
    const problems = watchForErrors(page);

    await page.goto("/");

    await expect(
      page.getByRole("heading", { name: "What is happening on Toronto transit right now?" }),
    ).toBeVisible();
    // The fixture feed holds five vehicles on one route and one alert.
    const status = page.locator("section", { has: page.getByRole("heading", { name: "Network status" }) });
    await expect(status).toContainText("Vehicles tracked");
    await expect(status).toContainText(/Vehicles tracked\s*5/);
    await expect(status).toContainText(/Routes reporting\s*1/);
    await expect(status).toContainText(/Active alerts\s*1/);
    await expect(status).toContainText(/Updated \d+ seconds? ago/);
    expect(problems).toEqual([]);
  });

  test("judges the route it can and says how many it could not", async ({ page }) => {
    await page.goto("/");

    const health = page.locator("section", { has: page.getByRole("heading", { name: "Route health" }) });
    await expect(health).toContainText("Of 1 routes with enough vehicles to judge, 0 have uneven spacing.");
    await expect(health).toContainText("not on delay, which the TTC does not publish");
  });

  test("shows the alert with when Trackline saw it, not when it began", async ({ page }) => {
    await page.goto("/");

    const alerts = page.locator("section", { has: page.getByRole("heading", { name: "Service alerts" }) });
    await expect(alerts).toContainText("501 Queen streetcars are diverting around construction");
    await expect(alerts).toContainText(/First seen .* last seen/);
  });

  test("shows nothing about saved routes to a visitor, on a page that works without an account", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Your saved routes and stops" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  });

  test("puts the skip link first for someone using a keyboard", async ({ page }) => {
    await page.goto("/");

    await page.keyboard.press("Tab");

    await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  });
});
