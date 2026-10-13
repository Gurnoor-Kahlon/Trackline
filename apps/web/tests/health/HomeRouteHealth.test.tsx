import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HomeRouteHealth } from "@/components/health/HomeRouteHealth";
import { fetchHealthRoutes } from "@/lib/api/health";
import { ApiError } from "@/lib/api/client";

import { listEnvelope, summary } from "./helpers";

vi.mock("@/lib/api/health", () => ({ fetchHealthRoutes: vi.fn(), fetchRouteHealth: vi.fn() }));

const fetchMock = vi.mocked(fetchHealthRoutes);

describe("the route health card on the home page", () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it("says how many routes could be judged, how many are uneven, and how many could not be judged", async () => {
    fetchMock.mockResolvedValue(
      listEnvelope([
        summary({ routeId: "1", state: "MAJOR_DISRUPTION" }),
        summary({ routeId: "2", state: "UNSTABLE" }),
        summary({ routeId: "3", state: "NORMAL" }),
        summary({ routeId: "4", state: "EXCELLENT" }),
        summary({ routeId: "5", state: "UNKNOWN" }),
        summary({ routeId: "6", state: "UNKNOWN" }),
        summary({ routeId: "7", state: "UNKNOWN" }),
      ]),
    );
    render(<HomeRouteHealth />);

    expect(
      await screen.findByText(/Of 4 routes with enough vehicles to judge, 2 have uneven spacing\. 3 more have too few\./),
    ).toBeInTheDocument();
  });

  it("says what it measures and what it cannot", async () => {
    fetchMock.mockResolvedValue(listEnvelope([summary()]));
    render(<HomeRouteHealth />);

    expect(await screen.findByText(/not on delay, which the TTC does not publish/)).toBeInTheDocument();
  });

  it("lists the routes with uneven spacing, worst first, with their verdict and what it rests on", async () => {
    fetchMock.mockResolvedValue(
      listEnvelope([
        summary({ routeId: "501", shortName: "501", longName: "Queen", state: "MAJOR_DISRUPTION", vehicles: 11, confidence: "HIGH" }),
        summary({ routeId: "504", shortName: "504", longName: "King", state: "UNSTABLE", vehicles: 6, confidence: "MEDIUM" }),
        summary({ routeId: "7", shortName: "7", longName: "Bathurst", state: "NORMAL" }),
      ]),
    );
    render(<HomeRouteHealth />);

    const items = await screen.findAllByRole("listitem");

    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Queen");
    expect(items[0]).toHaveTextContent("Major disruption");
    expect(items[0]).toHaveTextContent("High confidence · 11 vehicles");
    expect(items[1]).toHaveTextContent("King");
    expect(items[1]).toHaveTextContent("Unstable");
  });

  it("shows only the first few when many routes are uneven", async () => {
    fetchMock.mockResolvedValue(
      listEnvelope(Array.from({ length: 12 }, (_, index) => summary({ routeId: String(index), shortName: String(index) }))),
    );
    render(<HomeRouteHealth />);

    expect(await screen.findAllByRole("listitem")).toHaveLength(6);
    expect(screen.getByText(/12 have uneven spacing/)).toBeInTheDocument();
  });

  it("does not say the network is fine just because nothing was flagged: it says nothing is judged unstable", async () => {
    fetchMock.mockResolvedValue(listEnvelope([summary({ state: "NORMAL" }), summary({ state: "UNKNOWN" })]));
    render(<HomeRouteHealth />);

    expect(await screen.findByText("No route is currently judged unstable or disrupted.")).toBeInTheDocument();
    expect(screen.queryByText(/running well|all good/i)).not.toBeInTheDocument();
  });

  it("says plainly when no route has enough vehicles to be judged at all", async () => {
    fetchMock.mockResolvedValue(listEnvelope([summary({ state: "UNKNOWN" }), summary({ state: "UNKNOWN" })]));
    render(<HomeRouteHealth />);

    expect(await screen.findByText(/None of the 2 routes with vehicles has enough of them to judge/)).toBeInTheDocument();
    expect(screen.queryByText(/No route is currently judged/)).not.toBeInTheDocument();
  });

  it("says nothing has been calculated, which is not the same as every route being fine", async () => {
    fetchMock.mockResolvedValue(listEnvelope([], { source: "NO_DATA", feedTimestamp: null, ageSeconds: null }));
    render(<HomeRouteHealth />);

    expect(await screen.findByText("Route health has not been calculated yet")).toBeInTheDocument();
  });

  it("warns when the data behind it is old", async () => {
    fetchMock.mockResolvedValue(listEnvelope([summary()], { stale: true }));
    render(<HomeRouteHealth />);

    expect(await screen.findByText(/vehicle data behind this is old/)).toBeInTheDocument();
  });

  it("shows an error, not an empty card, when the request fails", async () => {
    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));
    render(<HomeRouteHealth />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Route health could not be loaded");
  });

  it("shows a loading state first", () => {
    fetchMock.mockReturnValue(new Promise(() => undefined));
    render(<HomeRouteHealth />);

    expect(screen.getByText("Loading route health")).toBeInTheDocument();
  });
});
