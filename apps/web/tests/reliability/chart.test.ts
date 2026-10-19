import { describe, expect, it } from "vitest";

import {
  describeCoverage,
  describeSlots,
  drawn,
  explainShortfall,
  formatHour,
  formatShare,
  formatVariation,
  hourSlots,
  weekdaySlots,
} from "@/lib/reliability/chart";

import { NEEDS, coverage, hour, insufficient, weekday } from "./reliabilityHelpers";

describe("formatting a share", () => {
  it.each([
    [0, "0%"],
    [1, "100%"],
    [0.5, "50%"],
    [0.667, "66.7%"],
    [0.0504, "5%"],
    [0.0451, "4.5%"],
  ])("writes %s as %s", (share, text) => {
    expect(formatShare(share)).toBe(text);
  });

  it("says a missing share is not available and never zero", () => {
    expect(formatShare(null)).toBe("not available");
  });

  it("writes a variation as a whole percentage", () => {
    expect(formatVariation(0.304)).toBe("30%");
    expect(formatVariation(null)).toBe("not available");
  });
});

describe("naming an hour", () => {
  it.each([
    [0, "12 am"],
    [1, "1 am"],
    [11, "11 am"],
    [12, "12 pm"],
    [17, "5 pm"],
    [23, "11 pm"],
  ])("hour %i is %s", (value, text) => {
    expect(formatHour(value)).toBe(text);
  });
});

describe("the hour-of-day axis", () => {
  it("always has twenty-four slots, in order", () => {
    const slots = hourSlots([hour(18, 0.5)], NEEDS);

    expect(slots).toHaveLength(24);
    expect(slots.map((slot) => slot.key)).toEqual(Array.from({ length: 24 }, (_, index) => index));
  });

  it("gives an hour with enough behind it its share and what it stands on", () => {
    const slot = hourSlots([hour(18, 0.5, 4)], NEEDS)[18]!;

    expect(slot.value).toBe(0.5);
    expect(slot.longLabel).toBe("6 pm to 7 pm");
    expect(slot.detail).toBe("50% steady, from 240 judged samples over 4 days");
  });

  it("gives an hour without enough no value, and says what it needed", () => {
    const slot = hourSlots([], NEEDS)[3]!;

    expect(slot.value).toBeNull();
    expect(slot.detail).toBe("Not enough yet: needs 60 judged samples from at least 3 different days");
  });

  it("keeps a real zero as zero and not as missing", () => {
    const slot = hourSlots([hour(8, 0)], NEEDS)[8]!;

    expect(slot.value).toBe(0);
    expect(drawn([slot])).toBe(1);
  });

  it("wraps the last hour's range to midnight", () => {
    expect(hourSlots([], NEEDS)[23]!.longLabel).toBe("11 pm to 12 am");
  });
});

describe("the day-of-week axis", () => {
  it("starts on Monday and has seven slots", () => {
    const slots = weekdaySlots([weekday(2, 0.7)], NEEDS);

    expect(slots.map((slot) => slot.label)).toEqual(["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]);
    expect(slots[1]!.value).toBe(0.7);
    expect(slots[0]!.value).toBeNull();
  });

  it("states the days a weekday needs, not an hour's", () => {
    expect(weekdaySlots([], NEEDS)[0]!.detail).toContain("at least 2 different days");
  });
});

describe("describing a chart in a sentence", () => {
  it("says when nothing can be drawn", () => {
    expect(describeSlots("By hour", hourSlots([], NEEDS))).toBe(
      "By hour: no bar has enough behind it to be drawn yet.",
    );
  });

  it("names the steadiest and least steady bars", () => {
    const text = describeSlots("By hour", hourSlots([hour(8, 0.9), hour(17, 0.4), hour(12, 0.7)], NEEDS));

    expect(text).toBe(
      "By hour: 3 of 24 bars drawn; steadiest 8 am to 9 am at 90%, least steady 5 pm to 6 pm at 40%.",
    );
  });

  it("does not pretend a single bar is a range", () => {
    expect(describeSlots("By hour", hourSlots([hour(8, 0.9)], NEEDS))).toBe(
      "By hour: 1 of 24 bars drawn; 8 am to 9 am is 90% steady.",
    );
  });
});

describe("saying how much history there is", () => {
  it("counts samples, hours and days for a window", () => {
    expect(describeCoverage(coverage({ samples: 4320, hoursCovered: 72, days: 3 }), 7)).toBe(
      "In the last 7 days: 4,320 samples from 72 hours on 3 days.",
    );
  });

  it("uses the singular where there is one", () => {
    expect(describeCoverage(coverage({ samples: 1, hoursCovered: 1, days: 1 }), 1)).toBe(
      "In the last 24 hours: 1 sample from 1 hour on 1 day.",
    );
  });

  it("says how many thin hours were left out", () => {
    expect(describeCoverage(coverage({ hoursExcluded: 1 }), 7)).toContain("1 hour with too few samples is left out");
    expect(describeCoverage(coverage({ hoursExcluded: 3 }), 7)).toContain("3 hours with too few samples are left out");
  });
});

describe("explaining why there is no figure", () => {
  it("counts hours against the hours needed", () => {
    const shortfall = explainShortfall(
      insufficient("TOO_FEW_HOURS", { coverage: coverage({ hoursCovered: 9 }) }),
    );

    expect(shortfall.headline).toBe("Not enough history yet");
    expect(shortfall.progress).toEqual({ have: 9, need: 24, unit: "hours recorded" });
    expect(shortfall.detail).toContain("24 hours have at least 20 samples each");
    expect(shortfall.detail).toContain("The first hour recorded for this route starts");
  });

  it("counts judged samples when the hours are there but the verdicts are not", () => {
    const shortfall = explainShortfall(
      insufficient("TOO_FEW_JUDGED_SAMPLES", { coverage: coverage({ hoursCovered: 30, judgedSamples: 100 }) }),
    );

    expect(shortfall.progress).toEqual({ have: 100, need: 240, unit: "judged samples" });
    expect(shortfall.detail).toContain("30 hours");
    expect(shortfall.detail).toContain("at least two vehicles");
  });

  it("has nothing to count when nothing was recorded", () => {
    const shortfall = explainShortfall(
      insufficient("NO_SAMPLES", { coverage: coverage({ hoursCovered: 0, firstHour: null, lastHour: null }) }),
    );

    expect(shortfall.headline).toBe("Nothing summarised yet");
    expect(shortfall.progress).toBeNull();
    expect(shortfall.detail).not.toContain("first hour recorded");
  });

  it("carries the needs figure through unchanged", () => {
    const shortfall = explainShortfall(
      insufficient("TOO_FEW_HOURS", { needs: { ...NEEDS, coveredHours: 48 } }),
    );

    expect(shortfall.progress?.need).toBe(48);
  });
});
