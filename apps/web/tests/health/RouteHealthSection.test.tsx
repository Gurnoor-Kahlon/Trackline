import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { RouteHealthSection } from "@/components/health/RouteHealthSection";

import { direction, metrics, ready, routeHealth, SERVER_NOW, unknownRoute } from "./helpers";

const HEADSIGNS: Record<number, string> = { 0: "East - 501 Queen towards Neville Park", 1: "West - 501 Queen towards Long Branch" };
const headsign = (id: number) => HEADSIGNS[id] ?? null;

describe("a route's service health", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(SERVER_NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("says it is measuring while it waits", () => {
    render(<RouteHealthSection result={{ status: "loading" }} headsign={headsign} />);

    expect(screen.getByText("Measuring…")).toBeInTheDocument();
  });

  it("says it failed rather than showing a verdict it does not have", () => {
    render(
      <RouteHealthSection result={{ status: "error", message: "down", errorId: undefined }} headsign={headsign} />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Service health could not be loaded.");
  });

  it("shows the verdict, how far to trust it, and why in words from the figures", () => {
    const health = routeHealth({
      directions: [
        direction({
          state: "UNSTABLE",
          confidence: "MEDIUM",
          reasons: ["HIGH_HEADWAY_VARIATION"],
          metrics: metrics({ variation: 0.62, gapRatio: 1.4 }),
        }),
      ],
    });

    render(<RouteHealthSection result={ready(health)} headsign={headsign} />);

    // The verdict, and again for the direction in the collapsed details.
    expect(screen.getAllByText("Unstable")[0]).toBeInTheDocument();
    expect(screen.getByText("Medium confidence")).toBeInTheDocument();
    expect(screen.getByText(/Spacing varies by 62%/)).toBeInTheDocument();
  });

  it("does not repeat the explanation to a screen reader inside the status pill", () => {
    render(<RouteHealthSection result={ready(routeHealth())} headsign={headsign} />);

    // The sentence, once. The figures list has a label of the same words but no number after them.
    expect(screen.getAllByText(/Spacing varies by \d+%/)).toHaveLength(1);
  });

  it("shows a caveat beside a verdict that rests on little", () => {
    const health = routeHealth({
      directions: [
        direction({ confidence: "LOW", reasons: ["FEW_VEHICLES"], metrics: metrics({ vehicles: 2 }) }),
      ],
    });

    render(<RouteHealthSection result={ready(health)} headsign={headsign} />);

    expect(screen.getByText("Low confidence")).toBeInTheDocument();
    expect(screen.getByText("Based on only 2 vehicles.")).toBeInTheDocument();
  });

  it("says why there is no verdict, and shows no confidence for it", () => {
    render(<RouteHealthSection result={ready(unknownRoute("NO_REALTIME_FEED"))} headsign={headsign} />);

    expect(screen.getByText("Not measured")).toBeInTheDocument();
    expect(screen.getByText(/publishes no vehicle positions/)).toBeInTheDocument();
    expect(screen.queryByText(/confidence/)).not.toBeInTheDocument();
    expect(screen.queryByText("How this was measured")).not.toBeInTheDocument();
  });

  it("says how old the vehicle data behind it is", () => {
    render(<RouteHealthSection result={ready(routeHealth())} headsign={headsign} />);

    expect(screen.getByText("Vehicle data 30s old.")).toBeInTheDocument();
  });

  it("warns when the vehicle data is old, instead of presenting the verdict as current", () => {
    render(
      <RouteHealthSection
        result={ready(routeHealth(), { meta: { stale: true, feedTimestamp: "2026-10-12T13:55:00Z" } })}
        headsign={headsign}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(/5m old, so it may not describe the route now/);
  });

  it("flags a verdict from an earlier fetch when the latest refresh failed", () => {
    render(<RouteHealthSection result={ready(routeHealth(), { refreshFailed: true })} headsign={headsign} />);

    expect(screen.getByText(/latest refresh failed/)).toBeInTheDocument();
  });

  describe("how it was measured", () => {
    it("lists each direction with its destination and every figure", async () => {
      const user = userEvent.setup();
      const health = routeHealth({
        directions: [
          direction({ directionId: 0, state: "NORMAL" }),
          direction({ directionId: 1, state: "UNSTABLE", metrics: metrics({ vehicles: 3, variation: null }) }),
        ],
        state: "UNSTABLE",
      });
      render(<RouteHealthSection result={ready(health)} headsign={headsign} />);

      await user.click(screen.getByText("How this was measured"));

      expect(screen.getByText("East - 501 Queen towards Neville Park")).toBeInTheDocument();
      expect(screen.getByText("West - 501 Queen towards Long Branch")).toBeInTheDocument();
      expect(screen.getAllByText("Vehicles measured")).toHaveLength(2);
    });

    it("says a figure was not measured instead of showing zero", async () => {
      const user = userEvent.setup();
      const health = routeHealth({ directions: [direction({ metrics: metrics({ variation: null }) })] });
      render(<RouteHealthSection result={ready(health)} headsign={headsign} />);

      await user.click(screen.getByText("How this was measured"));

      const row = screen.getByText("Spacing varies by").nextElementSibling as HTMLElement;
      expect(within(row.parentElement as HTMLElement).getByText("Not measured")).toBeInTheDocument();
    });

    it("falls back to the direction number when the route names no destination", async () => {
      const user = userEvent.setup();
      render(<RouteHealthSection result={ready(routeHealth())} headsign={() => null} />);

      await user.click(screen.getByText("How this was measured"));

      expect(screen.getByText("Direction 0")).toBeInTheDocument();
    });
  });
});
