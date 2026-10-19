import { defineConfig, devices } from "@playwright/test";

import { URLS } from "./e2e/stack.ts";

/**
 * End-to-end tests: a real browser against the real application, with only the TTC replaced (see e2e/stack.ts).
 *
 * One worker, because the tests share one backend and some change what it is serving (a different spacing of the fixture
 * fleet, an account) and must not interleave with ones that read it.
 *
 * Chrome is the one installed on the machine, not a browser downloaded for the purpose. GitHub's runners have it, and so
 * do most developers.
 */
export default defineConfig({
  testDir: "./e2e/tests",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  globalSetup: "./e2e/global-setup.ts",
  use: {
    baseURL: URLS.web,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    // The development server, so there is no build to wait for. The API's address is what a browser would be given.
    command: "node scripts/sync-maplibre-worker.mjs && npx --no-install next dev -p 3100",
    url: URLS.web,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: { NEXT_PUBLIC_API_BASE_URL: URLS.api, NEXT_TELEMETRY_DISABLED: "1" },
  },
  projects: [
    {
      name: "desktop",
      testIgnore: /mobile\.spec\.ts/,
      use: { ...devices["Desktop Chrome"], channel: "chrome" },
    },
    {
      name: "mobile",
      testMatch: /mobile\.spec\.ts/,
      use: { ...devices["Pixel 7"], channel: "chrome" },
    },
  ],
});
