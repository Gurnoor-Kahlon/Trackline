import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { AlertCard } from "@/components/alerts/AlertCard";
import { ROUTES_SHOWN } from "@/lib/alerts/display";

import { alert, route, SERVER_NOW, unknownRoute } from "./helpers";

function renderCard(props: Parameters<typeof AlertCard>[0]) {
  return render(
    <ul>
      <AlertCard {...props} />
    </ul>,
  );
}

describe("an alert card", () => {
  it("shows the whole description and not the publisher's clipped header", () => {
    renderCard({ alert: alert(), serverNowMs: SERVER_NOW });

    expect(screen.getByText(/Dundas St E and Broadview Ave due to construction/)).toBeInTheDocument();
    expect(screen.queryByText("506 Carlton: Detour via Bay St,")).not.toBeInTheDocument();
  });

  it("describes when it was seen without calling that a start or an end", () => {
    renderCard({ alert: alert(), serverNowMs: SERVER_NOW });

    expect(screen.getByText("First seen 2h ago · last seen 20s ago")).toBeInTheDocument();
  });

  it("marks an alert that has left the feed as ended and says when it was last there", () => {
    renderCard({
      alert: alert({ status: "RESOLVED", resolvedAt: "2026-10-12T11:30:00Z" }),
      serverNowMs: SERVER_NOW,
    });

    expect(screen.getByText("Ended")).toBeInTheDocument();
    expect(screen.getByText(/gone from the feed 30m ago/)).toBeInTheDocument();
  });

  it("does not mark an active alert as ended", () => {
    renderCard({ alert: alert(), serverNowMs: SERVER_NOW });

    expect(screen.queryByText("Ended")).not.toBeInTheDocument();
  });

  it("shows no age before the first client render, instead of one that would not match the server", () => {
    renderCard({ alert: alert(), serverNowMs: null });

    expect(screen.queryByText(/First seen/)).not.toBeInTheDocument();
  });

  describe("the routes it names", () => {
    it("shows each as the same badge the rest of the app uses, announced with its mode", () => {
      renderCard({ alert: alert({ routes: [route("501", { mode: "STREETCAR" })] }), serverNowMs: SERVER_NOW });

      expect(screen.getByText("501")).toBeInTheDocument();
      expect(screen.getByText(/Streetcar route/)).toBeInTheDocument();
    });

    it("shows a route the schedule does not know plainly, as published, and says why it is plain", () => {
      renderCard({ alert: alert({ routes: [unknownRoute("ZZ-999")] }), serverNowMs: SERVER_NOW });

      const chip = screen.getByText("ZZ-999");
      expect(chip).toHaveAttribute("title", "This route is not in the imported schedule");
    });

    it("keeps a long list short and offers the rest", () => {
      const many = Array.from({ length: 20 }, (_, index) => route(String(index + 1)));
      renderCard({ alert: alert({ routes: many }), serverNowMs: SERVER_NOW });

      const more = screen.getByText(`+${20 - ROUTES_SHOWN} more`);
      expect(more.closest("details")).not.toBeNull();
      // Every route is in the document, so a reader who opens the list loses nothing.
      expect(within(more.closest("details") as HTMLElement).getAllByText(/^\d+$/)).toHaveLength(20 - ROUTES_SHOWN);
    });

    it("offers no expansion when every route is already shown", () => {
      renderCard({ alert: alert({ routes: [route("1"), route("2")] }), serverNowMs: SERVER_NOW });

      expect(screen.queryByText(/more/)).not.toBeInTheDocument();
    });

    it("lists no routes for an alert that names none", () => {
      renderCard({ alert: alert({ routes: [] }), serverNowMs: SERVER_NOW });

      expect(screen.queryByText(/Routes named/)).not.toBeInTheDocument();
    });

    it("omits them from a compact card, which has no room", () => {
      renderCard({ alert: alert(), serverNowMs: SERVER_NOW, compact: true });

      expect(screen.queryByText("506")).not.toBeInTheDocument();
    });
  });

  it("names the stops it applies to, falling back to the id for one the schedule lacks", () => {
    renderCard({
      alert: alert({
        stops: [
          { stopId: "3718", known: true, name: "Union Station" },
          { stopId: "9999", known: false, name: null },
        ],
      }),
      serverNowMs: SERVER_NOW,
    });

    expect(screen.getByText("Union Station, Stop 9999")).toBeInTheDocument();
  });

  it("links to more information safely, in a new tab, and says so", () => {
    renderCard({ alert: alert({ url: "https://www.ttc.ca/alert" }), serverNowMs: SERVER_NOW });

    const link = screen.getByRole("link", { name: /More information/ });
    expect(link).toHaveAttribute("href", "https://www.ttc.ca/alert");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(link).toHaveTextContent("opens in a new tab");
  });

  it("has no link when the publisher gave none", () => {
    renderCard({ alert: alert({ url: null }), serverNowMs: SERVER_NOW });

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
