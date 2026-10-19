import { fetchEnvelope } from "@/lib/api/client";
import type { ApiEnvelope, ReliabilityWindowDays, RouteReliability } from "@/lib/api/types";

/**
 * How steady a route's service has been over a past window, with how much history stands behind it.
 *
 * The figures are computed from hourly summaries that change once an hour, so there is no reason to
 * ask for them fresh; the envelope's source is STORED and carries no feed age.
 */
export function fetchRouteReliability(
  routeId: string,
  windowDays: ReliabilityWindowDays,
  signal?: AbortSignal,
): Promise<ApiEnvelope<RouteReliability>> {
  return fetchEnvelope<RouteReliability>(
    `/api/v1/routes/${encodeURIComponent(routeId)}/reliability?window=${windowDays}d`,
    signal ? { signal } : {},
  );
}
