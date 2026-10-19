import { expect, type Page } from "@playwright/test";

import { URLS } from "../stack.ts";

/** Chooses how the fixture fleet is spaced. The tests that change it put it back. */
export async function setScenario(name: "even" | "bunched"): Promise<void> {
  const response = await fetch(`${URLS.fixture}/control/scenario`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name }),
  });
  expect(response.ok).toBe(true);
}

/** Errors the page raised itself: uncaught exceptions, and API calls that failed. Development-server noise is not among them. */
export function watchForErrors(page: Page): string[] {
  const problems: string[] = [];
  page.on("pageerror", (error) => problems.push(`uncaught: ${error.message}`));
  page.on("response", (response) => {
    // A 401 is what an anonymous visitor's silent sign-in check is told, and is not a failure.
    if (response.status() >= 400 && response.url().startsWith(URLS.api) && response.status() !== 401) {
      problems.push(`${response.status()} ${response.url()}`);
    }
  });
  return problems;
}

/** A new address for each run, so an account made by one test is never found by another. */
export function newAccount(): { email: string; password: string } {
  const unique = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  return { email: `e2e-${unique}@example.test`, password: `e2e-passphrase-${unique}` };
}
