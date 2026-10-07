import { describe, expect, it } from "vitest";

import type { RouteSummary, TransitMode } from "@/lib/api/types";
import { EMPTY_FILTER, filterRoutes, sortRoutes, toggleMode } from "@/lib/map/routeFilter";

function route(shortName: string, longName: string, mode: TransitMode): RouteSummary {
  return {
    routeId: shortName,
    shortName,
    longName,
    mode,
    colour: null,
    textColour: null,
    supportsRealtime: mode !== "SUBWAY",
  };
}

// Shaped like the real network: three-digit expresses, a numbered streetcar and
// a subway line whose short name is not a number at all.
const ROUTES: RouteSummary[] = [
  route("501", "Queen", "STREETCAR"),
  route("41", "Keele", "BUS"),
  route("1", "Line 1 Yonge-University", "SUBWAY"),
  route("100", "Flemingdon Park", "BUS"),
  route("TTC", "Night Network", "OTHER"),
];

describe("filterRoutes", () => {
  it("includes every mode when no mode is selected", () => {
    expect(filterRoutes(ROUTES, EMPTY_FILTER)).toHaveLength(ROUTES.length);
  });

  it("matches a route number", () => {
    const result = filterRoutes(ROUTES, { ...EMPTY_FILTER, query: "501" });

    expect(result.map((item) => item.shortName)).toEqual(["501"]);
  });

  it("matches a route name regardless of case", () => {
    const result = filterRoutes(ROUTES, { ...EMPTY_FILTER, query: "queen" });

    expect(result.map((item) => item.shortName)).toEqual(["501"]);
  });

  it("ignores surrounding whitespace in the query", () => {
    const result = filterRoutes(ROUTES, { ...EMPTY_FILTER, query: "  keele  " });

    expect(result.map((item) => item.shortName)).toEqual(["41"]);
  });

  it("restricts to the selected modes", () => {
    const result = filterRoutes(ROUTES, { modes: new Set<TransitMode>(["BUS"]), query: "" });

    expect(result.map((item) => item.shortName)).toEqual(["41", "100"]);
  });

  it("applies mode and query together", () => {
    const result = filterRoutes(ROUTES, { modes: new Set<TransitMode>(["BUS"]), query: "1" });

    // 501 contains "1" but is a streetcar, so the mode must exclude it.
    expect(result.map((item) => item.shortName)).toEqual(["41", "100"]);
  });

  it("returns nothing when the query matches nothing", () => {
    expect(filterRoutes(ROUTES, { ...EMPTY_FILTER, query: "zzz" })).toEqual([]);
  });
});

describe("sortRoutes", () => {
  it("orders numbered routes numerically rather than as text", () => {
    const result = sortRoutes(ROUTES).map((item) => item.shortName);

    // The failure this guards against is 1, 100, 41, 501 — what plain string
    // ordering gives, and what GTFS ids invite because they are text.
    expect(result).toEqual(["1", "41", "100", "501", "TTC"]);
  });

  it("does not mutate the array it was given", () => {
    const original = [...ROUTES];
    sortRoutes(ROUTES);

    expect(ROUTES).toEqual(original);
  });
});

describe("toggleMode", () => {
  it("adds a mode that was not selected", () => {
    const result = toggleMode(EMPTY_FILTER, "BUS");

    expect([...result.modes]).toEqual(["BUS"]);
  });

  it("removes a mode that was selected", () => {
    const result = toggleMode({ modes: new Set<TransitMode>(["BUS"]), query: "" }, "BUS");

    expect(result.modes.size).toBe(0);
  });

  it("returns a new set so React sees the change", () => {
    const before: Set<TransitMode> = new Set(["BUS"]);
    const result = toggleMode({ modes: before, query: "x" }, "SUBWAY");

    expect(result.modes).not.toBe(before);
    expect(before.size).toBe(1);
    expect(result.query).toBe("x");
  });
});
