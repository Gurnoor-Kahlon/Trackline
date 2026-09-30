import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { Skeleton, SkeletonLines } from "@/components/ui/Skeleton";

describe("EmptyState", () => {
  it("states the reason without implying a failure", () => {
    render(
      <EmptyState title="No vehicles on this route" description="No service is scheduled right now." />,
    );

    expect(screen.getByText("No vehicles on this route")).toBeInTheDocument();
    // Nothing to show is not an alert; a real failure is.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("ErrorState", () => {
  it("announces the failure immediately and surfaces the reference", () => {
    render(<ErrorState title="Could not load routes" errorId="cd5f3b94" />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("cd5f3b94")).toBeInTheDocument();
  });
});

describe("Skeleton", () => {
  it("tells assistive technology that something is loading", () => {
    render(<Skeleton label="Loading network status" />);

    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Loading network status")).toBeInTheDocument();
  });

  it("renders the requested number of placeholder lines", () => {
    render(<SkeletonLines lines={4} label="Loading routes" />);

    expect(screen.getAllByRole("status")).toHaveLength(4);
  });
});
