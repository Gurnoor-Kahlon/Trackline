"use client";

import { fetchAlerts, type AlertQuery } from "@/lib/api/alerts";
import type { ResponseMeta, ServiceAlert } from "@/lib/api/types";
import { usePolledEnvelope } from "@/lib/polling/usePolledEnvelope";

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
      /** Server time minus browser time when this arrived; see {@link usePolledEnvelope}. */
      serverOffsetMs: number;
      /** True when the latest refresh failed and what is shown is from an earlier one. */
      refreshFailed: boolean;
    };

/**
 * Alerts matching a query, kept current by polling.
 *
 * @param enabled false to fetch nothing, for a panel that has nothing selected
 */
export function useAlerts(query: AlertQuery, enabled = true): AlertsResult {
  const result = usePolledEnvelope(
    (signal) => fetchAlerts(query, signal),
    JSON.stringify(query),
    ALERT_REFRESH_MS,
    enabled,
  );

  if (result.status !== "ready") {
    return result;
  }
  return {
    status: "ready",
    alerts: result.data.items,
    total: result.data.totalElements,
    meta: result.meta,
    serverOffsetMs: result.serverOffsetMs,
    refreshFailed: result.refreshFailed,
  };
}
