"use client";

import { useSyncExternalStore } from "react";

function subscribe(listener: () => void): () => void {
  const timer = setInterval(listener, 1000);
  return () => clearInterval(timer);
}

/** Whole seconds, so the value is stable between calls within a second. */
function getSnapshot(): number {
  return Math.floor(Date.now() / 1000) * 1000;
}

/** The server has no meaningful "now" to hand a browser, so it hands none. */
function getServerSnapshot(): null {
  return null;
}

/**
 * The current time, to the second, re-read every second.
 *
 * Null on the server and during hydration, so the first client render matches the
 * server's. Anything showing an age renders nothing for that one moment instead of a
 * time that would differ between the two and fail to hydrate.
 */
export function useNow(): number | null {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
