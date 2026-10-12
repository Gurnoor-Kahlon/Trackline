import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { LiveStatusChip } from "@/components/map/LiveStatusChip";
import { LiveStore } from "@/lib/live/liveStore";

import { FEED_TIME, feed, snapshot, vehicle } from "./live/helpers";

const NO_NETWORK = { summary: null, connection: "open" as const };

describe("the live status chip", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(FEED_TIME);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function storeWithData(ageSeconds: number): LiveStore {
    const store = new LiveStore();
    store.dispatch({ type: "connection", status: "open", nowMs: Date.now() });
    store.dispatch({
      type: "snapshot",
      message: { ...snapshot([vehicle("a")], ageSeconds), feed: feed(ageSeconds) },
      eventId: "1",
      nowMs: Date.now(),
    });
    return store;
  }

  it("says live, with how old, when the feed is recent", () => {
    render(<LiveStatusChip store={storeWithData(12)} network={NO_NETWORK} />);

    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("updated 12s ago")).toBeInTheDocument();
  });

  it("keeps ageing on screen as the seconds pass", () => {
    render(<LiveStatusChip store={storeWithData(12)} network={NO_NETWORK} />);

    act(() => {
      vi.advanceTimersByTime(8_000);
    });

    expect(screen.getByText("updated 20s ago")).toBeInTheDocument();
  });

  it("warns when the data is old", () => {
    render(<LiveStatusChip store={storeWithData(90)} network={NO_NETWORK} />);

    expect(screen.getByText("Data is 1m old")).toBeInTheDocument();
  });

  it("says reconnecting when the connection is lost, with the last update's age", () => {
    const store = storeWithData(10);
    render(<LiveStatusChip store={store} network={NO_NETWORK} />);

    act(() => {
      vi.advanceTimersByTime(3_000);
      store.dispatch({ type: "connection", status: "reconnecting", nowMs: Date.now() });
    });

    expect(screen.getByText("Reconnecting")).toBeInTheDocument();
    expect(screen.getByText("last update 13s ago")).toBeInTheDocument();
  });

  it("shows the whole network's counts beside the status", () => {
    render(
      <LiveStatusChip
        store={storeWithData(5)}
        network={{ summary: { vehiclesTracked: 1507, routesReporting: 112, activeAlerts: null }, connection: "open" }}
      />,
    );

    expect(screen.getByText("1,507 vehicles on 112 routes")).toBeInTheDocument();
  });

  describe("the alert count", () => {
    const withAlerts = (activeAlerts: number | null) => ({
      summary: { vehiclesTracked: 1507, routesReporting: 112, activeAlerts },
      connection: "open" as const,
    });

    it("links to the alerts page", () => {
      render(<LiveStatusChip store={storeWithData(5)} network={withAlerts(11)} />);

      expect(screen.getByRole("link", { name: "11 alerts" })).toHaveAttribute("href", "/alerts");
    });

    it("is singular for one", () => {
      render(<LiveStatusChip store={storeWithData(5)} network={withAlerts(1)} />);

      expect(screen.getByRole("link", { name: "1 alert" })).toBeInTheDocument();
    });

    it("says zero when the count is a measured zero", () => {
      render(<LiveStatusChip store={storeWithData(5)} network={withAlerts(0)} />);

      expect(screen.getByRole("link", { name: "0 alerts" })).toBeInTheDocument();
    });

    it("says nothing when the count is not known, because that is not zero", () => {
      render(<LiveStatusChip store={storeWithData(5)} network={withAlerts(null)} />);

      expect(screen.queryByRole("link")).not.toBeInTheDocument();
    });

    it("is not inside the hidden, ticking text, since a focusable link must stay reachable", () => {
      render(<LiveStatusChip store={storeWithData(5)} network={withAlerts(3)} />);

      expect(screen.getByRole("link", { name: "3 alerts" }).closest("[aria-hidden='true']")).toBeNull();
    });
  });

  it("shows no counts rather than zeros before the network stream has answered", () => {
    render(<LiveStatusChip store={storeWithData(5)} network={NO_NETWORK} />);

    expect(screen.queryByText(/vehicles on/)).not.toBeInTheDocument();
  });

  describe("for a screen reader", () => {
    it("has one live region, carrying only the state", () => {
      render(<LiveStatusChip store={storeWithData(12)} network={NO_NETWORK} />);

      const regions = screen.getAllByRole("status");

      expect(regions).toHaveLength(1);
      expect(regions[0]).toHaveTextContent("Live data connected.");
    });

    it("does not change as the age ticks, so it is not read out every second", () => {
      render(<LiveStatusChip store={storeWithData(12)} network={NO_NETWORK} />);
      const before = screen.getByRole("status").textContent;

      act(() => {
        vi.advanceTimersByTime(30_000);
      });

      expect(screen.getByRole("status").textContent).toBe(before);
    });

    it("changes when the state does", () => {
      const store = storeWithData(12);
      render(<LiveStatusChip store={store} network={NO_NETWORK} />);

      act(() => {
        store.dispatch({ type: "connection", status: "reconnecting", nowMs: Date.now() });
      });

      expect(screen.getByRole("status")).toHaveTextContent("Reconnecting to live data.");
    });

    it("hides the visible, ticking text from assistive technology", () => {
      render(<LiveStatusChip store={storeWithData(12)} network={NO_NETWORK} />);

      expect(screen.getByText("updated 12s ago")).toHaveAttribute("aria-hidden", "true");
    });
  });
});
