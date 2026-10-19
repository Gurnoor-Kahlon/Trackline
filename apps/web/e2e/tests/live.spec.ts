import { expect, test } from "@playwright/test";

test.describe("live updates", () => {
  test("is live, and the age of the data keeps resetting as new readings arrive", async ({ page }) => {
    await page.goto("/map");

    await expect(page.getByText(/^Live$/).first()).toBeVisible();
    // "updated N s ago" climbs between readings and falls back when one arrives.
    const ages: number[] = [];
    for (let sample = 0; sample < 8; sample++) {
      const text = await page.getByText(/updated \d+s ago/).first().innerText();
      ages.push(Number(/(\d+)s ago/.exec(text)?.[1] ?? Number.NaN));
      await page.waitForTimeout(1500);
    }
    expect(ages.every((age) => Number.isFinite(age))).toBe(true);
    expect(Math.min(...ages)).toBeLessThanOrEqual(4);
    expect(Math.max(...ages)).toBeLessThan(20);
  });

  test("says it is reconnecting when the stream cannot be opened, and recovers by itself when it can, without a reload", async ({
    page,
  }) => {
    await page.goto("/map?route=501");
    await expect(page.getByText(/^Live$/).first()).toBeVisible();
    await expect(page.getByText("5 tracked now")).toBeVisible();

    // Choosing another route opens a new stream, and this one cannot be opened.
    await page.route("**/api/v1/stream/vehicles**", (route) => route.abort());
    await page.getByRole("complementary", { name: "Route filters" }).getByRole("button", { name: /Bus route 41/ }).click();

    await expect(page.getByText(/Reconnecting|Live data unavailable/).first()).toBeVisible({ timeout: 30_000 });
    // Not live, so the page does not claim a count it cannot vouch for.
    await expect(page.getByText(/^Live$/)).toHaveCount(0);

    // The network comes back. Nobody reloads or presses anything.
    await page.unroute("**/api/v1/stream/vehicles**");
    await expect(page.getByText(/^Live$/).first()).toBeVisible({ timeout: 60_000 });
    // Route 41 has no vehicles in the fixture feed, and once live it says so.
    await expect(page.getByRole("complementary", { name: "Selected route" })).toContainText("None reporting now");
  });
});
