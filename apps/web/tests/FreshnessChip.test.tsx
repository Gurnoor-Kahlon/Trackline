import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { FreshnessChip } from "@/components/ui/FreshnessChip";

describe("FreshnessChip", () => {
  it("reports an unknown age as not measured, never as zero", () => {
    render(<FreshnessChip ageSeconds={null} />);

    expect(screen.getByText("Not measured")).toBeInTheDocument();
    expect(screen.queryByText("0s")).not.toBeInTheDocument();
  });

  it("shows a fresh age without flagging it", () => {
    render(<FreshnessChip ageSeconds={12} />);

    expect(screen.getByText("12s")).toBeInTheDocument();
    expect(screen.getByText(/Updated 12 seconds ago/)).toBeInTheDocument();
    expect(screen.queryByText(/stale/i)).not.toBeInTheDocument();
  });

  it("says so when data is stale", () => {
    render(<FreshnessChip ageSeconds={240} />);

    expect(screen.getByText("4m")).toBeInTheDocument();
    expect(screen.getByText(/This data is stale/)).toBeInTheDocument();
  });

  it("discloses a degraded source even when the data is fresh", () => {
    render(<FreshnessChip ageSeconds={5} degraded />);

    expect(screen.getByText(/Serving from a fallback source/)).toBeInTheDocument();
  });

  it("announces changes politely rather than interrupting", () => {
    const { container } = render(<FreshnessChip ageSeconds={5} />);

    expect(container.firstElementChild).toHaveAttribute("aria-live", "polite");
  });
});
