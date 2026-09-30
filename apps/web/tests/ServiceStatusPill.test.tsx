import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ServiceStatusPill } from "@/components/ui/ServiceStatusPill";
import { SERVICE_STATUSES, SERVICE_STATUS_PRESENTATION } from "@/lib/transit/serviceStatus";

describe("ServiceStatusPill", () => {
  it.each(SERVICE_STATUSES)("renders a visible text label for %s", (status) => {
    const { container } = render(<ServiceStatusPill status={status} />);

    expect(screen.getByText(SERVICE_STATUS_PRESENTATION[status].label)).toBeInTheDocument();

    // Colour is never the only signal: each status also carries a glyph.
    expect(container.querySelector("svg")).toBeInTheDocument();
  });

  it("gives every status a distinct glyph shape", () => {
    const shapes = SERVICE_STATUSES.map((status) => {
      const { container } = render(<ServiceStatusPill status={status} />);
      return container.querySelector("svg")?.innerHTML ?? "";
    });

    expect(new Set(shapes).size).toBe(SERVICE_STATUSES.length);
  });

  it("describes an unmeasured route as not measured rather than as a fault", () => {
    render(<ServiceStatusPill status="UNKNOWN" />);

    expect(screen.getByText("Not measured")).toBeInTheDocument();
    expect(screen.getByText(/not enough live data/i)).toBeInTheDocument();
  });

  it("announces the supplied reason instead of the generic description", () => {
    render(<ServiceStatusPill status="UNSTABLE" reason="Headways vary by 62%" />);

    expect(screen.getByText(/Headways vary by 62%/)).toBeInTheDocument();
  });
});
