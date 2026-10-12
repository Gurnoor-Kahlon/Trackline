import { describe, expect, it } from "vitest";

import {
  alertText,
  describeObserved,
  filterAlerts,
  groupByEffect,
  hiddenRouteCount,
  isFiltering,
  NO_FILTER,
  ROUTES_SHOWN,
} from "@/lib/alerts/display";

import { alert, route, SERVER_NOW, unknownRoute } from "./helpers";

describe("what an alert says", () => {
  it("is the description, because the publisher cuts the header off mid-word", () => {
    const published = alert({ header: "Have proof of payment ready for", description: "Have proof of payment ready for inspection." });

    expect(alertText(published)).toBe("Have proof of payment ready for inspection.");
  });

  it("falls back to the header for an alert with no description", () => {
    expect(alertText(alert({ description: null, header: "Short notice" }))).toBe("Short notice");
  });

  it("says there is no text rather than showing nothing", () => {
    expect(alertText(alert({ description: null, header: null }))).toMatch(/no text/i);
  });
});

describe("grouping by effect", () => {
  it("puts alerts that state no effect in one group, as the TTC currently publishes them", () => {
    const groups = groupByEffect([alert({ effect: "UNKNOWN_EFFECT" }), alert({ effect: "UNKNOWN_EFFECT" })]);

    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ effect: null, label: "Effect not stated" });
    expect(groups[0]?.alerts).toHaveLength(2);
  });

  it("treats an absent effect like an unknown one", () => {
    const groups = groupByEffect([alert({ effect: null }), alert({ effect: "UNKNOWN_EFFECT" })]);

    expect(groups).toHaveLength(1);
  });

  it("orders groups by what a rider most needs to know, with the unstated last", () => {
    const groups = groupByEffect([
      alert({ effect: "UNKNOWN_EFFECT" }),
      alert({ effect: "DETOUR" }),
      alert({ effect: "NO_SERVICE" }),
      alert({ effect: "ACCESSIBILITY_ISSUE" }),
    ]);

    expect(groups.map((group) => group.label)).toEqual([
      "No service",
      "Detour",
      "Accessibility",
      "Effect not stated",
    ]);
  });

  it("keeps the order the API gave within a group", () => {
    const first = alert({ id: 10, effect: "DETOUR" });
    const second = alert({ id: 11, effect: "DETOUR" });

    expect(groupByEffect([first, second])[0]?.alerts.map((item) => item.id)).toEqual([10, 11]);
  });

  it("makes no group for an effect that no alert has", () => {
    expect(groupByEffect([alert({ effect: "DETOUR" })]).map((group) => group.effect)).toEqual(["DETOUR"]);
  });

  it("is empty for no alerts", () => {
    expect(groupByEffect([])).toEqual([]);
  });
});

describe("filtering", () => {
  const streetcar = alert({ id: 1, routes: [route("501", { mode: "STREETCAR", longName: "Queen" })] });
  const bus = alert({ id: 2, routes: [route("7", { mode: "BUS", longName: "Bathurst" })] });
  const subway = alert({ id: 3, routes: [route("1", { mode: "SUBWAY", longName: "Yonge-University" })] });
  const several = alert({ id: 4, routes: [route("501", { mode: "STREETCAR" }), route("7", { mode: "BUS" })] });
  const orphan = alert({ id: 5, routes: [unknownRoute("ZZ-999")] });
  const nothing = alert({ id: 6, routes: [] });
  const all = [streetcar, bus, subway, several, orphan, nothing];

  const ids = (found: readonly { id: number }[]) => found.map((item) => item.id);

  it("keeps everything when there is no filter", () => {
    expect(filterAlerts(all, NO_FILTER)).toEqual(all);
  });

  it("keeps alerts naming a route of the chosen mode, including ones that also name others", () => {
    expect(ids(filterAlerts(all, { mode: "STREETCAR", routeQuery: "" }))).toEqual([1, 4]);
    expect(ids(filterAlerts(all, { mode: "BUS", routeQuery: "" }))).toEqual([2, 4]);
  });

  it("never matches a route the schedule does not know to a mode, since it has none", () => {
    expect(ids(filterAlerts(all, { mode: "BUS", routeQuery: "" }))).not.toContain(5);
  });

  it("matches a route by number or by name, ignoring case", () => {
    expect(ids(filterAlerts(all, { mode: null, routeQuery: "501" }))).toEqual([1, 4]);
    expect(ids(filterAlerts(all, { mode: null, routeQuery: "queen" }))).toEqual([1]);
    expect(ids(filterAlerts(all, { mode: null, routeQuery: "  YONGE " }))).toEqual([3]);
  });

  it("can still find an alert by the id of a route the schedule lacks", () => {
    expect(ids(filterAlerts(all, { mode: null, routeQuery: "zz-999" }))).toEqual([5]);
  });

  it("combines the mode and the text", () => {
    expect(ids(filterAlerts(all, { mode: "BUS", routeQuery: "bathurst" }))).toEqual([2]);
    expect(filterAlerts(all, { mode: "SUBWAY", routeQuery: "queen" })).toEqual([]);
  });

  it("drops an alert that names no route as soon as there is a route filter", () => {
    expect(ids(filterAlerts(all, { mode: null, routeQuery: "5" }))).not.toContain(6);
  });

  it("knows whether a filter is doing anything", () => {
    expect(isFiltering(NO_FILTER)).toBe(false);
    expect(isFiltering({ mode: null, routeQuery: "   " })).toBe(false);
    expect(isFiltering({ mode: "BUS", routeQuery: "" })).toBe(true);
    expect(isFiltering({ mode: null, routeQuery: "7" })).toBe(true);
  });
});

describe("when an alert was seen", () => {
  it("says first seen and last seen, which is all that is known", () => {
    const text = describeObserved(alert(), SERVER_NOW);

    expect(text).toBe("First seen 2h ago · last seen 20s ago");
  });

  it("uses the server's clock for the age, so it moves with the time it is given", () => {
    expect(describeObserved(alert(), SERVER_NOW + 60_000)).toBe("First seen 2h 1m ago · last seen 1m ago");
  });

  it("says an ended alert is gone from the feed and not that it ended", () => {
    const ended = alert({ status: "RESOLVED", resolvedAt: "2026-10-12T11:30:00Z", lastSeenAt: "2026-10-12T11:29:00Z" });

    const text = describeObserved(ended, SERVER_NOW);

    expect(text).toBe("First seen 2h ago · gone from the feed 30m ago");
    expect(text).not.toMatch(/ended|began|started/i);
  });

  it("never reports a negative age when the browser clock runs behind", () => {
    expect(describeObserved(alert(), Date.parse(FIRST)).startsWith("First seen 0s ago")).toBe(true);
  });
});

const FIRST = "2026-10-12T09:00:00Z";

describe("long route lists", () => {
  it("shows a limited number and counts the rest", () => {
    const many = alert({ routes: Array.from({ length: 33 }, (_, index) => route(String(index + 1))) });

    expect(hiddenRouteCount(many)).toBe(33 - ROUTES_SHOWN);
  });

  it("hides nothing when the list is short", () => {
    expect(hiddenRouteCount(alert({ routes: [route("1"), route("2")] }))).toBe(0);
    expect(hiddenRouteCount(alert({ routes: [] }))).toBe(0);
  });
});
