import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { AppHeader } from "@/components/shell/AppHeader";
import { SearchTrigger } from "@/components/shell/SearchTrigger";
import { SkipLink } from "@/components/shell/SkipLink";

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

  it("says plainly that search is not available yet", async () => {
    const user = userEvent.setup();
    render(<SearchTrigger />);

    await user.click(screen.getByRole("button", { name: /route, stop, or station/i }));

    // An input that silently returns nothing would imply the feature works and
    // that Toronto has no matching routes.
    expect(screen.getByText(/Search is not available yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
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
