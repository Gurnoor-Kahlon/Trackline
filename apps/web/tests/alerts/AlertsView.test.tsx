import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AlertsView } from "@/components/alerts/AlertsView";
import { fetchAlerts } from "@/lib/api/alerts";
import { ApiError } from "@/lib/api/client";

import { alert, envelope, route, SERVER_NOW, unknownRoute } from "./helpers";

vi.mock("@/lib/api/alerts", () => ({ fetchAlerts: vi.fn() }));

const fetchMock = vi.mocked(fetchAlerts);

/** Answers the active query and the ended query separately, as the page asks them. */
function serve(active: ReturnType<typeof envelope>, ended: ReturnType<typeof envelope> = envelope([])) {
  fetchMock.mockImplementation((query) => Promise.resolve(query?.active === false ? ended : active));
}

describe("the alerts page", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    // The ages are measured against the server's clock, which the helpers fix.
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(SERVER_NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("says what the feed does not publish, before any alert", async () => {
    serve(envelope([alert()]));
    render(<AlertsView />);

    expect(screen.getByText(/publishes no start or end time/)).toBeInTheDocument();
    expect(screen.getByText(/neither a cause nor an effect/)).toBeInTheDocument();
    await screen.findByText(/Dundas St E/);
  });

  it("shows a loading state, then the alerts", async () => {
    serve(envelope([alert()]));
    render(<AlertsView />);

    expect(screen.getByText("Loading alerts")).toBeInTheDocument();
    expect(await screen.findByText(/Dundas St E/)).toBeInTheDocument();
  });

  it("counts the alerts and says how old the feed read is", async () => {
    serve(envelope([alert(), alert()]));
    render(<AlertsView />);

    expect(await screen.findByText(/2 active alerts/)).toBeInTheDocument();
    expect(screen.getByText(/feed read 20s ago/)).toBeInTheDocument();
  });

  it("does not group when the TTC states one effect for all, because one heading implies structure that is not there", async () => {
    serve(envelope([alert({ effect: "UNKNOWN_EFFECT" }), alert({ effect: "UNKNOWN_EFFECT" })]));
    render(<AlertsView />);

    await screen.findByText(/2 active alerts/);
    expect(screen.queryByRole("heading", { level: 2 })).not.toBeInTheDocument();
  });

  it("groups by effect once the TTC states more than one", async () => {
    serve(
      envelope([
        alert({ effect: "DETOUR", description: "A detour" }),
        alert({ effect: "NO_SERVICE", description: "Nothing running" }),
      ]),
    );
    render(<AlertsView />);

    const headings = await screen.findAllByRole("heading", { level: 2 });
    expect(headings.map((heading) => heading.textContent)).toEqual(["No service (1)", "Detour (1)"]);
  });

  describe("when there is nothing to list", () => {
    it("says alerts have never been ingested, which is not the same as there being none", async () => {
      serve(envelope([], { meta: { source: "NO_DATA", feedTimestamp: null, ageSeconds: null } }));
      render(<AlertsView />);

      expect(await screen.findByText("Alerts have not been ingested yet")).toBeInTheDocument();
      expect(screen.queryByText("No active alerts")).not.toBeInTheDocument();
    });

    it("says there are none when the feed was read and carries none", async () => {
      serve(envelope([]));
      render(<AlertsView />);

      expect(await screen.findByText("No active alerts")).toBeInTheDocument();
    });
  });

  describe("when it goes wrong", () => {
    it("says the API could not be reached, with its reference when it gave one", async () => {
      fetchMock.mockRejectedValue(new ApiError("The Trackline API could not be reached.", 0));
      render(<AlertsView />);

      expect(await screen.findByRole("alert")).toHaveTextContent("Alerts could not be loaded");
    });

    it("quotes the API's own message and reference for an error it answered with", async () => {
      fetchMock.mockRejectedValue(new ApiError("Database unavailable", 500, "abc-123"));
      render(<AlertsView />);

      expect(await screen.findByText("Database unavailable")).toBeInTheDocument();
      expect(screen.getByText("abc-123")).toBeInTheDocument();
    });

    it("warns that the list may be out of date when the API says the feed is stale", async () => {
      serve(envelope([alert()], { meta: { stale: true, ageSeconds: 900 } }));
      render(<AlertsView />);

      expect(await screen.findByText(/may be out of date/)).toBeInTheDocument();
    });
  });

  describe("filters", () => {
    const streetcar = alert({
      description: "Streetcar detour",
      routes: [route("501", { mode: "STREETCAR", longName: "Queen" })],
    });
    const bus = alert({ description: "Bus elevator", routes: [route("7", { mode: "BUS", longName: "Bathurst" })] });
    const orphan = alert({ description: "Orphan alert", routes: [unknownRoute("ZZ-999")] });

    it("narrows by mode and says how many matched", async () => {
      const user = userEvent.setup();
      serve(envelope([streetcar, bus, orphan]));
      render(<AlertsView />);
      await screen.findByText(/3 active alerts/);

      await user.click(screen.getByRole("button", { name: "Streetcar" }));

      expect(screen.getByText("Streetcar detour")).toBeInTheDocument();
      expect(screen.queryByText("Bus elevator")).not.toBeInTheDocument();
      expect(screen.getByText(/1 of 3 active alerts match/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Streetcar" })).toHaveAttribute("aria-pressed", "true");
    });

    it("narrows by route number or name", async () => {
      const user = userEvent.setup();
      serve(envelope([streetcar, bus, orphan]));
      render(<AlertsView />);
      await screen.findByText(/3 active alerts/);

      await user.type(screen.getByLabelText("Route"), "bathurst");

      expect(screen.getByText("Bus elevator")).toBeInTheDocument();
      expect(screen.queryByText("Streetcar detour")).not.toBeInTheDocument();
    });

    it("says nothing matches, and that the alerts exist, when a filter removes them all", async () => {
      const user = userEvent.setup();
      serve(envelope([streetcar]));
      render(<AlertsView />);
      await screen.findByText(/1 active alert/);

      await user.click(screen.getByRole("button", { name: "Subway" }));

      expect(screen.getByText("No alerts match")).toBeInTheDocument();
      expect(screen.queryByText("No active alerts")).not.toBeInTheDocument();
    });

    it("lets a mode be switched off again", async () => {
      const user = userEvent.setup();
      serve(envelope([streetcar, bus]));
      render(<AlertsView />);
      await screen.findByText(/2 active alerts/);

      await user.click(screen.getByRole("button", { name: "Bus" }));
      await user.click(screen.getByRole("button", { name: "Bus" }));

      expect(screen.getByText("Streetcar detour")).toBeInTheDocument();
      expect(screen.getByText("Bus elevator")).toBeInTheDocument();
    });
  });

  describe("ended alerts", () => {
    it("are not fetched until someone asks for them", async () => {
      serve(envelope([alert()]));
      render(<AlertsView />);
      await screen.findByText(/Dundas St E/);

      expect(fetchMock.mock.calls.every(([query]) => query?.active === true)).toBe(true);
    });

    it("are fetched and listed, marked as ended, when asked for", async () => {
      const user = userEvent.setup();
      serve(
        envelope([alert({ description: "Still here" })]),
        envelope([
          alert({
            description: "Already gone",
            status: "RESOLVED",
            resolvedAt: "2026-10-12T11:30:00Z",
            lastSeenAt: "2026-10-12T11:29:00Z",
          }),
        ]),
      );
      render(<AlertsView />);
      await screen.findByText("Still here");

      await user.click(screen.getByRole("button", { name: "Ended" }));

      expect(await screen.findByText("Already gone")).toBeInTheDocument();
      expect(screen.queryByText("Still here")).not.toBeInTheDocument();
      expect(screen.getByText(/1 ended alert/)).toBeInTheDocument();
      expect(screen.getByText(/gone from the feed 30m ago/)).toBeInTheDocument();
    });

    it("say so plainly when none have left the feed yet", async () => {
      const user = userEvent.setup();
      serve(envelope([alert()]), envelope([]));
      render(<AlertsView />);
      await screen.findByText(/Dundas St E/);

      await user.click(screen.getByRole("button", { name: "Ended" }));

      expect(await screen.findByText("No ended alerts")).toBeInTheDocument();
    });
  });

  it("shows the active count on its tab", async () => {
    serve(envelope([alert(), alert(), alert()]));
    render(<AlertsView />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Active (3)" })).toBeInTheDocument());
  });

  it("says when only the newest alerts are shown", async () => {
    serve(envelope([alert(), alert()], { total: 250 }));
    render(<AlertsView />);

    expect(await screen.findByText(/250 active alerts, showing the newest 2/)).toBeInTheDocument();
  });

  it("keeps the filter controls reachable by their labels", async () => {
    serve(envelope([alert()]));
    render(<AlertsView />);
    await screen.findByText(/Dundas St E/);

    const modes = screen.getByRole("group", { name: "Mode" });
    expect(within(modes).getAllByRole("button").map((button) => button.textContent)).toEqual([
      "Bus",
      "Streetcar",
      "Subway",
    ]);
    expect(screen.getByLabelText("Route")).toHaveAttribute("type", "search");
  });
});
