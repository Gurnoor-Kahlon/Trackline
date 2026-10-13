import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HomeNetworkStatus } from "@/components/health/HomeNetworkStatus";
import { ApiError } from "@/lib/api/client";
import { fetchNetworkStatus } from "@/lib/api/network";
import type { ApiEnvelope, NetworkStatus } from "@/lib/api/types";

import { meta } from "./helpers";

vi.mock("@/lib/api/network", () => ({ fetchNetworkStatus: vi.fn() }));

const fetchMock = vi.mocked(fetchNetworkStatus);

function status(
  data: Partial<NetworkStatus> = {},
  overrides: Parameters<typeof meta>[0] = {},
): ApiEnvelope<NetworkStatus> {
  return {
    data: { vehiclesTracked: 1816, routesReporting: 184, activeAlerts: 11, ...data },
    meta: meta(overrides),
  };
}

describe("the network status card on the home page", () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it("shows the counts with thousands separated, and says how old the feed is", async () => {
    fetchMock.mockResolvedValue(status());
    render(<HomeNetworkStatus />);

    expect(await screen.findByText("1,816")).toBeInTheDocument();
    expect(screen.getByText("Vehicles tracked")).toBeInTheDocument();
    expect(screen.getByText("184")).toBeInTheDocument();
    expect(screen.getByText("Routes reporting")).toBeInTheDocument();
    expect(screen.getByText(/30s/)).toBeInTheDocument();
  });

  it("links the alert count to the alerts", async () => {
    fetchMock.mockResolvedValue(status({ activeAlerts: 11 }));
    render(<HomeNetworkStatus />);

    expect(await screen.findByRole("link", { name: "11" })).toHaveAttribute("href", "/alerts");
  });

  it("shows a measured zero as zero", async () => {
    fetchMock.mockResolvedValue(status({ activeAlerts: 0 }));
    render(<HomeNetworkStatus />);

    expect(await screen.findByRole("link", { name: "0" })).toBeInTheDocument();
  });

  it("says alerts are not counted when the count is unknown, never zero", async () => {
    fetchMock.mockResolvedValue(status({ activeAlerts: null }));
    render(<HomeNetworkStatus />);

    expect(await screen.findByText("Not counted")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("says nothing has been recorded rather than showing a row of zeros", async () => {
    fetchMock.mockResolvedValue(
      status({ vehiclesTracked: 0, routesReporting: 0, activeAlerts: null }, { source: "NO_DATA", ageSeconds: null, feedTimestamp: null }),
    );
    render(<HomeNetworkStatus />);

    expect(await screen.findByText("No vehicle positions have been recorded yet")).toBeInTheDocument();
    expect(screen.queryByText("Vehicles tracked")).not.toBeInTheDocument();
  });

  it("flags the data as degraded when a fallback served it", async () => {
    fetchMock.mockResolvedValue(status({}, { degraded: true, source: "CACHED" }));
    render(<HomeNetworkStatus />);

    await screen.findByText("1,816");
    expect(screen.getByText(/30s/).closest("span")).toHaveClass("text-stale");
  });

  it("shows an error, not empty counts, when the request fails", async () => {
    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));
    render(<HomeNetworkStatus />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Network status could not be loaded");
  });

  it("shows a loading state first", () => {
    fetchMock.mockReturnValue(new Promise(() => undefined));
    render(<HomeNetworkStatus />);

    expect(screen.getByText("Loading network status")).toBeInTheDocument();
  });
});
