import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AppHeader } from "@/components/shell/AppHeader";
import { SearchTrigger } from "@/components/shell/SearchTrigger";
import { SkipLink } from "@/components/shell/SkipLink";
import { searchTransit } from "@/lib/api/search";

vi.mock("@/lib/api/search", () => ({ searchTransit: vi.fn(), fetchStopDetail: vi.fn(), SEARCH_RESULT_LIMIT: 15 }));

beforeEach(() => {
  vi.mocked(searchTransit).mockReset();
});

describe("SkipLink", () => {
  it("is the first thing a keyboard user reaches", async () => {
    const user = userEvent.setup();
    render(
      <>
        <SkipLink />
        <AppHeader />
      </>,
    );

    await user.tab();

    // Ahead of the wordmark and navigation, so the header is skippable on every
    // page rather than something to tab through repeatedly.
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveFocus();
  });

  it("points at the main landmark", () => {
    render(<SkipLink />);

    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
  });
});

describe("AppHeader", () => {
  it("exposes a named navigation landmark", () => {
    render(<AppHeader />);

    expect(screen.getByRole("navigation", { name: "Primary" })).toBeInTheDocument();
  });

  it("links home from the wordmark", () => {
    render(<AppHeader />);

    expect(screen.getByRole("link", { name: /TRACKLINE/ })).toHaveAttribute("href", "/");
  });
});

describe("SearchTrigger", () => {
  it("opens with the slash shortcut", async () => {
    const user = userEvent.setup();
    render(<SearchTrigger />);

    await user.keyboard("/");

    expect(screen.getByRole("heading", { name: "Search" })).toBeInTheDocument();
  });

  it("opens a search box, and asks the API nothing until something is typed", async () => {
    const user = userEvent.setup();
    render(<SearchTrigger />);

    await user.click(screen.getByRole("button", { name: /route, stop, or station/i }));

    expect(screen.getByRole("searchbox", { name: "Search routes and stops" })).toBeInTheDocument();
    expect(searchTransit).not.toHaveBeenCalled();
  });

  it("closes when a result is followed", async () => {
    vi.mocked(searchTransit).mockResolvedValue([{ kind: "ROUTE", id: "501", label: "501", sublabel: "Queen", score: 1 }]);
    const user = userEvent.setup();
    render(<SearchTrigger />);
    await user.keyboard("/");
    await user.type(screen.getByRole("searchbox"), "501");

    await user.click(await screen.findByRole("link", { name: /501\s*Queen/ }));

    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
  });

  it("does not hijack the slash key while typing in a field", async () => {
    const user = userEvent.setup();
    render(
      <>
        <input aria-label="Some field" />
        <SearchTrigger />
      </>,
    );

    await user.click(screen.getByLabelText("Some field"));
    await user.keyboard("/");

    expect(screen.queryByRole("heading", { name: "Search" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Some field")).toHaveValue("/");
  });
});
