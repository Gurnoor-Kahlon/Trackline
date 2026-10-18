import type {
  Bunch,
  BunchingEvent,
  DirectionSpacing,
  RouteBunching,
  SpacingVehicle,
  VehicleRole,
} from "@/lib/api/types";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";

import { meta } from "./helpers";

export function vehicle(vehicleId: string, progressMetres: number, role: VehicleRole = "SPACED"): SpacingVehicle {
  return { vehicleId, progressMetres, role };
}

export function bunch(vehicleIds: string[], overrides: Partial<Bunch> = {}): Bunch {
  return { vehicleIds, spanMetres: 400, serviceGapMetres: 6500, severity: "MODERATE", ...overrides };
}

export function spacing(overrides: Partial<DirectionSpacing> = {}): DirectionSpacing {
  return {
    directionId: 0,
    lengthMetres: 20_000,
    loop: false,
    expectedGapMetres: 3000,
    basis: "SCHEDULE",
    vehicles: [vehicle("a", 4000), vehicle("b", 8000), vehicle("c", 12_000)],
    bunches: [],
    ...overrides,
  };
}

/** A direction with a pair running together and a wide gap after them. */
export function bunched(): DirectionSpacing {
  return spacing({
    vehicles: [
      vehicle("start", 80, "AT_TERMINAL"),
      vehicle("a", 1000, "BUNCHED"),
      vehicle("b", 1400, "BUNCHED"),
      vehicle("c", 8000),
      vehicle("d", 11_000),
      vehicle("old", 15_000, "STALE"),
    ],
    bunches: [bunch(["a", "b"])],
  });
}

export function event(overrides: Partial<BunchingEvent> = {}): BunchingEvent {
  return {
    id: 1,
    directionId: 0,
    status: "ACTIVE",
    vehicleIds: ["a", "b"],
    peakVehicles: 2,
    spanMetres: 400,
    expectedGapMetres: 3000,
    serviceGapMetres: 6500,
    basis: "SCHEDULE",
    severity: "MODERATE",
    peakSeverity: "MODERATE",
    sightings: 12,
    firstSeenAt: "2026-10-12T13:50:00Z",
    lastSeenAt: "2026-10-12T14:00:10Z",
    resolvedAt: null,
    ...overrides,
  };
}

export function bunchingReady(
  directions: DirectionSpacing[],
  events: BunchingEvent[] = [],
  overrides: { meta?: Parameters<typeof meta>[0]; refreshFailed?: boolean } = {},
): PolledResult<RouteBunching> {
  return {
    status: "ready",
    data: { routeId: "501", directions, events, computedAt: "2026-10-12T14:00:02Z" },
    meta: meta(overrides.meta),
    serverOffsetMs: 0,
    refreshFailed: overrides.refreshFailed ?? false,
  };
}
