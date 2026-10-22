"use client";

import { useEffect, useState } from "react";

import { ApiError } from "@/lib/api/client";
import { searchTransit } from "@/lib/api/search";
import type { SearchHit } from "@/lib/api/types";

/** Long enough for a person to finish a word, short enough to feel immediate, and few enough requests to stay under the limit. */
export const SEARCH_DEBOUNCE_MS = 250;

export type SearchState =
  /** Nothing typed, so nothing asked. */
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; query: string; hits: SearchHit[] }
  | { status: "error"; message: string };

/** What came back for one query. Kept with the query it answers, so an answer is never taken for the answer to something newer. */
type Settled = { query: string; outcome: { status: "ready"; hits: SearchHit[] } | { status: "error"; message: string } };

/**
 * Results for what has been typed, asked for once the typing pauses.
 *
 * Idle and loading are not stored but worked out: with nothing typed it is idle, and with something typed that has no answer yet it
 * is loading. So the only state set is an answer, from a callback, and a slow answer to an earlier query, which is also cancelled
 * when the text changes, can never be shown beside a newer one. The API limits searches per address, and an answer saying so is
 * reported as what it is, not as a fault.
 */
export function useSearch(text: string, debounceMs: number = SEARCH_DEBOUNCE_MS): SearchState {
  const query = text.trim();
  const [settled, setSettled] = useState<Settled | null>(null);

  useEffect(() => {
    if (query === "") {
      return;
    }

    const controller = new AbortController();
    const timer = setTimeout(() => {
      // Either way of finishing is ignored once the text has moved on: a request can complete after it was cancelled, and what it
      // carries is the answer to a question nobody is asking now.
      searchTransit(query, controller.signal).then(
        (hits) => {
          if (!controller.signal.aborted) {
            setSettled({ query, outcome: { status: "ready", hits } });
          }
        },
        (failure: unknown) => {
          if (!controller.signal.aborted) {
            setSettled({ query, outcome: { status: "error", message: describeFailure(failure) } });
          }
        },
      );
    }, debounceMs);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, debounceMs]);

  if (query === "") {
    return { status: "idle" };
  }
  if (settled === null || settled.query !== query) {
    return { status: "loading" };
  }
  return settled.outcome.status === "ready"
    ? { status: "ready", query, hits: settled.outcome.hits }
    : { status: "error", message: settled.outcome.message };
}

function describeFailure(failure: unknown): string {
  if (failure instanceof ApiError) {
    if (failure.status === 429) {
      return "You have searched a lot in a short time. Wait a moment and try again.";
    }
    return failure.isResponse ? "Search is not working right now." : "Could not reach Trackline to search.";
  }
  return "Search is not working right now.";
}
