import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { useRouteReliability } from "@/lib/health/useHealth";

import { RouteDetailPanel } from "@/components/map/RouteDetailPanel";
import type { RouteDetail, RouteStop } from "@/lib/api/types";

vi.mock("@/lib/health/useHealth", () => ({ useRouteReliability: vi.fn(() => ({ status: "loading" })) }));

const ROUTE: RouteDetail = {
  routeId: "501",
  shortName: "501",
  longName: "Queen",
  mode: "STREETCAR",
  colour: "DA251D",
  textColour: "FFFFFF",
  supportsRealtime: true,
  directions: [
    {
      directionId: 0,
      headsign: "East to Neville Park",
      tripCount: 240,
      stopCount: 3,
      lengthMetres: 24680,
      loop: false,
    },
    {
      directionId: 1,
      headsign: "West to Long Branch",
      tripCount: 238,
      stopCount: 3,
      lengthMetres: 24710,
      loop: false,
    },
  ],
};

const STOPS: RouteStop[] = [
  {
    stopId: "1001",
    name: "Queen St East at Woodbine Ave",
    code: "11001",
    latitude: 43.666,
    longitude: -79.313,
    sequence: 1,
    distanceAlongRouteMetres: null,
    // GTFS 1: the agency states it is accessible.
    wheelchairBoarding: 1,
    stationId: null,
    stationName: null,
  },
  {
    stopId: "1002",
    name: "Queen St East at Coxwell Ave",
    code: "11002",
    latitude: 43.665,
    longitude: -79.32,
    sequence: 2,
    distanceAlongRouteMetres: 820,
    // GTFS 0: the agency says nothing, which is not the same as "no".
    wheelchairBoarding: 0,
    stationId: null,
    stationName: null,
  },
  {
    stopId: "1003",
    name: "Queen St East at Greenwood Ave",
    code: "11003",
    latitude: 43.664,
    longitude: -79.33,
    sequence: 3,
    distanceAlongRouteMetres: 1640,
    wheelchairBoarding: 2,
    stationId: null,
    stationName: null,
  },
];

const BASE = {
  stops: [] as RouteStop[],
  directionId: 0,
  onDirectionChange: () => undefined,
  selectedStopId: null,
  onStopSelect: () => undefined,
  loading: false,
  error: null,
};

describe("RouteDetailPanel", () => {
  it("invites a selection when nothing is selected", () => {
    render(<RouteDetailPanel {...BASE} route={null} />);

    expect(screen.getByText("No route selected")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows a loading state rather than the empty state while a route is on its way", () => {
    render(<RouteDetailPanel {...BASE} route={null} loading />);

    expect(screen.getByText("Loading route")).toBeInTheDocument();
    // An in-flight request must not be described as "nothing selected".
    expect(screen.queryByText("No route selected")).not.toBeInTheDocument();
  });

  it("announces a failure and quotes the reason", () => {
    render(<RouteDetailPanel {...BASE} route={null} error="The Trackline API could not be reached." />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("The Trackline API could not be reached.")).toBeInTheDocument();
  });

  it("prefers the error over the loading state when a retry is pending", () => {
    render(<RouteDetailPanel {...BASE} route={null} loading error="Route 999 was not found." />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.queryByText("Loading route")).not.toBeInTheDocument();
  });

  it("summarises the direction in kilometres", () => {
    // A one-way route, so the headsign appears once rather than also labelling a
    // direction toggle; the toggle has its own test below.
    const oneWay: RouteDetail = { ...ROUTE, directions: [ROUTE.directions[0]!] };
    render(<RouteDetailPanel {...BASE} route={oneWay} stops={STOPS} />);

    expect(screen.getByText("East to Neville Park")).toBeInTheDocument();
    expect(screen.getByText("24.7 km")).toBeInTheDocument();
  });

  it("says a length is not known rather than showing zero", () => {
    const withoutLength: RouteDetail = {
      ...ROUTE,
      directions: [{ ...ROUTE.directions[0]!, lengthMetres: null }],
    };
    render(<RouteDetailPanel {...BASE} route={withoutLength} />);

    expect(screen.getByText("Not known")).toBeInTheDocument();
    expect(screen.queryByText("0.0 km")).not.toBeInTheDocument();
  });

  it("shows how steady the route has been only where the page asks for it", () => {
    const { rerender } = render(<RouteDetailPanel {...BASE} route={ROUTE} />);
    expect(screen.queryByRole("heading", { name: "How steady it has been" })).not.toBeInTheDocument();

    rerender(<RouteDetailPanel {...BASE} route={ROUTE} showReliability />);

    expect(screen.getByRole("heading", { name: "How steady it has been" })).toBeInTheDocument();
    expect(vi.mocked(useRouteReliability)).toHaveBeenCalledWith("501", 7);
  });

  it("shows no history for a route with no published vehicles, since none is ever recorded", () => {
    render(<RouteDetailPanel {...BASE} route={{ ...ROUTE, supportsRealtime: false }} showReliability />);

    expect(screen.queryByRole("heading", { name: "How steady it has been" })).not.toBeInTheDocument();
  });

  it("states plainly that a subway route publishes no vehicles", () => {
    render(<RouteDetailPanel {...BASE} route={{ ...ROUTE, supportsRealtime: false }} />);

    expect(screen.getByText("Not published")).toBeInTheDocument();
  });

  it("offers both directions and reports the chosen one", async () => {
    const user = userEvent.setup();
    const onDirectionChange = vi.fn();
    render(
      <RouteDetailPanel {...BASE} route={ROUTE} stops={STOPS} onDirectionChange={onDirectionChange} />,
    );

    const west = screen.getByRole("button", { name: "West to Long Branch" });
    expect(screen.getByRole("button", { name: "East to Neville Park" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(west).toHaveAttribute("aria-pressed", "false");

    await user.click(west);
    expect(onDirectionChange).toHaveBeenCalledWith(1);
  });

  it("hides the direction toggle when a route runs only one way", () => {
    const oneWay: RouteDetail = { ...ROUTE, directions: [ROUTE.directions[0]!] };
    render(<RouteDetailPanel {...BASE} route={oneWay} />);

    expect(screen.queryByRole("group", { name: "Direction" })).not.toBeInTheDocument();
  });

  it("lists the stops in order as the keyboard alternative to the map markers", async () => {
    const user = userEvent.setup();
    const onStopSelect = vi.fn();
    render(<RouteDetailPanel {...BASE} route={ROUTE} stops={STOPS} onStopSelect={onStopSelect} />);

    const list = screen.getByRole("list", { name: "Stops in order" });
    const names = [...list.querySelectorAll("button")].map((button) => button.textContent);
    expect(names[0]).toContain("Queen St East at Woodbine Ave");
    expect(names[2]).toContain("Queen St East at Greenwood Ave");

    await user.click(screen.getByText("Queen St East at Coxwell Ave"));
    expect(onStopSelect).toHaveBeenCalledWith("1002");
  });

  it("marks the selected stop with aria-current", () => {
    render(<RouteDetailPanel {...BASE} route={ROUTE} stops={STOPS} selectedStopId="1003" />);

    const current = screen.getByRole("list", { name: "Stops in order" }).querySelectorAll(
      "[aria-current='true']",
    );
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent("Queen St East at Greenwood Ave");
  });

  it("reports accessibility only where the feed states it", () => {
    render(<RouteDetailPanel {...BASE} route={ROUTE} stops={STOPS} />);

    expect(screen.getByText("Accessible")).toBeInTheDocument();
    expect(screen.getByText("Not accessible")).toBeInTheDocument();
    // wheelchair_boarding 0 means the agency said nothing. Rendering it as either
    // answer would invent a fact about a stop a rider depends on.
    expect(screen.getAllByText(/^(Accessible|Not accessible)$/)).toHaveLength(2);
  });

  it("explains an empty stop list instead of leaving a gap", () => {
    render(<RouteDetailPanel {...BASE} route={ROUTE} stops={[]} />);

    expect(screen.getByText("No stops listed")).toBeInTheDocument();
  });

  it("keeps the route header visible while its stops are still loading", () => {
    render(<RouteDetailPanel {...BASE} route={ROUTE} loading />);

    expect(screen.getByRole("heading", { name: "Queen" })).toBeInTheDocument();
    expect(screen.getByText("Loading stops")).toBeInTheDocument();
  });
});
