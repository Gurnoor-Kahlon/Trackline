import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAuth } from "@/components/auth/AuthProvider";
import { HomeFavourites } from "@/components/favourites/HomeFavourites";
import { useFavourites } from "@/components/favourites/FavouritesProvider";
import { useHealthRoutes } from "@/lib/health/useHealth";

import { summary } from "../health/helpers";
import { meta } from "../health/helpers";
import { ANONYMOUS, LOADING, authValue, authenticated } from "./authHelpers";
import { favourites, routeSummary, savedRoute, savedStop } from "./favouritesHelpers";

vi.mock("@/components/auth/AuthProvider", () => ({ useAuth: vi.fn() }));
vi.mock("@/components/favourites/FavouritesProvider", () => ({ useFavourites: vi.fn() }));
vi.mock("@/lib/health/useHealth", () => ({ useHealthRoutes: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);
const useFavouritesMock = vi.mocked(useFavourites);
const useHealthMock = vi.mocked(useHealthRoutes);

type Value = ReturnType<typeof useFavourites>;

function show(partial: Partial<Value>) {
  useFavouritesMock.mockReturnValue({
    status: "ready",
    favourites: null,
    isSaved: () => true,
    isPending: () => false,
    toggle: vi.fn().mockResolvedValue(undefined),
    error: null,
    ...partial,
  });
}

function withHealth(...routes: ReturnType<typeof summary>[]) {
  useHealthMock.mockReturnValue({
    status: "ready",
    data: { routes, computedAt: "2026-10-20T12:00:00Z" },
    meta: meta(),
    serverOffsetMs: 0,
    refreshFailed: false,
  });
}

beforeEach(() => {
  useAuthMock.mockReset();
  useFavouritesMock.mockReset();
  useHealthMock.mockReset();
  useAuthMock.mockReturnValue(authValue(authenticated()));
  useHealthMock.mockReturnValue({ status: "loading" });
});

describe("saved routes and stops on the home page", () => {
  it("draws nothing for a visitor, on a page that works without an account", () => {
    useAuthMock.mockReturnValue(authValue(ANONYMOUS));
    show({ status: "idle" });

    const { container } = render(<HomeFavourites />);

    expect(container).toBeEmptyDOMElement();
  });

  it("draws nothing while it is finding out who is signed in", () => {
    useAuthMock.mockReturnValue(authValue(LOADING));
    show({ status: "idle" });

    const { container } = render(<HomeFavourites />);

    expect(container).toBeEmptyDOMElement();
  });

  it("says it is loading", () => {
    show({ status: "loading" });

    render(<HomeFavourites />);

    expect(screen.getByText("Loading your saved routes and stops")).toBeInTheDocument();
  });

  it("says what could not be loaded", () => {
    show({ status: "error", error: "Could not reach the Trackline API." });

    render(<HomeFavourites />);

    expect(screen.getByRole("alert")).toHaveTextContent("Could not reach the Trackline API.");
  });

  it("says nothing is saved yet and how to save something", () => {
    show({ favourites: favourites() });

    render(<HomeFavourites />);

    expect(screen.getByText("Nothing saved yet")).toBeInTheDocument();
    expect(screen.getByText(/press Save/)).toBeInTheDocument();
  });

  it("lists a saved route with its name, a link to it on the map, and how it is running", () => {
    show({ favourites: favourites([savedRoute("501")]) });
    withHealth(summary({ routeId: "501", state: "UNSTABLE", confidence: "HIGH", vehicles: 12 }));

    render(<HomeFavourites />);

    const row = within(screen.getByRole("list", { name: "Saved routes" })).getByRole("listitem");
    expect(within(row).getByRole("link", { name: "Queen on the map" })).toHaveAttribute("href", "/map?route=501");
    expect(within(row).getByText("Unstable")).toBeInTheDocument();
    expect(row).toHaveTextContent("12 vehicles");
  });

  it("says no vehicles are being tracked rather than saying the route is fine", () => {
    show({ favourites: favourites([savedRoute("501")]) });
    withHealth();

    render(<HomeFavourites />);

    expect(screen.getByText("No vehicles tracked now")).toBeInTheDocument();
    expect(screen.queryByText("Excellent")).not.toBeInTheDocument();
  });

  it("shows the route without its health while that is still loading", () => {
    show({ favourites: favourites([savedRoute("501")]) });

    render(<HomeFavourites />);

    expect(screen.getByRole("link", { name: "Queen on the map" })).toBeInTheDocument();
  });

  it("keeps a route the schedule no longer lists and says so", () => {
    show({ favourites: favourites([savedRoute("9999", null)]) });

    render(<HomeFavourites />);

    expect(screen.getByText("Route 9999")).toBeInTheDocument();
    expect(screen.getByText("Not in the current schedule")).toBeInTheDocument();
    // Nothing to open on a map.
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("lists saved stops by name and code", () => {
    show({ favourites: favourites([], [savedStop("3718", "Union Station", "1"), savedStop("9001", "Woodbine Ave", null)]) });

    render(<HomeFavourites />);

    const stops = within(screen.getByRole("list", { name: "Saved stops" }));
    expect(stops.getByText("Union Station")).toBeInTheDocument();
    expect(stops.getByText("#1")).toBeInTheDocument();
    expect(stops.getByText("Woodbine Ave")).toBeInTheDocument();
  });

  it("keeps a stop the schedule no longer lists", () => {
    show({ favourites: favourites([], [savedStop("9999", null)]) });

    render(<HomeFavourites />);

    expect(screen.getByText("Stop 9999")).toBeInTheDocument();
    expect(screen.getByText("Not in the current schedule")).toBeInTheDocument();
  });

  it("offers a way to remove each, named for what it removes", () => {
    show({ favourites: favourites([savedRoute("501")], [savedStop("3718")]) });

    render(<HomeFavourites />);

    expect(screen.getByRole("button", { name: "Remove route 501 from saved routes" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove Union Station from saved stops" })).toBeInTheDocument();
  });

  it("shows an error from a change beside the list that is still there", () => {
    show({ favourites: favourites([savedRoute("501")]), error: "That could not be saved." });

    render(<HomeFavourites />);

    expect(screen.getByRole("alert")).toHaveTextContent("That could not be saved.");
    expect(screen.getByRole("link", { name: "Queen on the map" })).toBeInTheDocument();
  });

  it("does not need the schedule to know the route to remove it", () => {
    show({ favourites: favourites([savedRoute("9999", routeSummary({ routeId: "9999" }))]) });

    render(<HomeFavourites />);

    expect(screen.getByRole("button", { name: /Remove route/ })).toBeInTheDocument();
  });
});
