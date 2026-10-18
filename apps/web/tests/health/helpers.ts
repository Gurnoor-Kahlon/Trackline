import type {
  ApiEnvelope,
  DirectionHealth,
  HealthMetrics,
  ResponseMeta,
  RouteHealth,
  RouteHealthList,
  RouteHealthSummary,
} from "@/lib/api/types";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";

export function metrics(overrides: Partial<HealthMetrics> = {}): HealthMetrics {
  return {
    vehicles: 5,
    gaps: 4,
    meanGapMetres: 3000,
    maxGapMetres: 4200,
    variation: 0.31,
    gapRatio: 1.4,
    expectedGapMetres: 3000,
    expectedVehicles: 6,
    supplyRatio: 0.83,
    scheduledHeadwaySeconds: 600,
    speedMetresPerSecond: 5,
    staleVehicles: 0,
    terminalVehicles: 0,
    basis: "SCHEDULE",
    bunchingClusters: 0,
    ...overrides,
  };
}

export function direction(overrides: Partial<DirectionHealth> = {}): DirectionHealth {
  return {
    directionId: 0,
    state: "NORMAL",
    confidence: "HIGH",
    reasons: [],
    metrics: metrics(),
    ...overrides,
  };
}

/** A route whose first direction decided its verdict, as the API reports one. */
export function routeHealth(overrides: Partial<RouteHealth> = {}): RouteHealth {
  const directions = overrides.directions ?? [direction()];
  const decisive = directions[0]!;
  return {
    routeId: "501",
    state: decisive.state,
    confidence: decisive.confidence,
    reasons: decisive.reasons,
    directions,
    unplacedVehicles: 0,
    computedAt: "2026-10-12T14:00:02Z",
    ...overrides,
  };
}

export function unknownRoute(
  reason: RouteHealth["reasons"][number],
  overrides: Partial<RouteHealth> = {},
): RouteHealth {
  return {
    routeId: "1",
    state: "UNKNOWN",
    confidence: "NONE",
    reasons: [reason],
    directions: [],
    unplacedVehicles: 0,
    computedAt: "2026-10-12T14:00:02Z",
    ...overrides,
  };
}

export function meta(overrides: Partial<ResponseMeta> = {}): ResponseMeta {
  return {
    generatedAt: "2026-10-12T14:00:30Z",
    feedTimestamp: "2026-10-12T14:00:00Z",
    ageSeconds: 30,
    stale: false,
    degraded: false,
    source: "LIVE",
    ...overrides,
  };
}

/** Server "now" in tests: thirty seconds after the feed's timestamp. */
export const SERVER_NOW = Date.parse("2026-10-12T14:00:30Z");

export function ready(
  health: RouteHealth,
  overrides: { meta?: Partial<ResponseMeta>; refreshFailed?: boolean } = {},
): PolledResult<RouteHealth> {
  return {
    status: "ready",
    data: health,
    meta: meta(overrides.meta),
    serverOffsetMs: 0,
    refreshFailed: overrides.refreshFailed ?? false,
  };
}

export function summary(overrides: Partial<RouteHealthSummary> = {}): RouteHealthSummary {
  return {
    routeId: "501",
    shortName: "501",
    longName: "Queen",
    mode: "STREETCAR",
    colour: "DA251D",
    textColour: "FFFFFF",
    state: "UNSTABLE",
    confidence: "MEDIUM",
    reasons: ["HIGH_HEADWAY_VARIATION"],
    vehicles: 8,
    ...overrides,
  };
}

export function listEnvelope(
  routes: RouteHealthSummary[],
  overrides: Partial<ResponseMeta> = {},
): ApiEnvelope<RouteHealthList> {
  return { data: { routes, computedAt: "2026-10-12T14:00:02Z" }, meta: meta(overrides) };
}
