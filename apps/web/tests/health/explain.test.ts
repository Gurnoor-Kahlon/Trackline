import { describe, expect, it } from "vitest";

import {
  confidenceLabel,
  decisiveDirection,
  directionFigures,
  explainHealth,
  formatDistance,
} from "@/lib/health/explain";

import { direction, metrics, routeHealth, unknownRoute } from "./helpers";

describe("formatting a distance", () => {
  it("rounds short distances to ten metres, where more precision would be false", () => {
    expect(formatDistance(847)).toBe("850 m");
    expect(formatDistance(12)).toBe("10 m");
  });

  it("states longer ones in kilometres to a tenth", () => {
    expect(formatDistance(1000)).toBe("1.0 km");
    expect(formatDistance(2960)).toBe("3.0 km");
    expect(formatDistance(12_340)).toBe("12.3 km");
  });
});

describe("confidence", () => {
  it("is named, and says nothing when there is no verdict to be confident in", () => {
    expect(confidenceLabel("HIGH")).toBe("High confidence");
    expect(confidenceLabel("MEDIUM")).toBe("Medium confidence");
    expect(confidenceLabel("LOW")).toBe("Low confidence");
    expect(confidenceLabel("NONE")).toBeNull();
  });
});

describe("the direction that decided a verdict", () => {
  it("is the one whose state the route took", () => {
    const calm = direction({ directionId: 0, state: "EXCELLENT" });
    const bad = direction({ directionId: 1, state: "UNSTABLE", reasons: ["LARGE_GAP"] });

    const health = routeHealth({
      directions: [calm, bad],
      state: "UNSTABLE",
      confidence: bad.confidence,
      reasons: ["LARGE_GAP"],
    });

    expect(decisiveDirection(health)?.directionId).toBe(1);
  });

  it("is, between equals, the one whose reasons the route reports", () => {
    const first = direction({ directionId: 0, state: "UNSTABLE", reasons: ["LARGE_GAP"] });
    const second = direction({ directionId: 1, state: "UNSTABLE", reasons: ["HIGH_HEADWAY_VARIATION"] });

    const health = routeHealth({
      directions: [first, second],
      state: "UNSTABLE",
      confidence: "HIGH",
      reasons: ["HIGH_HEADWAY_VARIATION"],
    });

    expect(decisiveDirection(health)?.directionId).toBe(1);
  });

  it("is nothing when no direction has a verdict", () => {
    expect(decisiveDirection(unknownRoute("INSUFFICIENT_VEHICLES"))).toBeNull();
  });
});

describe("explaining a verdict", () => {
  it("composes the sentence from the figures, not from a template of adjectives", () => {
    const health = routeHealth({
      directions: [
        direction({
          state: "UNSTABLE",
          reasons: ["HIGH_HEADWAY_VARIATION", "LARGE_GAP"],
          metrics: metrics({ variation: 0.62, gapRatio: 2.1, expectedGapMetres: 3000 }),
        }),
      ],
    });

    expect(explainHealth(health).headline).toBe(
      "Spacing varies by 62%, and the longest gap is 2.1 times the expected spacing of 3.0 km.",
    );
  });

  it("measures the gap against the vehicles' own average when there is no timetable", () => {
    const health = routeHealth({
      directions: [
        direction({
          state: "NORMAL",
          reasons: ["NO_SCHEDULE_COVERAGE"],
          metrics: metrics({ basis: "OBSERVED", expectedGapMetres: null, gapRatio: 1.5, variation: 0.3 }),
        }),
      ],
    });

    expect(explainHealth(health).headline).toBe(
      "Spacing varies by 30%, and the longest gap is 1.5 times the average gap.",
    );
  });

  it("leaves out the variation when it could not be computed instead of stating zero", () => {
    const health = routeHealth({
      directions: [direction({ metrics: metrics({ variation: null, gapRatio: 1.2 }) })],
    });

    const { headline } = explainHealth(health);

    expect(headline).toBe("The longest gap is 1.2 times the expected spacing of 3.0 km.");
    expect(headline).not.toMatch(/0%/);
  });

  it("says how few of the scheduled vehicles are running when that is part of the reason", () => {
    const health = routeHealth({
      directions: [
        direction({
          state: "MAJOR_DISRUPTION",
          reasons: ["FEWER_VEHICLES_THAN_SCHEDULED"],
          metrics: metrics({ variation: 0.1, gapRatio: 1.1, supplyRatio: 0.39 }),
        }),
      ],
    });

    expect(explainHealth(health).headline).toContain("only 39% of the vehicles the timetable calls for are running");
  });

  it("says one group of vehicles is running together when there is one", () => {
    const health = routeHealth({
      directions: [direction({ state: "UNSTABLE", reasons: ["BUNCHING"], metrics: metrics({ bunchingClusters: 1 }) })],
    });

    expect(explainHealth(health).headline).toContain("one group of vehicles is running together");
  });

  it("counts the groups when there are several", () => {
    const health = routeHealth({
      directions: [direction({ state: "UNSTABLE", reasons: ["BUNCHING"], metrics: metrics({ bunchingClusters: 3 }) })],
    });

    expect(explainHealth(health).headline).toContain("3 groups of vehicles are running together");
  });

  it("does not mention a group when bunching is not part of the reason", () => {
    expect(explainHealth(routeHealth()).headline).not.toMatch(/running together/);
  });

  it("does not mention supply when it is not part of the reason", () => {
    expect(explainHealth(routeHealth()).headline).not.toMatch(/timetable calls for/);
  });

  it("says plainly when a direction was measured but no figure could be stated", () => {
    const health = routeHealth({
      directions: [direction({ metrics: metrics({ variation: null, gapRatio: null }) })],
    });

    expect(explainHealth(health).headline).toMatch(/too few vehicles/);
  });

  it("names the direction it describes", () => {
    const health = routeHealth({ directions: [direction({ directionId: 1 })] });

    expect(explainHealth(health).directionId).toBe(1);
  });

  describe("caveats", () => {
    it("say a verdict rests on two vehicles", () => {
      const health = routeHealth({
        directions: [direction({ confidence: "LOW", reasons: ["FEW_VEHICLES"], metrics: metrics({ vehicles: 2 }) })],
      });

      expect(explainHealth(health).caveats).toContain("Based on only 2 vehicles.");
    });

    it("say the timetable does not cover today, and what that costs", () => {
      const health = routeHealth({ directions: [direction({ reasons: ["NO_SCHEDULE_COVERAGE"] })] });

      expect(explainHealth(health).caveats[0]).toMatch(/does not cover today/);
      expect(explainHealth(health).caveats[0]).toMatch(/every gap is too wide cannot be seen/);
    });

    it("say there is no timetable spacing for the hour", () => {
      const health = routeHealth({ directions: [direction({ reasons: ["NO_SCHEDULED_HEADWAY"] })] });

      expect(explainHealth(health).caveats[0]).toMatch(/no spacing for this route at this hour/);
    });

    it("say speed is still being measured, which is the state for the first minutes after a restart", () => {
      const health = routeHealth({ directions: [direction({ reasons: ["SPEED_UNKNOWN"] })] });

      expect(explainHealth(health).caveats[0]).toMatch(/still being measured/);
    });

    it("say many vehicles were left out for not reporting", () => {
      const health = routeHealth({ directions: [direction({ confidence: "LOW", reasons: ["STALE_VEHICLES"] })] });

      expect(explainHealth(health).caveats[0]).toMatch(/not reported recently/);
    });

    it("count vehicles that could not be placed, with the right grammar", () => {
      const one = routeHealth({ unplacedVehicles: 1 });
      const several = routeHealth({ unplacedVehicles: 3 });

      expect(explainHealth(one).caveats).toContain(
        "1 vehicle on this route could not be placed in a direction and is not counted.",
      );
      expect(explainHealth(several).caveats).toContain(
        "3 vehicles on this route could not be placed in a direction and are not counted.",
      );
    });

    it("are absent when nothing weakens the verdict", () => {
      expect(explainHealth(routeHealth()).caveats).toEqual([]);
    });

    it("keep a stable order whatever order the reasons came in", () => {
      const health = routeHealth({
        directions: [direction({ reasons: ["SPEED_UNKNOWN", "FEW_VEHICLES", "NO_SCHEDULE_COVERAGE"] })],
      });

      const caveats = explainHealth(health).caveats;

      expect(caveats[0]).toMatch(/^Based on/);
      expect(caveats[1]).toMatch(/does not cover today/);
      expect(caveats[2]).toMatch(/still being measured/);
    });
  });

  describe("when there is no verdict", () => {
    it("says a subway line has no positions published", () => {
      expect(explainHealth(unknownRoute("NO_REALTIME_FEED")).headline).toMatch(/publishes no vehicle positions/);
    });

    it("says fewer than two vehicles are running", () => {
      expect(explainHealth(unknownRoute("INSUFFICIENT_VEHICLES")).headline).toMatch(/Fewer than two vehicles/);
    });

    it("says nothing has been analysed yet, which is not the same as being fine", () => {
      expect(explainHealth(unknownRoute("NOT_ANALYSED_YET")).headline).toBe("Route health has not been calculated yet.");
    });

    it("falls back to a general statement for a reason it has no sentence for", () => {
      expect(explainHealth(unknownRoute("FEW_VEHICLES")).headline).toBe(
        "There is not enough live data to judge this route.",
      );
    });

    it("still reports vehicles that could not be placed", () => {
      const health = unknownRoute("INSUFFICIENT_VEHICLES", { unplacedVehicles: 2 });

      expect(explainHealth(health).caveats).toHaveLength(1);
    });

    it("describes no direction", () => {
      expect(explainHealth(unknownRoute("INSUFFICIENT_VEHICLES")).directionId).toBeNull();
    });
  });
});

describe("the figures behind a direction", () => {
  const byLabel = (rows: ReturnType<typeof directionFigures>) =>
    Object.fromEntries(rows.map((row) => [row.label, row.value]));

  it("states each one in units a rider can read", () => {
    const figures = byLabel(directionFigures(metrics()));

    expect(figures["Vehicles measured"]).toBe("5");
    expect(figures["Average gap"]).toBe("3.0 km");
    expect(figures["Longest gap"]).toBe("4.2 km");
    expect(figures["Spacing varies by"]).toBe("31%");
    expect(figures["Expected spacing (timetable)"]).toBe("3.0 km");
    expect(figures["Vehicles expected"]).toBe("6");
    expect(figures["Timetable spacing"]).toBe("10 min");
    // 5 m/s is 18 km/h.
    expect(figures["Measured speed"]).toBe("18.0 km/h");
  });

  it("says a figure was not measured rather than showing zero", () => {
    const figures = byLabel(
      directionFigures(
        metrics({
          variation: null,
          expectedGapMetres: null,
          expectedVehicles: null,
          scheduledHeadwaySeconds: null,
          speedMetresPerSecond: null,
          basis: "OBSERVED",
        }),
      ),
    );

    expect(figures["Spacing varies by"]).toBe("Not measured");
    expect(figures["Expected spacing"]).toBe("No timetable comparison");
    expect(figures["Vehicles expected"]).toBe("Not measured");
    expect(figures["Timetable spacing"]).toBe("Not measured");
    expect(figures["Measured speed"]).toBe("Not measured");
    expect(Object.values(figures)).not.toContain("0%");
  });

  it("always states how many groups were found, including none", () => {
    expect(byLabel(directionFigures(metrics()))["Groups running together"]).toBe("0");
    expect(byLabel(directionFigures(metrics({ bunchingClusters: 2 })))["Groups running together"]).toBe("2");
  });

  it("lists vehicles left out only when there were some", () => {
    expect(directionFigures(metrics()).map((row) => row.label)).not.toContain("Left out: not reporting");

    const rows = byLabel(directionFigures(metrics({ staleVehicles: 2, terminalVehicles: 1 })));
    expect(rows["Left out: not reporting"]).toBe("2");
    expect(rows["Left out: at a terminal"]).toBe("1");
  });
});
