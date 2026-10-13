import { fetchEnvelope } from "@/lib/api/client";
import type { ApiEnvelope, HealthState, RouteHealth, RouteHealthList } from "@/lib/api/types";

/**
 * Route health queries.
 *
 * Never cached: a verdict is recomputed every few seconds and one that is minutes old is
 * exactly what the envelope's age exists to warn about. The envelope is returned whole
 * because its `meta` says how old the vehicle data behind the verdict is.
 */
export function fetchRouteHealth(routeId: string, signal?: AbortSignal): Promise<ApiEnvelope<RouteHealth>> {
  return fetchEnvelope<RouteHealth>(
    `/api/v1/routes/${encodeURIComponent(routeId)}/health`,
    signal ? { signal } : {},
  );
}

/** Every route that has vehicles, worst first, optionally only those in the given states. */
export function fetchHealthRoutes(
  states: readonly HealthState[] = [],
  signal?: AbortSignal,
): Promise<ApiEnvelope<RouteHealthList>> {
  const parameters = new URLSearchParams();
  for (const state of states) {
    parameters.append("state", state);
  }
  const suffix = parameters.size === 0 ? "" : `?${parameters}`;

  return fetchEnvelope<RouteHealthList>(`/api/v1/health/routes${suffix}`, signal ? { signal } : {});
}
