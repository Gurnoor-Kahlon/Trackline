"use client";

import { useEffect, useState } from "react";

import { EmptyState } from "@/components/ui/EmptyState";
import { Sheet } from "@/components/ui/Sheet";

/**
 * Opens search.
 *
 * Search itself needs the imported network, which does not exist yet, so the
 * panel states that plainly instead of presenting an input that silently returns
 * nothing. The affordance and its keyboard shortcut are built now because they
 * belong to the shell; the query goes in when there is something to query.
 */
export function SearchTrigger() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typingElsewhere =
        target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable;

      if (event.key === "/" && !typingElsewhere && !event.metaKey && !event.ctrlKey) {
        event.preventDefault();
        setOpen(true);
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="flex w-full max-w-xs items-center gap-2 rounded-md border border-border bg-surface-raised px-3 py-2 text-left text-sm text-ink-subtle hover:border-border-strong"
      >
        <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.75}>
          <circle cx="7" cy="7" r="4.5" />
          <path d="M10.5 10.5 14 14" strokeLinecap="round" />
        </svg>
        <span className="flex-1">Route, stop, or station</span>
        <kbd className="hidden rounded border border-border px-1.5 py-0.5 text-[0.6875rem] font-medium text-ink-subtle sm:inline">
          /
        </kbd>
      </button>

      <Sheet open={open} onClose={() => setOpen(false)} title="Search">
        <EmptyState
          title="Search is not available yet"
          description="It will cover routes, stops and stations once the TTC schedule data has been imported."
        />
      </Sheet>
    </>
  );
}
