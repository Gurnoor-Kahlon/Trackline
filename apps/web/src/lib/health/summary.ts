import type { HealthState, RouteHealthSummary } from "@/lib/api/types";

export type HealthTally = {
  /** Routes that have a verdict. */
  judged: number;
  /** Routes with vehicles but too few of them, or none placed, to judge. */
  notJudged: number;
  byState: Record<HealthState, number>;
};

export function tally(routes: readonly RouteHealthSummary[]): HealthTally {
  const byState: Record<HealthState, number> = {
    EXCELLENT: 0,
    NORMAL: 0,
    UNSTABLE: 0,
    MAJOR_DISRUPTION: 0,
    UNKNOWN: 0,
  };
  for (const route of routes) {
    byState[route.state] += 1;
  }
  return {
    judged: routes.length - byState.UNKNOWN,
    notJudged: byState.UNKNOWN,
    byState,
  };
}

/** The routes whose spacing is worst, in the order given: the API already lists worst first. */
export function needingAttention(routes: readonly RouteHealthSummary[], limit: number): RouteHealthSummary[] {
  return routes.filter((route) => route.state === "UNSTABLE" || route.state === "MAJOR_DISRUPTION").slice(0, limit);
}
