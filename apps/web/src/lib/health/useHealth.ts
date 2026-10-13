"use client";

import { fetchHealthRoutes, fetchRouteHealth } from "@/lib/api/health";
import type { RouteHealth, RouteHealthList } from "@/lib/api/types";
import { usePolledEnvelope, type PolledResult } from "@/lib/polling/usePolledEnvelope";

/**
 * How often health is asked for again. Verdicts are recomputed with every vehicle cycle, about
 * every 20 s, so asking more often finds nothing new, and each answer is a cache read.
 */
export const HEALTH_REFRESH_MS = 30_000;

/** The health of one route; fetches nothing while no route is selected. */
export function useRouteHealth(routeId: string | null): PolledResult<RouteHealth> {
  return usePolledEnvelope(
    (signal) => fetchRouteHealth(routeId ?? "", signal),
    `route:${routeId ?? ""}`,
    HEALTH_REFRESH_MS,
    routeId !== null,
  );
}

/** The verdict for every route that has vehicles, worst first. */
export function useHealthRoutes(): PolledResult<RouteHealthList> {
  return usePolledEnvelope((signal) => fetchHealthRoutes([], signal), "all", HEALTH_REFRESH_MS);
}
