"use client";

import { useEffect, useState } from "react";

import { ApiError } from "@/lib/api/client";
import type { ApiEnvelope, ResponseMeta } from "@/lib/api/types";

export type PolledResult<T> =
  | { status: "loading" }
  | { status: "error"; message: string; errorId: string | undefined }
  | {
      status: "ready";
      data: T;
      meta: ResponseMeta;
      /**
       * Server time minus browser time when this arrived. Times in the data are the server's,
       * so ages are measured against its clock, and a laptop with the wrong time still
       * reports the right age.
       */
      serverOffsetMs: number;
      /** True when the latest refresh failed and what is shown is from an earlier one. */
      refreshFailed: boolean;
    };

/**
 * An API call kept current by polling.
 *
 * A refresh never replaces what is on screen with a spinner, and a failed refresh never
 * replaces it with an error: what was last fetched stays, flagged as such, because a
 * minute-old answer is more useful than a blank panel.
 *
 * @param fetcher   the call; it must honour the signal so a superseded request is cancelled
 * @param key       identifies the question: when it changes the result starts over as loading
 * @param intervalMs how often to ask again
 * @param enabled   false to fetch nothing, for a panel that has nothing selected
 */
export function usePolledEnvelope<T>(
  fetcher: (signal: AbortSignal) => Promise<ApiEnvelope<T>>,
  key: string,
  intervalMs: number,
  enabled = true,
): PolledResult<T> {
  const [result, setResult] = useState<PolledResult<T>>({ status: "loading" });

  useEffect(() => {
    if (!enabled) {
      return;
    }
    // A new question starts from loading, not from the previous question's answer.
    setResult({ status: "loading" });

    const controller = new AbortController();

    async function load() {
      try {
        const received = Date.now();
        const envelope = await fetcher(controller.signal);
        // An answer for a question that has since changed must not land under the new one.
        if (controller.signal.aborted) {
          return;
        }
        setResult({
          status: "ready",
          data: envelope.data,
          meta: envelope.meta,
          serverOffsetMs: Date.parse(envelope.meta.generatedAt) - received,
          refreshFailed: false,
        });
      } catch (cause) {
        if (controller.signal.aborted) {
          return;
        }
        setResult((previous) =>
          previous.status === "ready"
            ? { ...previous, refreshFailed: true }
            : {
                status: "error",
                message:
                  cause instanceof ApiError && cause.isResponse
                    ? cause.message
                    : "The Trackline API could not be reached.",
                errorId: cause instanceof ApiError ? cause.errorId : undefined,
              },
        );
      }
    }

    void load();
    const timer = setInterval(() => void load(), intervalMs);

    return () => {
      controller.abort();
      clearInterval(timer);
    };
    // The key stands for the fetcher: it is rebuilt every render and must not restart polling.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled, intervalMs]);

  return result;
}
