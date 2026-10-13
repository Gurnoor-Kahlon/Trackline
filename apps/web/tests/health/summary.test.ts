import { describe, expect, it } from "vitest";

import { needingAttention, tally } from "@/lib/health/summary";

import { summary } from "./helpers";

describe("tallying the network", () => {
  it("counts routes in each state and separates those that could be judged", () => {
    const counts = tally([
      summary({ state: "EXCELLENT" }),
      summary({ state: "NORMAL" }),
      summary({ state: "NORMAL" }),
      summary({ state: "UNSTABLE" }),
      summary({ state: "MAJOR_DISRUPTION" }),
      summary({ state: "UNKNOWN" }),
      summary({ state: "UNKNOWN" }),
    ]);

    expect(counts.judged).toBe(5);
    expect(counts.notJudged).toBe(2);
    expect(counts.byState).toEqual({ EXCELLENT: 1, NORMAL: 2, UNSTABLE: 1, MAJOR_DISRUPTION: 1, UNKNOWN: 2 });
  });

  it("reports nothing judged for an empty network rather than a perfect one", () => {
    const counts = tally([]);

    expect(counts.judged).toBe(0);
    expect(counts.notJudged).toBe(0);
  });
});

describe("routes needing attention", () => {
  const routes = [
    summary({ routeId: "a", state: "MAJOR_DISRUPTION" }),
    summary({ routeId: "b", state: "UNSTABLE" }),
    summary({ routeId: "c", state: "NORMAL" }),
    summary({ routeId: "d", state: "UNKNOWN" }),
    summary({ routeId: "e", state: "UNSTABLE" }),
  ];

  it("are the unstable and the disrupted, in the order the API gave", () => {
    expect(needingAttention(routes, 10).map((route) => route.routeId)).toEqual(["a", "b", "e"]);
  });

  it("stop at the limit, keeping the worst because they come first", () => {
    expect(needingAttention(routes, 2).map((route) => route.routeId)).toEqual(["a", "b"]);
  });

  it("do not include a route that merely could not be judged", () => {
    expect(needingAttention([summary({ state: "UNKNOWN" })], 5)).toEqual([]);
  });
});
