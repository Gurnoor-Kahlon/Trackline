import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { RouteBadge } from "@/components/ui/RouteBadge";

/** RouteBadge renders a single root element, which is the badge itself. */
function renderBadge(element: React.ReactElement): HTMLElement {
  const { container } = render(element);
  return container.firstElementChild as HTMLElement;
}

describe("RouteBadge", () => {
  it("shows the route number and announces the mode", () => {
    render(<RouteBadge number="501" mode="STREETCAR" colour="DA251D" textColour="FFFFFF" />);

    // "501" alone does not say whether it is a streetcar or a bus, and the colour
    // that conveys that visually is invisible to a screen reader.
    expect(screen.getByText(/Streetcar route/)).toBeInTheDocument();
    expect(screen.getByText(/501/)).toBeInTheDocument();
  });

  it("uses the colours the feed supplied when they are legible", () => {
    const badge = renderBadge(<RouteBadge number="41" mode="BUS" colour="003DA5" textColour="FFFFFF" />);

    expect(badge).toHaveStyle({ backgroundColor: "rgb(0, 61, 165)", color: "rgb(255, 255, 255)" });
  });

  it("substitutes a readable foreground when the feed's text colour does not contrast", () => {
    // Yellow background with the feed claiming white text is unreadable, so black
    // is used instead. A route number that cannot be read is worse than one drawn
    // slightly off-brand.
    const badge = renderBadge(<RouteBadge number="7" mode="BUS" colour="FFEE00" textColour="FFFFFF" />);

    expect(badge).toHaveStyle({ backgroundColor: "rgb(255, 238, 0)", color: "rgb(0, 0, 0)" });
  });

  it("falls back to neutral tokens when the feed gives no usable colour", () => {
    const badge = renderBadge(<RouteBadge number="900" mode="BUS" colour={null} />);

    // Asserted against the inline style, because a CSS custom property has no
    // computed value in jsdom.
    expect(badge.getAttribute("style")).toContain("var(--color-surface-inset)");
    expect(badge.getAttribute("style")).toContain("var(--color-ink)");
  });

  it("ignores a malformed colour rather than emitting broken CSS", () => {
    const badge = renderBadge(<RouteBadge number="510" mode="STREETCAR" colour="not-a-colour" />);

    expect(badge.getAttribute("style")).toContain("var(--color-surface-inset)");
    expect(badge.getAttribute("style")).not.toContain("not-a-colour");
  });
});
