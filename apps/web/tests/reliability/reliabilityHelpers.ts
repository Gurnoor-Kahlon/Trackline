import type {
  InsufficientReason,
  ReliabilityCoverage,
  ReliabilityFigures,
  ReliabilityHour,
  ReliabilityNeeds,
  ReliabilityWeekday,
  RouteReliability,
} from "@/lib/api/types";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";

import { meta } from "../health/helpers";

/** What the server is configured to need, as it ships. */
export const NEEDS: ReliabilityNeeds = {
  coveredHours: 24,
  samplesPerHour: 20,
  judgedSamples: 240,
  cellSamples: 60,
  daysPerHour: 3,
  daysPerWeekday: 2,
};

export function figures(overrides: Partial<ReliabilityFigures> = {}): ReliabilityFigures {
  return {
    samples: 1440,
    judged: 1440,
    unjudged: 0,
    excellent: 480,
    normal: 480,
    unstable: 240,
    majorDisruption: 240,
    steadyShare: 0.667,
    variation: 0.3,
    gapRatio: 1.2,
    bunchingShare: 0.05,
    ...overrides,
  };
}

export function coverage(overrides: Partial<ReliabilityCoverage> = {}): ReliabilityCoverage {
  return {
    hoursCovered: 24,
    hoursExcluded: 0,
    days: 1,
    samples: 1440,
    judgedSamples: 1440,
    firstHour: "2026-10-13T04:00:00Z",
    lastHour: "2026-10-14T03:00:00Z",
    ...overrides,
  };
}

export function hour(hourOfDay: number, steadyShare: number, days = 3): ReliabilityHour {
  return { hour: hourOfDay, days, figures: figures({ samples: 60 * days, judged: 60 * days, steadyShare }) };
}

export function weekday(isoWeekday: number, steadyShare: number, days = 2): ReliabilityWeekday {
  return { isoWeekday, days, figures: figures({ samples: 60 * days, judged: 60 * days, steadyShare }) };
}

/** Enough history, with no breakdown bar yet. */
export function available(overrides: Partial<RouteReliability> = {}): RouteReliability {
  return {
    routeId: "501",
    status: "AVAILABLE",
    reason: null,
    windowDays: 7,
    from: "2026-10-07T14:00:30Z",
    through: "2026-10-14T04:00:00Z",
    timeZone: "America/Toronto",
    needs: NEEDS,
    coverage: coverage(),
    summary: figures(),
    byHour: [],
    byWeekday: [],
    ...overrides,
  };
}

export function insufficient(
  reason: InsufficientReason,
  overrides: Partial<RouteReliability> = {},
): RouteReliability {
  return {
    ...available(),
    status: "INSUFFICIENT_HISTORY",
    reason,
    summary: null,
    ...overrides,
  };
}

export function reliabilityReady(data: RouteReliability): PolledResult<RouteReliability> {
  return {
    status: "ready",
    data,
    meta: meta({ source: "STORED", feedTimestamp: null, ageSeconds: null, stale: false }),
    serverOffsetMs: 0,
    refreshFailed: false,
  };
}
