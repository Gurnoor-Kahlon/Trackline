import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { ThemeToggle } from "@/components/shell/ThemeToggle";

describe("ThemeToggle", () => {
  it("switches the document theme and remembers the choice", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);

    const toggle = await screen.findByRole("button", { name: /switch to dark theme/i });
    await user.click(toggle);

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem("trackline-theme")).toBe("dark");
    expect(screen.getByRole("button", { name: /switch to light theme/i })).toBeInTheDocument();
  });

  it("switches back", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);

    const toggle = await screen.findByRole("button", { name: /switch to dark theme/i });
    await user.click(toggle);
    await user.click(screen.getByRole("button", { name: /switch to light theme/i }));

    expect(document.documentElement.dataset.theme).toBe("light");
    expect(localStorage.getItem("trackline-theme")).toBe("light");
  });

  it("starts from the theme already applied to the document", async () => {
    document.documentElement.dataset.theme = "dark";
    render(<ThemeToggle />);

    // The blocking script runs before React, so the button must reflect what is
    // on screen rather than assuming light.
    expect(await screen.findByRole("button", { name: /switch to light theme/i })).toBeInTheDocument();
  });

  it("exposes the current state to assistive technology", async () => {
    render(<ThemeToggle />);

    const toggle = await screen.findByRole("button", { name: /switch to dark theme/i });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
  });
});
