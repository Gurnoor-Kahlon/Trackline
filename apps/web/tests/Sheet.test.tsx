import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Sheet } from "@/components/ui/Sheet";

describe("Sheet", () => {
  it("is labelled by its visible title", () => {
    render(
      <Sheet open onClose={() => {}} title="Route 501 Queen">
        <p>Body</p>
      </Sheet>,
    );

    expect(screen.getByRole("dialog")).toHaveAccessibleName("Route 501 Queen");
  });

  it("stays closed until asked to open", () => {
    render(
      <Sheet open={false} onClose={() => {}} title="Filters">
        <p>Body</p>
      </Sheet>,
    );

    const dialog = screen.getByRole("dialog", { hidden: true }) as HTMLDialogElement;
    expect(dialog.open).toBe(false);
  });

  it("reports a close request from the close button", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Sheet open onClose={onClose} title="Filters">
        <p>Body</p>
      </Sheet>,
    );

    await user.click(screen.getByRole("button", { name: "Close" }));

    expect(onClose).toHaveBeenCalled();
  });

  it("reports a close request when the dialog is closed programmatically", () => {
    const onClose = vi.fn();
    render(
      <Sheet open onClose={onClose} title="Filters">
        <p>Body</p>
      </Sheet>,
    );

    screen.getByRole("dialog").dispatchEvent(new Event("close"));

    expect(onClose).toHaveBeenCalled();
  });

  it("reports a close request when the user presses Escape", () => {
    const onClose = vi.fn();
    render(
      <Sheet open onClose={onClose} title="Filters">
        <p>Body</p>
      </Sheet>,
    );

    // Escape fires "cancel", and Chromium emits no "close" after it. Listening
    // only for "close" left the parent believing the sheet was still open, so it
    // could never be reopened — caught in a real browser, not here.
    screen.getByRole("dialog").dispatchEvent(new Event("cancel"));

    expect(onClose).toHaveBeenCalled();
  });
});
