import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { RouteFilterPanel } from "@/components/map/RouteFilterPanel";
import type { RouteSummary } from "@/lib/api/types";
import { EMPTY_FILTER, type RouteFilter } from "@/lib/map/routeFilter";

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
    routeId: "41",
    shortName: "41",
    longName: "Keele",
    mode: "BUS",
    colour: "F8C300",
    textColour: "000000",
    supportsRealtime: true,
  },
  {
    routeId: "1",
    shortName: "1",
    longName: "Line 1 Yonge-University",
    mode: "SUBWAY",
    colour: "F8C300",
    textColour: "000000",
    // The TTC publishes no subway vehicle positions.
    supportsRealtime: false,
  },
];

/** Holds the filter the way the page does, so the panel can be driven for real. */
function Harness({ onRouteSelect }: { onRouteSelect?: (routeId: string) => void }) {
  const [filter, setFilter] = useState<RouteFilter>(EMPTY_FILTER);
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <RouteFilterPanel
      routes={ROUTES}
      filter={filter}
      onFilterChange={setFilter}
      selectedRouteId={selected}
      onRouteSelect={(routeId) => {
        setSelected(routeId);
        onRouteSelect?.(routeId);
      }}
    />
  );
}

describe("RouteFilterPanel", () => {
  it("lists every route as something a keyboard can reach", () => {
    render(<Harness />);

    const list = screen.getByRole("list", { name: "Routes" });
    expect(list.querySelectorAll("button")).toHaveLength(3);
  });

  it("narrows the list as a query is typed", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.type(screen.getByLabelText("Find a route"), "queen");

    expect(screen.getByText("Queen")).toBeInTheDocument();
    expect(screen.queryByText("Keele")).not.toBeInTheDocument();
    expect(screen.getByText("1 of 3 routes")).toBeInTheDocument();
  });

  it("restricts the list to a mode and restores it when the mode is toggled off", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    const subway = screen.getByRole("button", { name: "Subway" });
    await user.click(subway);

    expect(subway).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Line 1 Yonge-University")).toBeInTheDocument();
    expect(screen.queryByText("Keele")).not.toBeInTheDocument();

    await user.click(subway);

    expect(subway).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("Keele")).toBeInTheDocument();
  });

  it("explains an empty result instead of showing a blank list", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.type(screen.getByLabelText("Find a route"), "zzz");

    expect(screen.getByText("No routes match")).toBeInTheDocument();
    // Nothing matching a search is not a failure, so it must not be announced as one.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports the selected route with aria-current", async () => {
    const user = userEvent.setup();
    const onRouteSelect = vi.fn();
    render(<Harness onRouteSelect={onRouteSelect} />);

    await user.click(screen.getByText("Queen"));

    expect(onRouteSelect).toHaveBeenCalledWith("501");
    const list = screen.getByRole("list", { name: "Routes" });
    const current = list.querySelectorAll("[aria-current='true']");
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent("Queen");
  });

  it("says in the list which routes have no live data", () => {
    render(<Harness />);

    // Stated up front, so an empty subway map is not read as a broken feature.
    expect(screen.getAllByText("No live data")).toHaveLength(1);
  });

  it("names the mode of each route for assistive technology", () => {
    render(<Harness />);

    expect(screen.getByText("Streetcar route")).toBeInTheDocument();
    expect(screen.getByText("Subway route")).toBeInTheDocument();
  });
});
