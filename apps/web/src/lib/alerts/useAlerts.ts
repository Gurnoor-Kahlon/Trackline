"use client";

import { useEffect, useState } from "react";

import { fetchAlerts, type AlertQuery } from "@/lib/api/alerts";
import { ApiError } from "@/lib/api/client";
import type { ResponseMeta, ServiceAlert } from "@/lib/api/types";

/** The alerts feed is polled every minute, so asking more often finds nothing new. */
export const ALERT_REFRESH_MS = 60_000;

export type AlertsResult =
  | { status: "loading" }
  | { status: "error"; message: string; errorId: string | undefined }
  | {
      status: "ready";
      alerts: ServiceAlert[];
      /** Matching alerts across all pages, which can exceed what was asked for. */
      total: number;
      meta: ResponseMeta;
      /**
       * Server time minus browser time when this arrived. Alert times are the server's,
       * so ages are measured against its clock, and a laptop with the wrong time still
       * reports the right age.
       */
      serverOffsetMs: number;
      /** True when the latest refresh failed and what is shown is from an earlier one. */
      refreshFailed: boolean;
    };

/**
 * Alerts matching a query, kept current by polling.
 *
 * A refresh never replaces what is on screen with a spinner, and a failed refresh never
 * replaces it with an error: the alerts last fetched stay, flagged as such, because an
 * alert that was in force a minute ago is more useful than a blank panel.
 *
 * @param enabled false to fetch nothing, for a panel that has nothing selected
 */
export function useAlerts(query: AlertQuery, enabled = true): AlertsResult {
  const [result, setResult] = useState<AlertsResult>({ status: "loading" });
  const key = JSON.stringify(query);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    // A new question starts from loading, not from the previous question's answer.
    setResult({ status: "loading" });

    const controller = new AbortController();
    const parsed = JSON.parse(key) as AlertQuery;

    async function load() {
      try {
        const received = Date.now();
        const envelope = await fetchAlerts(parsed, controller.signal);
        // An answer for a question that has since changed must not land under the new one.
        if (controller.signal.aborted) {
          return;
        }
        setResult({
          status: "ready",
          alerts: envelope.data.items,
          total: envelope.data.totalElements,
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
    const timer = setInterval(() => void load(), ALERT_REFRESH_MS);

    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [key, enabled]);

  return result;
}
