import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ReliabilityBars } from "@/components/health/ReliabilityBars";
import { hourSlots, weekdaySlots } from "@/lib/reliability/chart";

import { NEEDS, hour, weekday } from "./reliabilityHelpers";

const NOTE = "A dot marks an hour without enough.";

function hours(...cells: ReturnType<typeof hour>[]) {
  return hourSlots(cells, NEEDS);
}

/** The focusable chart, not the table disclosure, which is also a group. */
function chartGroup() {
  return screen.getByRole("group", { name: /Use the arrow keys/ });
}

function chart(slots = hours(hour(8, 0.9), hour(17, 0.4))) {
  return render(<ReliabilityBars title="Steady share by hour of the day" slots={slots} labelEvery={6} note={NOTE} />);
}

describe("the steady-share bar chart", () => {
  it("has a slot for every hour and a bar only where there is enough behind it", () => {
    chart();

    const slots = screen.getAllByTestId("bar-slot");
    expect(slots).toHaveLength(24);
    expect(slots.filter((slot) => slot.getAttribute("data-has-bar") === "true")).toHaveLength(2);
  });

  it("describes the whole chart in one sentence for a screen reader", () => {
    chart();

    expect(screen.getByRole("img")).toHaveAccessibleName(
      "Steady share by hour of the day: 2 of 24 bars drawn; steadiest 8 am to 9 am at 90%, least steady 5 pm to 6 pm at 40%.",
    );
  });

  it("marks a missing bar with a dot and not with a bar at zero", () => {
    chart(hours());

    const slots = screen.getAllByTestId("bar-slot");
    for (const slot of slots) {
      expect(slot.getAttribute("data-has-bar")).toBe("false");
      expect(slot.querySelector("path")).toBeNull();
      expect(slot.querySelector("circle")).not.toBeNull();
    }
  });

  it("still draws a real zero, so every sample being unsteady is not shown as no data", () => {
    chart(hours(hour(8, 0)));

    const slot = screen.getAllByTestId("bar-slot")[8]!;
    expect(slot.getAttribute("data-has-bar")).toBe("true");
    expect(slot.querySelector("path")).not.toBeNull();
  });

  it("labels the axis only where asked, so it does not crowd", () => {
    chart();

    const labels = Array.from(document.querySelectorAll("svg text")).map((node) => node.textContent);
    expect(labels).toContain("12 am");
    expect(labels).toContain("6 am");
    expect(labels).toContain("12 pm");
    expect(labels).toContain("6 pm");
    expect(labels).not.toContain("7 am");
  });

  it("says what a missing bar means", () => {
    chart();

    expect(screen.getByText(NOTE)).toBeInTheDocument();
  });
});

describe("reading a bar", () => {
  it("invites the reader before one is chosen", () => {
    chart();

    expect(screen.getByTestId("bar-readout")).toHaveTextContent("Point at a bar or use the arrow keys to read it.");
  });

  it("reads out the bar the pointer is over, with what stands behind it", () => {
    chart();
    const hit = screen.getAllByTestId("bar-slot")[8]!.querySelector("rect[fill='transparent']")!;

    fireEvent.pointerOver(hit);

    expect(screen.getByTestId("bar-readout")).toHaveTextContent(
      "8 am to 9 am: 90% steady, from 180 judged samples over 3 days.",
    );
  });

  it("reads out a missing bar as not enough yet, with what it needs", () => {
    chart();
    const hit = screen.getAllByTestId("bar-slot")[3]!.querySelector("rect[fill='transparent']")!;

    fireEvent.pointerOver(hit);

    expect(screen.getByTestId("bar-readout")).toHaveTextContent(
      "3 am to 4 am: Not enough yet: needs 60 judged samples from at least 3 different days.",
    );
  });

  it("follows the arrow keys, so the pointer is never needed", () => {
    chart();
    const group = chartGroup();
    group.focus();

    fireEvent.keyDown(group, { key: "ArrowRight" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("12 am to 1 am");
    fireEvent.keyDown(group, { key: "ArrowRight" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("1 am to 2 am");
    fireEvent.keyDown(group, { key: "ArrowLeft" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("12 am to 1 am");
  });

  it("starts from the last bar when going left from nothing, and stops at the ends", () => {
    chart();
    const group = chartGroup();

    fireEvent.keyDown(group, { key: "ArrowLeft" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("11 pm to 12 am");
    fireEvent.keyDown(group, { key: "ArrowRight" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("11 pm to 12 am");
    fireEvent.keyDown(group, { key: "Home" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("12 am to 1 am");
    fireEvent.keyDown(group, { key: "ArrowLeft" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("12 am to 1 am");
    fireEvent.keyDown(group, { key: "End" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("11 pm to 12 am");
  });

  it("clears the readout on Escape and when focus leaves", () => {
    chart();
    const group = chartGroup();

    fireEvent.keyDown(group, { key: "Home" });
    fireEvent.keyDown(group, { key: "Escape" });
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("Point at a bar");

    fireEvent.keyDown(group, { key: "Home" });
    fireEvent.blur(group);
    expect(screen.getByTestId("bar-readout")).toHaveTextContent("Point at a bar");
  });

  it("leaves other keys alone", () => {
    chart();
    const group = chartGroup();

    const handled = fireEvent.keyDown(group, { key: "a" });

    // fireEvent returns false when the default was prevented.
    expect(handled).toBe(true);
  });
});

describe("the table twin", () => {
  it("lists every slot, with a dash where there is no bar", () => {
    chart();

    const rows = within(screen.getByRole("table", { hidden: true })).getAllByRole("row", { hidden: true });
    // A header row and twenty-four hours.
    expect(rows).toHaveLength(25);
    expect(rows[9]).toHaveTextContent("8 am to 9 am");
    expect(rows[9]).toHaveTextContent("90%");
    expect(rows[4]).toHaveTextContent("3 am to 4 am");
    expect(rows[4]).toHaveTextContent("–");
    expect(rows[4]).toHaveTextContent("Not enough yet");
  });

  it("heads the first column by what the slots are", () => {
    render(<ReliabilityBars title="By day" slots={weekdaySlots([weekday(2, 0.7)], NEEDS)} note="n" />);

    expect(screen.getByRole("columnheader", { name: "Day", hidden: true })).toBeInTheDocument();
    expect(screen.getAllByTestId("bar-slot")).toHaveLength(7);
  });
});
