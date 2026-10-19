import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ReliabilitySection } from "@/components/health/ReliabilitySection";
import { useRouteReliability } from "@/lib/health/useHealth";

import {
  available,
  coverage,
  figures,
  hour,
  insufficient,
  reliabilityReady,
  weekday,
} from "./reliabilityHelpers";

vi.mock("@/lib/health/useHealth", () => ({ useRouteReliability: vi.fn() }));

const hook = vi.mocked(useRouteReliability);

beforeEach(() => {
  hook.mockReset();
  hook.mockReturnValue({ status: "loading" });
});

describe("the reliability section of a route's panel", () => {
  it("says it is loading while it waits", () => {
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText("Loading history…")).toBeInTheDocument();
  });

  it("says it failed rather than showing nothing", () => {
    hook.mockReturnValue({ status: "error", message: "down", errorId: undefined });
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByRole("alert")).toHaveTextContent("History could not be loaded.");
  });

  it("asks for the route and, to start with, the last seven days", () => {
    render(<ReliabilitySection routeId="501" />);

    expect(hook).toHaveBeenLastCalledWith("501", 7);
  });
});

describe("when there is not enough history", () => {
  it("shows how much there is against how much is needed, and no figures", () => {
    hook.mockReturnValue(
      reliabilityReady(insufficient("TOO_FEW_HOURS", { coverage: coverage({ hoursCovered: 9, samples: 540 }) })),
    );
    render(<ReliabilitySection routeId="501" />);

    const card = screen.getByTestId("reliability-shortfall");
    expect(within(card).getByText("Not enough history yet")).toBeInTheDocument();
    expect(within(card).getByText("9 of 24 hours recorded")).toBeInTheDocument();
    expect(within(card).getByRole("img", { name: "9 of 24 hours recorded" })).toBeInTheDocument();
    // No figure of any kind, and no chart: nothing is drawn from an afternoon.
    expect(screen.queryByTestId("reliability-figures")).not.toBeInTheDocument();
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it("says nothing has been summarised, without a progress bar, when there are no summarised hours", () => {
    hook.mockReturnValue(
      reliabilityReady(
        insufficient("NO_SAMPLES", { coverage: coverage({ hoursCovered: 0, firstHour: null, lastHour: null }) }),
      ),
    );
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText("Nothing summarised yet")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("explains a route that has been recorded but rarely judged", () => {
    hook.mockReturnValue(
      reliabilityReady(
        insufficient("TOO_FEW_JUDGED_SAMPLES", { coverage: coverage({ hoursCovered: 30, judgedSamples: 100 }) }),
      ),
    );
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText("Recorded, but too rarely judged")).toBeInTheDocument();
    expect(screen.getByText("100 of 240 judged samples")).toBeInTheDocument();
  });
});

describe("when there is enough history", () => {
  it("shows the three figures, each with what it is a share of", () => {
    hook.mockReturnValue(reliabilityReady(available()));
    render(<ReliabilitySection routeId="501" />);

    const figuresList = screen.getByTestId("reliability-figures");
    expect(within(figuresList).getByText("Steady").nextElementSibling).toHaveTextContent("66.7%");
    expect(within(figuresList).getByText("Spacing varies by").nextElementSibling).toHaveTextContent("30%");
    expect(within(figuresList).getByText("Running together").nextElementSibling).toHaveTextContent("5%");
    expect(within(figuresList).getByText("of judged samples were excellent or normal")).toBeInTheDocument();
  });

  it("states the history behind the figures and when they run to", () => {
    hook.mockReturnValue(reliabilityReady(available()));
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText(/In the last 7 days: 1,440 samples from 24 hours on 1 day\./)).toBeInTheDocument();
    expect(screen.getByText(/Counted through .*, Toronto time\./)).toBeInTheDocument();
  });

  it("says how much of the time the route could not be judged, and that it is in no share", () => {
    hook.mockReturnValue(
      reliabilityReady(available({ summary: figures({ samples: 1600, judged: 1440, unjudged: 160 }) })),
    );
    render(<ReliabilitySection routeId="501" />);

    expect(
      screen.getByText(/10% of samples could not be judged, with fewer than two vehicles tracked/),
    ).toBeInTheDocument();
  });

  it("says nothing about unjudged samples when every one was judged", () => {
    hook.mockReturnValue(reliabilityReady(available()));
    render(<ReliabilitySection routeId="501" />);

    expect(screen.queryByText(/could not be judged/)).not.toBeInTheDocument();
  });

  it("does not draw an hour of the day until enough days stand behind it", () => {
    hook.mockReturnValue(reliabilityReady(available()));
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText("An hour is drawn once 3 different days stand behind it.")).toBeInTheDocument();
    const bars = screen.getAllByTestId("bar-slot");
    expect(bars.every((bar) => bar.getAttribute("data-has-bar") === "false")).toBe(true);
  });

  it("draws the hours that have enough and explains the dots for those that do not", () => {
    hook.mockReturnValue(
      reliabilityReady(available({ byHour: [hour(8, 0.9), hour(17, 0.4)], byWeekday: [weekday(2, 0.7)] })),
    );
    render(<ReliabilitySection routeId="501" />);

    const withBars = screen.getAllByTestId("bar-slot").filter((bar) => bar.getAttribute("data-has-bar") === "true");
    expect(withBars).toHaveLength(3);
    expect(
      screen.getByText("A dot marks an hour without 60 judged samples from 3 different days."),
    ).toBeInTheDocument();
    expect(
      screen.getByText("A dot marks a day without 60 judged samples from 2 different days."),
    ).toBeInTheDocument();
  });

  it("explains that the weekdays take more than a week to appear", () => {
    hook.mockReturnValue(reliabilityReady(available()));
    render(<ReliabilitySection routeId="501" />);

    expect(screen.getByText(/which takes more than a week of recording/)).toBeInTheDocument();
  });
});

describe("choosing a window", () => {
  it("offers the four windows with the current one checked", () => {
    render(<ReliabilitySection routeId="501" />);

    const group = screen.getByRole("radiogroup", { name: "Time window" });
    const options = within(group).getAllByRole("radio");
    expect(options.map((option) => option.textContent)).toEqual(["24 hours", "7 days", "30 days", "90 days"]);
    expect(within(group).getByRole("radio", { name: "7 days" })).toBeChecked();
  });

  it("asks again for the window chosen", async () => {
    const user = userEvent.setup();
    render(<ReliabilitySection routeId="501" />);

    await user.click(screen.getByRole("radio", { name: "30 days" }));

    expect(hook).toHaveBeenLastCalledWith("501", 30);
    expect(screen.getByRole("radio", { name: "30 days" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "7 days" })).not.toBeChecked();
  });
});
