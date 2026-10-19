"use client";

import { fetchRouteBunching } from "@/lib/api/bunching";
import { fetchHealthRoutes, fetchRouteHealth } from "@/lib/api/health";
import { fetchRouteReliability } from "@/lib/api/reliability";
import type {
  ReliabilityWindowDays,
  RouteBunching,
  RouteHealth,
  RouteHealthList,
  RouteReliability,
} from "@/lib/api/types";
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

/** Where one route's vehicles are and which are bunched; fetches nothing while no route is selected. */
export function useRouteBunching(routeId: string | null): PolledResult<RouteBunching> {
  return usePolledEnvelope(
    (signal) => fetchRouteBunching(routeId ?? "", signal),
    `bunching:${routeId ?? ""}`,
    HEALTH_REFRESH_MS,
    routeId !== null,
  );
}

/**
 * How often reliability is asked for again. It is built from hourly summaries that are rebuilt every ten
 * minutes, so a faster poll would only fetch the same answer.
 */
export const RELIABILITY_REFRESH_MS = 5 * 60_000;

/** How steady a route has been over a window; fetches nothing while no route is selected. */
export function useRouteReliability(
  routeId: string | null,
  windowDays: ReliabilityWindowDays,
): PolledResult<RouteReliability> {
  return usePolledEnvelope(
    (signal) => fetchRouteReliability(routeId ?? "", windowDays, signal),
    `reliability:${routeId ?? ""}:${windowDays}`,
    RELIABILITY_REFRESH_MS,
    routeId !== null,
  );
}
