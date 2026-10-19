import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { MapExplorer } from "@/components/map/MapExplorer";
import { ApiError } from "@/lib/api/client";
import { fetchRoute, fetchRouteShape, fetchRouteStops } from "@/lib/api/routes";
import type { RouteDetail, RouteShape, RouteStop, RouteSummary } from "@/lib/api/types";
import { LiveStore } from "@/lib/live/liveStore";
import { useAlerts, type AlertsResult } from "@/lib/alerts/useAlerts";
import { useRouteBunching, useRouteHealth, useRouteReliability } from "@/lib/health/useHealth";
import { useLiveVehicles } from "@/lib/live/useLiveVehicles";

import { snapshot as liveSnapshot, vehicle as liveVehicle } from "./live/helpers";

vi.mock("@/lib/api/routes", () => ({
  fetchRoute: vi.fn(),
  fetchRouteShape: vi.fn(),
  fetchRouteStops: vi.fn(),
}));

/**
 * The streams are replaced too: jsdom has no EventSource, and what matters here is which
 * subscription the page asks for, not the transport. The hook is a spy returning a store
 * the test controls, so the page can be shown a fleet without a network.
 */
vi.mock("@/lib/live/useLiveVehicles", () => ({ useLiveVehicles: vi.fn() }));
vi.mock("@/lib/live/useNetworkStatus", () => ({
  useNetworkStatus: () => ({ summary: null, connection: "open" }),
}));

/** Alerts are fetched by their own hook, which is replaced so the page can be shown any answer. */
vi.mock("@/lib/alerts/useAlerts", () => ({ useAlerts: vi.fn() }));

/** Health likewise: the page is shown any answer, and what it asks for is what is asserted. */
vi.mock("@/lib/health/useHealth", () => ({
  useRouteHealth: vi.fn(),
  useRouteBunching: vi.fn(),
  useRouteReliability: vi.fn(),
}));

/**
 * The map itself is replaced by a component that prints what it was handed.
 *
 * MapLibre needs a WebGL context that jsdom does not have, and rendering it here
 * would test the library rather than Trackline. What matters is that the right
 * geometry and selection reach it, which this makes assertable.
 */
vi.mock("@/components/map/MapCanvas", () => ({
  MapCanvas: ({
    shape,
    stops,
    routeColour,
    selectedStopId,
    vehicles,
  }: {
    shape: RouteShape | null;
    stops: RouteStop[];
    routeColour: string | null;
    selectedStopId: string | null;
    onStopSelect: (stopId: string) => void;
    vehicles?: { routeColours: ReadonlyMap<string, string> };
  }) => (
    <div data-testid="map">
      <span data-testid="map-shape">{shape === null ? "none" : shape.shapeId}</span>
      <span data-testid="map-stops">{stops.length}</span>
      <span data-testid="map-colour">{routeColour ?? "none"}</span>
      <span data-testid="map-stop-selection">{selectedStopId ?? "none"}</span>
      <span data-testid="map-live-colour-501">{vehicles?.routeColours.get("501") ?? "none"}</span>
      <span data-testid="map-live-colour-unlisted">{vehicles?.routeColours.get("no-such-route") ?? "none"}</span>
    </div>
  ),
}));

const ROUTES: RouteSummary[] = [
  {
    routeId: "501",
    shortName: "501",
    longName: "Queen",
    mode: "STREETCAR",
    colour: "DA251D",
    textColour: "FFFFFF",
    supportsRealtime: true,
  },
  {
    routeId: "900",
    shortName: "900",
    longName: "Airport Express",
    mode: "BUS",
    colour: "0071CE",
    textColour: "FFFFFF",
    supportsRealtime: true,
  },
];

function detail(routeId: string, directionIds: number[]): RouteDetail {
  const summary = ROUTES.find((route) => route.routeId === routeId)!;
  return {
    ...summary,
    directions: directionIds.map((directionId) => ({
      directionId,
      headsign: directionId === 0 ? "Eastbound" : "Westbound",
      tripCount: 100,
      stopCount: 2,
      lengthMetres: 12000,
      loop: false,
    })),
  };
}

function shape(directionId: number): RouteShape {
  return {
    routeId: "501",
    directionId,
    shapeId: `shape-${directionId}`,
    lengthMetres: 12000,
    simplifiedToMetres: 15,
    geometry: {
      type: "LineString",
      coordinates: [
        [-79.4, 43.64],
        [-79.3, 43.66],
      ],
    },
  };
}

function stop(stopId: string, name: string, sequence: number): RouteStop {
  return {
    stopId,
    name,
    code: stopId,
    latitude: 43.65,
    longitude: -79.38,
    sequence,
    distanceAlongRouteMetres: null,
    wheelchairBoarding: 1,
    stationId: null,
    stationName: null,
  };
}

const STOPS = [stop("1001", "Woodbine Ave", 1), stop("1002", "Coxwell Ave", 2)];

/** The panels are rendered twice, for the desktop rail and the mobile sheet. */
function filters() {
  return within(screen.getByRole("complementary", { name: "Route filters" }));
}

function detailPanel() {
  return within(screen.getByRole("complementary", { name: "Selected route" }));
}

let liveStore: LiveStore;

beforeEach(() => {
  liveStore = new LiveStore();
  vi.mocked(useLiveVehicles).mockReset();
  vi.mocked(useLiveVehicles).mockReturnValue(liveStore);
  vi.mocked(fetchRoute).mockReset();
  vi.mocked(fetchRouteShape).mockReset();
  vi.mocked(fetchRouteStops).mockReset();
  vi.mocked(useAlerts).mockReset();
  vi.mocked(useAlerts).mockReturnValue({ status: "loading" });
  vi.mocked(useRouteHealth).mockReset();
  vi.mocked(useRouteHealth).mockReturnValue({ status: "loading" });
  vi.mocked(useRouteBunching).mockReset();
  vi.mocked(useRouteBunching).mockReturnValue({ status: "loading" });
  vi.mocked(useRouteReliability).mockReset();
  vi.mocked(useRouteReliability).mockReturnValue({ status: "loading" });
});

describe("MapExplorer", () => {
  it("starts with no route drawn and says so", async () => {
    render(<MapExplorer routes={ROUTES} />);

    expect(await screen.findByTestId("map")).toBeInTheDocument();
    expect(screen.getByTestId("map-shape")).toHaveTextContent("none");
    expect(detailPanel().getByText("No route selected")).toBeInTheDocument();
    expect(fetchRoute).not.toHaveBeenCalled();
  });

  it("draws the route and lists its stops once one is selected", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0, 1]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    await waitFor(() => expect(screen.getByTestId("map-shape")).toHaveTextContent("shape-0"));
    expect(screen.getByTestId("map-stops")).toHaveTextContent("2");
    // The route's own GTFS colour, not an interface colour.
    expect(screen.getByTestId("map-colour")).toHaveTextContent("DA251D");
    expect(detailPanel().getByText("Woodbine Ave")).toBeInTheDocument();
  });

  it("asks for a direction the route actually serves", async () => {
    const user = userEvent.setup();
    // A route that publishes only direction 1. Asking for 0, which is the initial
    // state, would be a 404 shown to the user as a failure.
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [1]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(1));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    await waitFor(() => expect(fetchRouteShape).toHaveBeenCalled());
    for (const call of vi.mocked(fetchRouteShape).mock.calls) {
      expect(call[1]).toBe(1);
    }
    expect(detailPanel().queryByRole("alert")).not.toBeInTheDocument();
  });

  it("refetches the geometry when the direction is changed", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0, 1]));
    vi.mocked(fetchRouteShape).mockImplementation((_routeId, directionId) =>
      Promise.resolve(shape(directionId)),
    );
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));
    await waitFor(() => expect(screen.getByTestId("map-shape")).toHaveTextContent("shape-0"));

    await user.click(detailPanel().getByRole("button", { name: "Westbound" }));

    await waitFor(() => expect(screen.getByTestId("map-shape")).toHaveTextContent("shape-1"));
    expect(fetchRouteStops).toHaveBeenLastCalledWith("501", 1, expect.anything());
  });

  it("clears the stop selection when the direction changes", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0, 1]));
    vi.mocked(fetchRouteShape).mockImplementation((_routeId, directionId) =>
      Promise.resolve(shape(directionId)),
    );
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));
    await waitFor(() => expect(screen.getByTestId("map-shape")).toHaveTextContent("shape-0"));

    await user.click(detailPanel().getByText("Coxwell Ave"));
    expect(screen.getByTestId("map-stop-selection")).toHaveTextContent("1002");

    await user.click(detailPanel().getByRole("button", { name: "Westbound" }));

    // The two directions do not serve the same stop ids, so a kept selection
    // would highlight a stop that is no longer in the list.
    await waitFor(() =>
      expect(screen.getByTestId("map-stop-selection")).toHaveTextContent("none"),
    );
  });

  it("sends a stop chosen from the list through to the map", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));
    await waitFor(() => expect(detailPanel().getByText("Woodbine Ave")).toBeInTheDocument());

    await user.click(detailPanel().getByText("Woodbine Ave"));

    // This is the keyboard path to what clicking a marker does.
    expect(screen.getByTestId("map-stop-selection")).toHaveTextContent("1001");
  });

  it("reports an unreachable API instead of an empty panel", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    const alert = await waitFor(() => detailPanel().getByRole("alert"));
    expect(alert).toHaveTextContent("The Trackline API could not be reached.");
    expect(fetchRouteShape).not.toHaveBeenCalled();
  });

  it("quotes the API's own reason when it answers with an error", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockRejectedValue(
      new ApiError("Shape for route 501 direction 0 was not found.", 404, "a1b2c3"),
    );
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    const alert = await waitFor(() => detailPanel().getByRole("alert"));
    expect(alert).toHaveTextContent("Shape for route 501 direction 0 was not found.");
    expect(screen.getByTestId("map-shape")).toHaveTextContent("none");
  });

  it("clears the previous route's line as soon as another is chosen", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));
    await waitFor(() => expect(screen.getByTestId("map-shape")).toHaveTextContent("shape-0"));

    // Never resolves: the point is what the map shows in the meantime.
    vi.mocked(fetchRoute).mockReturnValue(new Promise(() => undefined));
    await user.click(filters().getByText("Airport Express"));

    // Showing one route's geometry under another route's name would be a lie the
    // user has no way to detect.
    expect(screen.getByTestId("map-shape")).toHaveTextContent("none");
    expect(screen.getByTestId("map-stops")).toHaveTextContent("0");
  });
});

describe("MapExplorer live vehicles", () => {
  it("follows the whole fleet until a route is chosen", () => {
    render(<MapExplorer routes={ROUTES} />);

    expect(useLiveVehicles).toHaveBeenLastCalledWith([]);
  });

  it("narrows the subscription to the selected route, so the server does the filtering", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    await waitFor(() => expect(useLiveVehicles).toHaveBeenLastCalledWith(["501"]));
  });

  it("gives the map each route's published colour and none for a route without one", () => {
    render(<MapExplorer routes={ROUTES} />);

    expect(screen.getByTestId("map-live-colour-501")).toHaveTextContent("DA251D");
    expect(screen.getByTestId("map-live-colour-unlisted")).toHaveTextContent("none");
  });

  it("shows how many vehicles the selected route has running", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
    liveStore.dispatch({ type: "connection", status: "open", nowMs: 1_000 });
    liveStore.dispatch({
      type: "snapshot",
      message: liveSnapshot([liveVehicle("a"), liveVehicle("b"), liveVehicle("c")]),
      eventId: "1",
      nowMs: 1_000,
    });

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    expect(await detailPanel().findByText("3 tracked now")).toBeInTheDocument();
  });

  it("calls the count last-known, not live, once the stream has dropped", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
    liveStore.dispatch({ type: "connection", status: "open", nowMs: 1_000 });
    liveStore.dispatch({
      type: "snapshot",
      message: liveSnapshot([liveVehicle("a"), liveVehicle("b"), liveVehicle("c")]),
      eventId: "1",
      nowMs: 1_000,
    });
    liveStore.dispatch({ type: "connection", status: "reconnecting", nowMs: 2_000 });

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    expect(await detailPanel().findByText("3 last known")).toBeInTheDocument();
    expect(detailPanel().queryByText(/tracked now/)).not.toBeInTheDocument();
  });

  describe("service health for the selected route", () => {
    it("asks for nothing while no route is selected", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(vi.mocked(useRouteHealth)).toHaveBeenCalledWith(null);
    });

    it("asks for the selected route's health once one is chosen", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      await waitFor(() => expect(vi.mocked(useRouteHealth)).toHaveBeenLastCalledWith("501"));
    });

    it("shows the route's verdict in its panel", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      vi.mocked(useRouteHealth).mockReturnValue({
        status: "ready",
        data: {
          routeId: "501",
          state: "UNSTABLE",
          confidence: "MEDIUM",
          reasons: ["HIGH_HEADWAY_VARIATION"],
          directions: [
            {
              directionId: 0,
              state: "UNSTABLE",
              confidence: "MEDIUM",
              reasons: ["HIGH_HEADWAY_VARIATION"],
              metrics: {
                vehicles: 5, gaps: 4, meanGapMetres: 3000, maxGapMetres: 4200, variation: 0.62, gapRatio: 1.4,
                expectedGapMetres: 3000, expectedVehicles: 6, supplyRatio: 0.8, scheduledHeadwaySeconds: 600,
                speedMetresPerSecond: 5, staleVehicles: 0, terminalVehicles: 0, basis: "SCHEDULE", bunchingClusters: 0,
              },
            },
          ],
          unplacedVehicles: 0,
          computedAt: "2026-10-12T14:00:02Z",
        },
        meta: {
          generatedAt: "2026-10-12T14:00:30Z", feedTimestamp: "2026-10-12T14:00:00Z", ageSeconds: 30,
          stale: false, degraded: false, source: "LIVE",
        },
        serverOffsetMs: 0,
        refreshFailed: false,
      });
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      expect(await detailPanel().findByText(/Spacing varies by 62%/)).toBeInTheDocument();
      expect(detailPanel().getByRole("heading", { name: "Service health" })).toBeInTheDocument();
    });

    it("shows no health section before a route is chosen", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(screen.queryByRole("heading", { name: "Service health" })).not.toBeInTheDocument();
    });
  });

  describe("spacing and bunching for the selected route", () => {
    it("asks for nothing while no route is selected", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(vi.mocked(useRouteBunching)).toHaveBeenCalledWith(null);
    });

    it("asks for the selected route's spacing once one is chosen", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      await waitFor(() => expect(vi.mocked(useRouteBunching)).toHaveBeenLastCalledWith("501"));
    });

    it("shows the spacing section in the route's panel", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      vi.mocked(useRouteBunching).mockReturnValue({
        status: "ready",
        data: { routeId: "501", directions: [], events: [], computedAt: "2026-10-13T14:00:02Z" },
        meta: {
          generatedAt: "2026-10-13T14:00:30Z", feedTimestamp: "2026-10-13T14:00:00Z", ageSeconds: 30,
          stale: false, degraded: false, source: "LIVE",
        },
        serverOffsetMs: 0,
        refreshFailed: false,
      });
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      expect(await detailPanel().findByRole("heading", { name: "Spacing along the route" })).toBeInTheDocument();
    });

    it("shows no spacing section before a route is chosen", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(screen.queryByRole("heading", { name: "Spacing along the route" })).not.toBeInTheDocument();
    });
  });

  describe("opening on a route from a link", () => {
    it("starts with the named route selected, as a link from a saved-routes list asks", async () => {
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

      render(<MapExplorer routes={ROUTES} initialRouteId="501" />);

      await waitFor(() => expect(vi.mocked(fetchRoute)).toHaveBeenCalledWith("501", expect.anything()));
      expect(vi.mocked(useRouteBunching)).toHaveBeenLastCalledWith("501");
    });

    it("opens with nothing selected when the route is not one the schedule has, so a stale link still opens the map", () => {
      render(<MapExplorer routes={ROUTES} initialRouteId="no-such-route" />);

      expect(vi.mocked(useRouteBunching)).toHaveBeenLastCalledWith(null);
      expect(vi.mocked(fetchRoute)).not.toHaveBeenCalled();
    });

    it("opens with nothing selected when no route was named", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(vi.mocked(useRouteBunching)).toHaveBeenLastCalledWith(null);
    });
  });

  describe("history for the selected route", () => {
    it("shows how steady the route has been once one is chosen", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      expect(await detailPanel().findByRole("heading", { name: "How steady it has been" })).toBeInTheDocument();
      expect(vi.mocked(useRouteReliability)).toHaveBeenLastCalledWith("501", 7);
    });

    it("shows no history section before a route is chosen", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(screen.queryByRole("heading", { name: "How steady it has been" })).not.toBeInTheDocument();
    });
  });

  describe("alerts for the selected route", () => {
    const readyAlerts = (descriptions: string[], total = descriptions.length): AlertsResult => ({
      status: "ready",
      alerts: descriptions.map((description, index) => ({
        id: index + 1,
        sourceAlertId: `s${index}`,
        status: "ACTIVE",
        cause: "UNKNOWN_CAUSE",
        effect: "UNKNOWN_EFFECT",
        header: null,
        description,
        url: null,
        firstSeenAt: "2026-10-12T10:00:00Z",
        lastSeenAt: "2026-10-12T11:59:40Z",
        lastChangedAt: "2026-10-12T10:00:00Z",
        resolvedAt: null,
        routes: [],
        stops: [],
      })),
      total,
      meta: {
        generatedAt: "2026-10-12T12:00:00Z",
        feedTimestamp: "2026-10-12T11:59:40Z",
        ageSeconds: 20,
        stale: false,
        degraded: false,
        source: "STORED",
      },
      serverOffsetMs: 0,
      refreshFailed: false,
    });

    it("fetches nothing while no route is selected", () => {
      render(<MapExplorer routes={ROUTES} />);

      expect(vi.mocked(useAlerts)).toHaveBeenCalledWith({ active: true }, false);
    });

    it("asks for the selected route's alerts, three at most, once one is chosen", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      await waitFor(() =>
        expect(vi.mocked(useAlerts)).toHaveBeenLastCalledWith({ active: true, route: "501", size: 3 }, true),
      );
    });

    it("shows what the route is named in, in its panel", async () => {
      const user = userEvent.setup();
      vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
      vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
      vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
      vi.mocked(useAlerts).mockReturnValue(readyAlerts(["Queen detour via Dundas"]));
      render(<MapExplorer routes={ROUTES} />);

      await user.click(filters().getByText("Queen"));

      expect(await detailPanel().findByText("Queen detour via Dundas")).toBeInTheDocument();
      expect(detailPanel().getByRole("heading", { name: "Alerts naming this route" })).toBeInTheDocument();
    });

    it("shows no alerts section before a route is chosen", () => {
      vi.mocked(useAlerts).mockReturnValue(readyAlerts(["Should not appear"]));
      render(<MapExplorer routes={ROUTES} />);

      expect(screen.queryByText("Should not appear")).not.toBeInTheDocument();
    });
  });

  it("says none are reporting when the stream knows of none, which is a finding", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);
    liveStore.dispatch({ type: "connection", status: "open", nowMs: 1_000 });
    liveStore.dispatch({ type: "snapshot", message: liveSnapshot([]), eventId: "1", nowMs: 1_000 });

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    expect(await detailPanel().findByText("None reporting now")).toBeInTheDocument();
  });

  it("says connecting, not none, before the first snapshot has arrived", async () => {
    const user = userEvent.setup();
    vi.mocked(fetchRoute).mockResolvedValue(detail("501", [0]));
    vi.mocked(fetchRouteShape).mockResolvedValue(shape(0));
    vi.mocked(fetchRouteStops).mockResolvedValue(STOPS);

    render(<MapExplorer routes={ROUTES} />);
    await user.click(filters().getByText("Queen"));

    // Not knowing yet and knowing there are none are different claims.
    expect(await detailPanel().findByText("Connecting…")).toBeInTheDocument();
    expect(detailPanel().queryByText("None reporting now")).not.toBeInTheDocument();
  });
});
