import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { SpacingStrip } from "@/components/health/SpacingStrip";

import { bunch, bunched, spacing, vehicle } from "./bunchingHelpers";

describe("the spacing strip", () => {
  it("names the direction it shows", () => {
    render(<SpacingStrip direction={spacing()} label="East - 501 Queen towards Neville Park" />);

    expect(screen.getByText("East - 501 Queen towards Neville Park")).toBeInTheDocument();
  });

  it("is an image whose description is the same sentence the picture shows", () => {
    render(<SpacingStrip direction={bunched()} label="East" />);

    const picture = screen.getByRole("img");

    expect(picture).toHaveAccessibleName(/4 vehicles measured/);
    expect(picture).toHaveAccessibleName(/2 running within 400 m of each other while the largest gap elsewhere is 6\.5 km/);
  });

  it("draws one marker per vehicle, with its role on the marker", () => {
    const { container } = render(<SpacingStrip direction={bunched()} label="East" />);

    const roles = Array.from(container.querySelectorAll("[data-role]")).map((marker) => marker.getAttribute("data-role"));

    expect(roles).toEqual(["AT_TERMINAL", "BUNCHED", "BUNCHED", "SPACED", "SPACED", "STALE"]);
  });

  it("positions a marker by its share of the way along the route", () => {
    const { container } = render(
      <SpacingStrip direction={spacing({ lengthMetres: 20_000, vehicles: [vehicle("a", 5000)] })} label="East" />,
    );

    expect(container.querySelector<HTMLElement>("[data-role]")?.style.left).toBe("25%");
  });

  it("marks a group with a bracket and none when there is no group", () => {
    const withGroup = render(<SpacingStrip direction={bunched()} label="East" />);
    expect(withGroup.getAllByTestId("bunch")).toHaveLength(1);
    withGroup.unmount();

    render(<SpacingStrip direction={spacing()} label="East" />);
    expect(screen.queryByTestId("bunch")).not.toBeInTheDocument();
  });

  it("draws a severe group differently from a moderate one, in more than colour", () => {
    const moderate = render(<SpacingStrip direction={bunched()} label="East" />);
    const moderateClass = moderate.getByTestId("bunch").className;
    moderate.unmount();

    const severe = render(
      <SpacingStrip
        direction={{ ...bunched(), bunches: [bunch(["a", "b"], { severity: "SEVERE" })] }}
        label="East"
      />,
    );

    expect(severe.getByTestId("bunch").className).not.toBe(moderateClass);
    // And the words carry it too, since colour alone is not enough.
    expect(moderateClass).toContain("unstable");
    expect(severe.getByTestId("bunch").className).toContain("major");
  });

  it("draws a bunch across the start of a loop as two brackets", () => {
    const direction = spacing({
      lengthMetres: 10_000,
      loop: true,
      vehicles: [vehicle("a", 9900, "BUNCHED"), vehicle("b", 100, "BUNCHED"), vehicle("c", 6000)],
      bunches: [bunch(["a", "b"], { spanMetres: 200 })],
    });

    render(<SpacingStrip direction={direction} label="Loop" />);

    expect(screen.getAllByTestId("bunch")).toHaveLength(2);
  });

  it("shows the spacing the vehicles were judged against and where it came from", () => {
    const { rerender } = render(<SpacingStrip direction={bunched()} label="East" />);
    expect(screen.getByText(/timetable spacing 3\.0 km/)).toBeInTheDocument();

    rerender(<SpacingStrip direction={{ ...bunched(), basis: "OBSERVED" }} label="East" />);
    expect(screen.getByText(/average spacing 3\.0 km/)).toBeInTheDocument();
  });

  it("shows no spacing when there were too few vehicles to work one out, not zero", () => {
    render(
      <SpacingStrip direction={spacing({ expectedGapMetres: null, vehicles: [vehicle("a", 4000)] })} label="East" />,
    );

    expect(screen.queryByText(/spacing 0/)).not.toBeInTheDocument();
    expect(screen.queryByText(/timetable spacing|average spacing/)).not.toBeInTheDocument();
  });

  it("lists every vehicle with its position and role, for anyone who cannot see the picture", async () => {
    const user = userEvent.setup();
    render(<SpacingStrip direction={bunched()} label="East" />);

    await user.click(screen.getByText("Vehicles along the route"));

    const list = screen.getByRole("list");
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(6);
    expect(items[0]).toHaveTextContent("start · 80 m");
    expect(items[0]).toHaveTextContent("at a terminal, left out of the spacing");
    expect(items[1]).toHaveTextContent("a · 1.0 km");
    expect(items[1]).toHaveTextContent("in a group running together");
    expect(items[5]).toHaveTextContent("not reporting recently, left out of the spacing");
  });

  it("has no vehicle list when there are no vehicles", () => {
    render(<SpacingStrip direction={spacing({ vehicles: [], expectedGapMetres: null })} label="East" />);

    expect(screen.queryByText("Vehicles along the route")).not.toBeInTheDocument();
  });
});
