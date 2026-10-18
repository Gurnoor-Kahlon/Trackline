import { describe, expect, it } from "vitest";

import { describeDirection, describeEvent, describeRole, layoutStrip } from "@/lib/bunching/strip";

import { bunch, bunched, event, spacing, vehicle } from "./bunchingHelpers";

const NOW = Date.parse("2026-10-12T14:00:30Z");

describe("laying out the strip", () => {
  it("places each vehicle at its share of the way along the route", () => {
    const { markers } = layoutStrip(spacing({ lengthMetres: 20_000, vehicles: [vehicle("a", 5000), vehicle("b", 15_000)] }));

    expect(markers.map((marker) => marker.fraction)).toEqual([0.25, 0.75]);
  });

  it("keeps a marker on the strip when a position runs fractionally past either end", () => {
    const { markers } = layoutStrip(spacing({ vehicles: [vehicle("early", -3), vehicle("late", 20_050)] }));

    expect(markers.map((marker) => marker.fraction)).toEqual([0, 1]);
  });

  it("does not divide by a route of no length", () => {
    const { markers } = layoutStrip(spacing({ lengthMetres: 0, vehicles: [vehicle("a", 100)] }));

    expect(markers[0]?.fraction).toBe(0);
  });

  it("carries each vehicles role through to its marker", () => {
    const { markers } = layoutStrip(bunched());

    expect(markers.map((marker) => marker.role)).toEqual([
      "AT_TERMINAL",
      "BUNCHED",
      "BUNCHED",
      "SPACED",
      "SPACED",
      "STALE",
    ]);
  });

  it("draws a bunch from its first member to its last", () => {
    const { bunches } = layoutStrip(bunched());

    expect(bunches).toHaveLength(1);
    expect(bunches[0]?.segments).toEqual([{ start: 0.05, end: 0.07 }]);
    expect(bunches[0]?.vehicles).toBe(2);
    expect(bunches[0]?.severity).toBe("MODERATE");
  });

  it("draws no bunch when there is none", () => {
    expect(layoutStrip(spacing()).bunches).toEqual([]);
  });

  it("ignores a bunch member the strip does not know of rather than failing", () => {
    const direction = spacing({ bunches: [bunch(["a", "ghost"])] });

    expect(layoutStrip(direction).bunches[0]?.segments).toEqual([{ start: 0.2, end: 0.2 }]);
  });

  it("draws a bunch across the start of a loop as two stretches meeting the edges", () => {
    // 100 m before the end and 100 m and 400 m after the start, on a 10 km loop.
    const direction = spacing({
      lengthMetres: 10_000,
      loop: true,
      vehicles: [vehicle("a", 9900, "BUNCHED"), vehicle("b", 100, "BUNCHED"), vehicle("c", 400, "BUNCHED"), vehicle("d", 6000)],
      bunches: [bunch(["a", "b", "c"], { spanMetres: 500 })],
    });

    const [only] = layoutStrip(direction).bunches;

    // Not one stretch crossing the whole picture.
    expect(only?.segments).toEqual([
      { start: 0, end: 0.04 },
      { start: 0.99, end: 1 },
    ]);
  });

  it("draws a bunch on a loop that does not cross the seam as one stretch", () => {
    const direction = spacing({
      lengthMetres: 10_000,
      loop: true,
      vehicles: [vehicle("a", 4000, "BUNCHED"), vehicle("b", 4300, "BUNCHED")],
      bunches: [bunch(["a", "b"], { spanMetres: 300 })],
    });

    expect(layoutStrip(direction).bunches[0]?.segments).toEqual([{ start: 0.4, end: 0.43 }]);
  });
});

describe("saying the same thing in words", () => {
  it("says how many vehicles were measured and that none are running together", () => {
    expect(describeDirection(spacing())).toBe(
      "3 vehicles measured, none running together while a gap opens elsewhere.",
    );
  });

  it("describes each group by how close they are and the gap they sit beside", () => {
    expect(describeDirection(bunched())).toContain(
      "2 running within 400 m of each other while the largest gap elsewhere is 6.5 km",
    );
  });

  it("says what was left out and why", () => {
    const words = describeDirection(bunched());

    expect(words).toContain("1 at a terminal and left out");
    expect(words).toContain("1 not reporting recently and left out");
    // Left-out vehicles are not counted as measured.
    expect(words).toContain("4 vehicles measured");
  });

  it("says plainly that too few vehicles can say nothing about bunching, instead of that there is none", () => {
    const words = describeDirection(spacing({ vehicles: [vehicle("a", 4000), vehicle("b", 8000)] }));

    expect(words).toBe("2 vehicles measured, too few to say whether any are bunched.");
    expect(words).not.toMatch(/none running together/);
  });

  it("is singular for one vehicle", () => {
    expect(describeDirection(spacing({ vehicles: [vehicle("a", 4000)] }))).toBe(
      "1 vehicle measured, too few to say whether any are bunched.",
    );
  });

  it("describes more than one group", () => {
    const direction = spacing({
      vehicles: [vehicle("a", 1000, "BUNCHED"), vehicle("b", 1400, "BUNCHED"), vehicle("c", 12_000, "BUNCHED"), vehicle("d", 12_300, "BUNCHED")],
      bunches: [bunch(["a", "b"]), bunch(["c", "d"], { spanMetres: 300 })],
    });

    const words = describeDirection(direction);

    expect(words).toContain("within 400 m");
    expect(words).toContain("within 300 m");
  });

  it("names every role", () => {
    expect(describeRole("SPACED")).toMatch(/in the spacing/);
    expect(describeRole("BUNCHED")).toMatch(/running together/);
    expect(describeRole("AT_TERMINAL")).toMatch(/terminal/);
    expect(describeRole("STALE")).toMatch(/not reporting/);
  });
});

describe("saying when a bunch was seen", () => {
  it("says first seen and still together for one that is open", () => {
    expect(describeEvent(event(), NOW)).toBe("First seen 10m ago · still together · seen on 12 checks");
  });

  it("says when it was last seen and that it has been apart since for one that has resolved", () => {
    const resolved = event({ status: "RESOLVED", lastSeenAt: "2026-10-12T13:58:00Z", resolvedAt: "2026-10-12T13:59:00Z", sightings: 1 });

    expect(describeEvent(resolved, NOW)).toBe(
      "First seen 10m ago · last seen 2m ago · apart since 1m ago · seen on 1 check",
    );
  });

  it("never says a bunch began or ended, which is not what was observed", () => {
    const words = describeEvent(event({ status: "RESOLVED", resolvedAt: "2026-10-12T14:00:20Z" }), NOW);

    expect(words).not.toMatch(/began|ended|started/i);
  });

  it("never reports a negative age when the browser clock runs behind", () => {
    expect(describeEvent(event(), Date.parse("2026-10-12T13:00:00Z"))).toContain("First seen 0s ago");
  });
});
