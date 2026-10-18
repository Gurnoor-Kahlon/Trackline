import { fetchEnvelope } from "@/lib/api/client";
import type { ApiEnvelope, RouteBunching } from "@/lib/api/types";

/**
 * Where a route's vehicles are and which are bunched, with the bunches of the last day.
 *
 * Never cached: positions are recomputed every few seconds, and the envelope's `meta` says how old the
 * vehicle data behind them is.
 */
export function fetchRouteBunching(routeId: string, signal?: AbortSignal): Promise<ApiEnvelope<RouteBunching>> {
  return fetchEnvelope<RouteBunching>(
    `/api/v1/routes/${encodeURIComponent(routeId)}/bunching`,
    signal ? { signal } : {},
  );
}
