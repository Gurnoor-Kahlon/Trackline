import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SearchPanel } from "@/components/search/SearchPanel";
import { ApiError } from "@/lib/api/client";
import { fetchStopDetail, searchTransit } from "@/lib/api/search";
import type { RouteSummary, SearchHit, StopDetail } from "@/lib/api/types";

vi.mock("@/lib/api/search", () => ({
  searchTransit: vi.fn(),
  fetchStopDetail: vi.fn(),
  SEARCH_RESULT_LIMIT: 15,
}));

const searchMock = vi.mocked(searchTransit);
const stopMock = vi.mocked(fetchStopDetail);

const route = (id: string, longName: string): SearchHit => ({ kind: "ROUTE", id, label: id, sublabel: longName, score: 1 });
const stop = (id: string, name: string, code: string | null): SearchHit => ({
  kind: "STOP",
  id,
  label: name,
  sublabel: code,
  score: 0.8,
});
const station: SearchHit = { kind: "STATION", id: "7", label: "Kennedy Station", sublabel: null, score: 0.9 };

const queen: RouteSummary = {
  routeId: "501",
  shortName: "501",
  longName: "Queen",
  mode: "STREETCAR",
  colour: "DA251D",
  textColour: "FFFFFF",
  supportsRealtime: true,
};

function stopDetail(routes: RouteSummary[]): StopDetail {
  return {
    stop: {
      stopId: "S1",
      name: "Kennedy Rd at Lawrence Ave",
      code: "1234",
      latitude: 43.7,
      longitude: -79.3,
      wheelchairBoarding: 0,
      stationId: null,
      stationName: null,
    },
    routes,
  };
}

async function typeInto(text: string) {
  const user = userEvent.setup();
  await user.type(screen.getByRole("searchbox", { name: "Search routes and stops" }), text);
  return user;
}

describe("the search panel", () => {
  beforeEach(() => {
    searchMock.mockReset();
    stopMock.mockReset();
  });

  it("starts with a hint and asks the API nothing", () => {
    render(<SearchPanel onNavigate={() => undefined} />);

    expect(screen.getByRole("searchbox", { name: "Search routes and stops" })).toHaveAttribute("data-autofocus");
    expect(screen.getByRole("status")).toHaveTextContent("Type a route number or a name.");
    expect(searchMock).not.toHaveBeenCalled();
  });

  it("lists routes and stops under their own headings, and says how many", async () => {
    searchMock.mockResolvedValue([route("501", "Queen"), stop("S1", "Kennedy Rd at Lawrence Ave", "1234")]);
    render(<SearchPanel onNavigate={() => undefined} />);

    await typeInto("queen");

    const routes = await screen.findByRole("region", { name: "Routes" });
    expect(within(routes).getByRole("link", { name: /501\s*Queen/ })).toBeInTheDocument();
    const stops = screen.getByRole("region", { name: "Stops" });
    expect(within(stops).getByRole("button", { name: /Kennedy Rd at Lawrence Ave/ })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("2 results for queen.");
  });

  it("takes a route to the map with that route selected", async () => {
    searchMock.mockResolvedValue([route("501", "Queen")]);
    render(<SearchPanel onNavigate={() => undefined} />);

    await typeInto("501");

    expect(await screen.findByRole("link", { name: /501\s*Queen/ })).toHaveAttribute("href", "/map?route=501");
  });

  it("tells the sheet it has been followed, so the sheet can close", async () => {
    const onNavigate = vi.fn();
    searchMock.mockResolvedValue([route("501", "Queen")]);
    render(<SearchPanel onNavigate={onNavigate} />);
    const user = await typeInto("501");

    await user.click(await screen.findByRole("link", { name: /501\s*Queen/ }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it("leaves stations out, since nothing opens one, and counts only what is shown", async () => {
    searchMock.mockResolvedValue([station, route("41", "Keele")]);
    render(<SearchPanel onNavigate={() => undefined} />);

    await typeInto("kennedy");

    await screen.findByRole("region", { name: "Routes" });
    expect(screen.queryByText("Kennedy Station")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("1 result for kennedy.");
  });

  it("says plainly when nothing matches, as a finding and not a fault", async () => {
    searchMock.mockResolvedValue([]);
    render(<SearchPanel onNavigate={() => undefined} />);

    await typeInto("zzzz");

    expect(await screen.findByText("No routes or stops match")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No results for zzzz.");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says, in words, when the API asks it to slow down", async () => {
    searchMock.mockRejectedValue(new ApiError("Too many requests from this address.", 429));
    render(<SearchPanel onNavigate={() => undefined} />);

    await typeInto("queen");

    expect(await screen.findByRole("alert")).toHaveTextContent("Wait a moment and try again.");
  });

  it("opens a stop to show the routes that call there, each leading to the map", async () => {
    searchMock.mockResolvedValue([stop("S1", "Kennedy Rd at Lawrence Ave", "1234")]);
    stopMock.mockResolvedValue(stopDetail([queen]));
    render(<SearchPanel onNavigate={() => undefined} />);
    const user = await typeInto("kennedy");

    const button = await screen.findByRole("button", { name: /Kennedy Rd at Lawrence Ave/ });
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(stopMock).not.toHaveBeenCalled();
    await user.click(button);

    expect(button).toHaveAttribute("aria-expanded", "true");
    const link = await screen.findByRole("link", { name: /Queen/ });
    expect(link).toHaveAttribute("href", "/map?route=501");
    expect(stopMock).toHaveBeenCalledWith("S1", expect.any(AbortSignal));
  });

  it("asks for a stop's routes once however often it is opened and closed", async () => {
    searchMock.mockResolvedValue([stop("S1", "Kennedy Rd at Lawrence Ave", "1234")]);
    stopMock.mockResolvedValue(stopDetail([queen]));
    render(<SearchPanel onNavigate={() => undefined} />);
    const user = await typeInto("kennedy");
    const button = await screen.findByRole("button", { name: /Kennedy Rd at Lawrence Ave/ });

    await user.click(button);
    await screen.findByRole("link", { name: /Queen/ });
    await user.click(button);
    await user.click(button);

    expect(stopMock).toHaveBeenCalledTimes(1);
  });

  it("says so when no imported route calls at a stop, and when the routes could not be loaded", async () => {
    searchMock.mockResolvedValue([stop("S1", "Kennedy Rd at Lawrence Ave", "1234"), stop("S2", "Kennedy Station", null)]);
    stopMock.mockResolvedValueOnce(stopDetail([])).mockRejectedValueOnce(new ApiError("The API returned 500.", 500));
    render(<SearchPanel onNavigate={() => undefined} />);
    const user = await typeInto("kennedy");

    await user.click(await screen.findByRole("button", { name: /Kennedy Rd at Lawrence Ave/ }));
    expect(await screen.findByText("No route in the imported schedule calls here.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Kennedy Station/ }));
    await waitFor(() => expect(screen.getByText("Could not load the routes that call here.")).toBeInTheDocument());
  });
});
