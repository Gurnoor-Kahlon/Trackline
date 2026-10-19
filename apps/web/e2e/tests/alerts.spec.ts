import { expect, test } from "@playwright/test";

test.describe("the alerts page", () => {
  test("lists the active alert and states plainly what the feed does not say", async ({ page }) => {
    await page.goto("/alerts");

    await expect(page.getByRole("heading", { name: "Service alerts" })).toBeVisible();
    await expect(page.getByText("The TTC publishes no start or end time for an alert")).toBeVisible();
    await expect(page.getByText("501 Queen streetcars are diverting around construction")).toBeVisible();
    await expect(page.getByText(/1 active alert/)).toBeVisible();
  });

  test("names the route the alert is about", async ({ page }) => {
    await page.goto("/alerts");

    await expect(page.getByText("Routes named by this alert:")).toBeVisible();
    await expect(page.getByText("Queen", { exact: false }).first()).toBeVisible();
  });
});
