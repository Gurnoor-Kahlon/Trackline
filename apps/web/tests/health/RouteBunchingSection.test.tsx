import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { RouteBunchingSection } from "@/components/health/RouteBunchingSection";

import { bunched, bunchingReady, event, spacing } from "./bunchingHelpers";
import { SERVER_NOW } from "./helpers";

const HEADSIGNS: Record<number, string> = { 0: "East - 501 Queen towards Neville Park" };
const headsign = (id: number) => HEADSIGNS[id] ?? null;

describe("the spacing section of a route's panel", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(SERVER_NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("says it is measuring while it waits", () => {
    render(<RouteBunchingSection result={{ status: "loading" }} headsign={headsign} />);

    expect(screen.getByText("Measuring…")).toBeInTheDocument();
  });

  it("says it failed rather than showing nothing", () => {
    render(
      <RouteBunchingSection result={{ status: "error", message: "down", errorId: undefined }} headsign={headsign} />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Spacing could not be loaded.");
  });

  it("shows a strip for each direction under its destination", () => {
    render(
      <RouteBunchingSection
        result={bunchingReady([bunched(), spacing({ directionId: 1 })])}
        headsign={headsign}
      />,
    );

    expect(screen.getByText("East - 501 Queen towards Neville Park")).toBeInTheDocument();
    expect(screen.getByText("Direction 1")).toBeInTheDocument();
    expect(screen.getAllByRole("img")).toHaveLength(2);
  });

  it("says no vehicles are placed when there are none, which is not the same as a route with no bunching", () => {
    render(<RouteBunchingSection result={bunchingReady([])} headsign={headsign} />);

    expect(screen.getByText("No vehicles are placed on this route right now.")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("says spacing has not been calculated when no analysis has run", () => {
    render(
      <RouteBunchingSection
        result={bunchingReady([], [], { meta: { source: "NO_DATA", feedTimestamp: null, ageSeconds: null } })}
        headsign={headsign}
      />,
    );

    expect(screen.getByText("Spacing has not been calculated yet.")).toBeInTheDocument();
  });

  it("warns when the vehicle data behind it is old", () => {
    render(
      <RouteBunchingSection
        result={bunchingReady([bunched()], [], { meta: { stale: true, feedTimestamp: "2026-10-12T13:55:00Z" } })}
        headsign={headsign}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(/5m old, so it may not describe the route now/);
  });

  it("flags what it shows as from an earlier fetch when the latest refresh failed", () => {
    render(<RouteBunchingSection result={bunchingReady([bunched()], [], { refreshFailed: true })} headsign={headsign} />);

    expect(screen.getByText(/latest refresh failed/)).toBeInTheDocument();
  });

  describe("the groups seen in the last 24 hours", () => {
    it("says none were recorded, and that this covers only the time Trackline has been watching", () => {
      render(<RouteBunchingSection result={bunchingReady([bunched()])} headsign={headsign} />);

      expect(screen.getByText("None recorded, in the time Trackline has been watching.")).toBeInTheDocument();
    });

    it("lists an open one as happening now, with what it was and what is elsewhere", () => {
      render(<RouteBunchingSection result={bunchingReady([bunched()], [event()])} headsign={headsign} />);

      const item = screen.getByText(/^Now: /).closest("li") as HTMLElement;
      expect(item).toHaveTextContent("East - 501 Queen towards Neville Park: 2 vehicles, moderate, with a gap of 6.5 km elsewhere.");
      expect(item).toHaveTextContent("First seen 10m ago · still together · seen on 12 checks");
    });

    it("says how many vehicles it held at most when that differs from now", () => {
      render(
        <RouteBunchingSection
          result={bunchingReady([bunched()], [event({ peakVehicles: 4, peakSeverity: "SEVERE" })])}
          headsign={headsign}
        />,
      );

      expect(screen.getByText(/2 vehicles, 4 at most, severe/)).toBeInTheDocument();
    });

    it("lists a resolved one without calling it now, and says it has been apart since", () => {
      render(
        <RouteBunchingSection
          result={bunchingReady([bunched()], [event({ status: "RESOLVED", resolvedAt: "2026-10-12T14:00:00Z", lastSeenAt: "2026-10-12T13:59:00Z" })])}
          headsign={headsign}
        />,
      );

      expect(screen.queryByText(/^Now: /)).not.toBeInTheDocument();
      expect(screen.getByText(/apart since 30s ago/)).toBeInTheDocument();
    });

    it("shows only the first few and counts the rest", () => {
      const many = Array.from({ length: 8 }, (_, index) => event({ id: index + 1 }));
      render(<RouteBunchingSection result={bunchingReady([bunched()], many)} headsign={headsign} />);

      expect(screen.getAllByText(/First seen/)).toHaveLength(5);
      expect(screen.getByText("and 3 more.")).toBeInTheDocument();
    });

    it("is still shown for a route with no vehicles right now", () => {
      render(<RouteBunchingSection result={bunchingReady([], [event({ status: "RESOLVED", resolvedAt: "2026-10-12T14:00:00Z" })])} headsign={headsign} />);

      expect(screen.getByText("No vehicles are placed on this route right now.")).toBeInTheDocument();
      expect(screen.getByText(/First seen/)).toBeInTheDocument();
    });
  });
});
