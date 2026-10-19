import { startStack, stopRemembered } from "./stack.ts";

/**
 * Starts the backend before any test, and returns the function that stops it afterwards.
 *
 * Anything a previous run left behind is stopped first, so a crashed run cannot make the next one talk to a stale server.
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  stopRemembered();
  return startStack();
}
