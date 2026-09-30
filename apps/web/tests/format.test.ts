import { describe, expect, it } from "vitest";

import { describeAge, formatAge, isStale, STALE_AFTER_SECONDS } from "@/lib/format/age";
import { contrastRatio, routeColours } from "@/lib/format/colour";

describe("formatAge", () => {
  it("never rounds an age away", () => {
    expect(formatAge(0)).toBe("0s");
    expect(formatAge(0.9)).toBe("0s");
    expect(formatAge(59)).toBe("59s");
  });

  it("switches units at the right boundaries", () => {
    expect(formatAge(60)).toBe("1m");
    expect(formatAge(3599)).toBe("59m");
    expect(formatAge(3600)).toBe("1h");
    expect(formatAge(3900)).toBe("1h 5m");
  });

  it("clamps a negative age rather than printing nonsense", () => {
    // A vehicle timestamp slightly ahead of our clock is normal clock skew.
    expect(formatAge(-5)).toBe("0s");
  });
});

describe("describeAge", () => {
  it("agrees in number", () => {
    expect(describeAge(1)).toBe("Updated 1 second ago");
    expect(describeAge(2)).toBe("Updated 2 seconds ago");
    expect(describeAge(60)).toBe("Updated 1 minute ago");
    expect(describeAge(7200)).toBe("Updated 2 hours ago");
  });
});

describe("isStale", () => {
  it("matches the API's threshold exactly at the boundary", () => {
    expect(isStale(STALE_AFTER_SECONDS)).toBe(false);
    expect(isStale(STALE_AFTER_SECONDS + 1)).toBe(true);
  });
});

describe("contrastRatio", () => {
  it("computes the known extremes", () => {
    expect(contrastRatio("ffffff", "000000")).toBeCloseTo(21, 1);
    expect(contrastRatio("ffffff", "ffffff")).toBeCloseTo(1, 5);
  });
});

describe("routeColours", () => {
  it("keeps the feed's pairing when it is legible", () => {
    expect(routeColours("DA251D", "FFFFFF")).toEqual({
      background: "#da251d",
      foreground: "#ffffff",
    });
  });

  it("overrides an illegible pairing the feed supplied", () => {
    expect(routeColours("FFEE00", "FFFFFF").foreground).toBe("#000000");
  });

  it("chooses a foreground when the feed supplies none", () => {
    expect(routeColours("003DA5", null).foreground).toBe("#ffffff");
    expect(routeColours("FFEE00", undefined).foreground).toBe("#000000");
  });

  it("falls back to neutral tokens for missing or malformed input", () => {
    const fallback = { background: "var(--color-surface-inset)", foreground: "var(--color-ink)" };
    expect(routeColours(null, null)).toEqual(fallback);
    expect(routeColours("", null)).toEqual(fallback);
    expect(routeColours("#DA251D", null)).toEqual(fallback);
    expect(routeColours("DA251", null)).toEqual(fallback);
  });
});
