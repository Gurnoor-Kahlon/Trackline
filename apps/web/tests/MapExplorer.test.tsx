import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { MapExplorer } from "@/components/map/MapExplorer";
import { ApiError } from "@/lib/api/client";
import { fetchRoute, fetchRouteShape, fetchRouteStops } from "@/lib/api/routes";
import type { RouteDetail, RouteShape, RouteStop, RouteSummary } from "@/lib/api/types";

vi.mock("@/lib/api/routes", () => ({
  fetchRoute: vi.fn(),
  fetchRouteShape: vi.fn(),
  fetchRouteStops: vi.fn(),
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
  }: {
    shape: RouteShape | null;
    stops: RouteStop[];
    routeColour: string | null;
    selectedStopId: string | null;
    onStopSelect: (stopId: string) => void;
  }) => (
    <div data-testid="map">
      <span data-testid="map-shape">{shape === null ? "none" : shape.shapeId}</span>
      <span data-testid="map-stops">{stops.length}</span>
      <span data-testid="map-colour">{routeColour ?? "none"}</span>
      <span data-testid="map-stop-selection">{selectedStopId ?? "none"}</span>
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

beforeEach(() => {
  vi.mocked(fetchRoute).mockReset();
  vi.mocked(fetchRouteShape).mockReset();
  vi.mocked(fetchRouteStops).mockReset();
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
