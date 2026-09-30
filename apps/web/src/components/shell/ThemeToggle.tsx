"use client";

import { useSyncExternalStore } from "react";

import { Button } from "@/components/ui/Button";
import { getServerTheme, getTheme, setTheme, subscribe } from "@/lib/theme";

/**
 * Switches between light and dark.
 *
 * The theme is read from the document rather than held in component state, so the
 * button always describes what is actually on screen — including on first render,
 * where a blocking script has already applied a stored preference that React knows
 * nothing about. Before hydration the store reports null and a same-sized
 * placeholder is rendered, which keeps the header from shifting.
 */
export function ThemeToggle() {
  const theme = useSyncExternalStore(subscribe, getTheme, getServerTheme);

  if (theme === null) {
    return <span aria-hidden className="inline-block size-9" />;
  }

  const isDark = theme === "dark";

  return (
    <Button
      variant="quiet"
      onClick={() => setTheme(isDark ? "light" : "dark")}
      className="w-9 px-0"
      aria-pressed={isDark}
    >
      <span className="sr-only">
        {isDark ? "Switch to light theme" : "Switch to dark theme"}
      </span>
      {isDark ? (
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.5}>
          <circle cx="8" cy="8" r="3.25" />
          <path
            d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3.1 3.1l1.05 1.05M11.85 11.85l1.05 1.05M12.9 3.1l-1.05 1.05M4.15 11.85L3.1 12.9"
            strokeLinecap="round"
          />
        </svg>
      ) : (
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.5}>
          <path d="M13.5 9.6A5.8 5.8 0 0 1 6.4 2.5a5.8 5.8 0 1 0 7.1 7.1z" strokeLinejoin="round" />
        </svg>
      )}
    </Button>
  );
}
