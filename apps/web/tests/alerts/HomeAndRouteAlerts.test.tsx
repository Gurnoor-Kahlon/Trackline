import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { HomeAlerts } from "@/components/alerts/HomeAlerts";
import { RouteAlerts } from "@/components/alerts/RouteAlerts";
import { fetchAlerts } from "@/lib/api/alerts";
import { ApiError } from "@/lib/api/client";
import type { AlertsResult } from "@/lib/alerts/useAlerts";

import { alert, envelope, meta, SERVER_NOW } from "./helpers";

vi.mock("@/lib/api/alerts", () => ({ fetchAlerts: vi.fn() }));

const fetchMock = vi.mocked(fetchAlerts);

beforeEach(() => {
  fetchMock.mockReset();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(SERVER_NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("the alerts card on the home page", () => {
  it("shows the newest few and says how many there are", async () => {
    fetchMock.mockResolvedValue(
      envelope([alert({ description: "First" }), alert({ description: "Second" }), alert({ description: "Third" })], {
        total: 11,
      }),
    );
    render(<HomeAlerts />);

    expect(await screen.findByText("First")).toBeInTheDocument();
    expect(screen.getByText(/11 active alerts/)).toBeInTheDocument();
    expect(screen.getByText(/The newest 3 are shown/)).toBeInTheDocument();
  });

  it("asks only for as many as it shows", async () => {
    fetchMock.mockResolvedValue(envelope([alert()]));
    render(<HomeAlerts />);
    await screen.findByText(/Dundas St E/);

    expect(fetchMock).toHaveBeenCalledWith({ active: true, size: 3 }, expect.any(AbortSignal));
  });

  it("does not claim the alerts are ranked: the feed publishes no severity", async () => {
    fetchMock.mockResolvedValue(envelope([alert()]));
    render(<HomeAlerts />);

    expect(await screen.findByText(/does not rank alerts by severity/)).toBeInTheDocument();
  });

  it("links to the full list", async () => {
    fetchMock.mockResolvedValue(envelope([alert()]));
    render(<HomeAlerts />);
    await screen.findByText(/Dundas St E/);

    expect(screen.getByRole("link", { name: "All alerts" })).toHaveAttribute("href", "/alerts");
  });

  it("says alerts have not been ingested when the API has none to give, not that there are none", async () => {
    fetchMock.mockResolvedValue(envelope([], { meta: { source: "NO_DATA" } }));
    render(<HomeAlerts />);

    expect(await screen.findByText("Alerts have not been ingested yet")).toBeInTheDocument();
  });

  it("says there are none when the feed was read and carries none", async () => {
    fetchMock.mockResolvedValue(envelope([]));
    render(<HomeAlerts />);

    expect(await screen.findByText("No active alerts")).toBeInTheDocument();
  });

  it("shows an error, not an empty card, when the request fails", async () => {
    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));
    render(<HomeAlerts />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Alerts could not be loaded");
  });
});

describe("the alerts section of the route panel", () => {
  const ready = (alerts = [alert()], total = alerts.length): AlertsResult => ({
    status: "ready",
    alerts,
    total,
    meta: meta(),
    serverOffsetMs: 0,
    refreshFailed: false,
  });

  it("says what it lists: the alerts that name the route, which is not the same as affect it", () => {
    render(<RouteAlerts result={ready()} />);

    expect(screen.getByRole("heading", { name: "Alerts naming this route" })).toBeInTheDocument();
  });

  it("lists the alerts compactly", () => {
    render(<RouteAlerts result={ready([alert({ description: "Detour on Carlton" })])} />);

    expect(screen.getByText("Detour on Carlton")).toBeInTheDocument();
  });

  it("counts the ones it does not show and links to them", () => {
    render(<RouteAlerts result={ready([alert(), alert()], 9)} />);

    expect(screen.getByText(/7 more\./)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All alerts" })).toHaveAttribute("href", "/alerts");
  });

  it("says none when the feed was read and none name the route", () => {
    render(<RouteAlerts result={ready([], 0)} />);

    expect(screen.getByText(/None in the TTC's alerts feed/)).toBeInTheDocument();
  });

  it("says alerts have not been ingested rather than none", () => {
    render(
      <RouteAlerts
        result={{ ...ready([], 0), meta: meta({ source: "NO_DATA" }) } as AlertsResult}
      />,
    );

    expect(screen.getByText("Alerts have not been ingested yet.")).toBeInTheDocument();
    expect(screen.queryByText(/None in the TTC/)).not.toBeInTheDocument();
  });

  it("shows loading and error states without pretending to know", () => {
    const { rerender } = render(<RouteAlerts result={{ status: "loading" }} />);
    expect(screen.getByText("Loading alerts…")).toBeInTheDocument();

    rerender(<RouteAlerts result={{ status: "error", message: "x", errorId: undefined }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Alerts could not be loaded.");
  });

  it("flags alerts from an earlier fetch when the latest refresh failed", () => {
    render(<RouteAlerts result={{ ...ready(), refreshFailed: true } as AlertsResult} />);

    expect(screen.getByText(/latest refresh failed/)).toBeInTheDocument();
  });
});
