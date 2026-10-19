import type {
  ReliabilityCoverage,
  ReliabilityFigures,
  ReliabilityHour,
  ReliabilityNeeds,
  ReliabilityWeekday,
  ReliabilityWindowDays,
  RouteReliability,
} from "@/lib/api/types";

/** The windows the API offers, in the order they are shown. */
export const WINDOWS: { days: ReliabilityWindowDays; label: string }[] = [
  { days: 1, label: "24 hours" },
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
];

export const DEFAULT_WINDOW: ReliabilityWindowDays = 7;

const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

/**
 * One bar's slot on a breakdown axis.
 *
 * `value` is null when too little stands behind the bar to draw it. That is a different thing from
 * zero: a bar at zero says every sample was unsteady, and a missing one says nothing was measured
 * well enough to say.
 */
export type BarSlot = {
  key: number;
  /** Short, for the axis. */
  label: string;
  /** In full, for the readout and the table. */
  longLabel: string;
  value: number | null;
  /** What stands behind the bar, or what it lacks. */
  detail: string;
};

/** A share as a percentage, to a tenth where that says something: 67%, 66.7%, 0%. */
export function formatShare(share: number | null): string {
  if (share === null) {
    return "not available";
  }
  const percent = Math.round(share * 1000) / 10;
  return `${Number.isInteger(percent) ? percent : percent.toFixed(1)}%`;
}

/** "5 pm". */
export function formatHour(hour: number): string {
  const suffix = hour < 12 ? "am" : "pm";
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return `${twelve} ${suffix}`;
}

function plural(count: number, one: string, many = `${one}s`): string {
  return `${count.toLocaleString("en-CA")} ${count === 1 ? one : many}`;
}

/** What the bar stands on, in words. */
function standingOn(figures: ReliabilityFigures, days: number): string {
  return `${formatShare(figures.steadyShare)} steady, from ${plural(figures.judged, "judged sample")} over ${plural(days, "day")}`;
}

function lacking(needs: ReliabilityNeeds, minDays: number): string {
  return `Not enough yet: needs ${needs.cellSamples.toLocaleString("en-CA")} judged samples from at least ${plural(minDays, "different day")}`;
}

/** Twenty-four slots, one for each hour of the Toronto day; an hour without enough behind it has no value. */
export function hourSlots(cells: ReliabilityHour[], needs: ReliabilityNeeds): BarSlot[] {
  const byHour = new Map(cells.map((cell) => [cell.hour, cell]));
  return Array.from({ length: 24 }, (_, hour) => {
    const cell = byHour.get(hour);
    const longLabel = `${formatHour(hour)} to ${formatHour((hour + 1) % 24)}`;
    return {
      key: hour,
      label: formatHour(hour),
      longLabel,
      value: cell?.figures.steadyShare ?? null,
      detail: cell === undefined ? lacking(needs, needs.daysPerHour) : standingOn(cell.figures, cell.days),
    };
  });
}

/** Seven slots, Monday first; a day without enough behind it has no value. */
export function weekdaySlots(cells: ReliabilityWeekday[], needs: ReliabilityNeeds): BarSlot[] {
  const byDay = new Map(cells.map((cell) => [cell.isoWeekday, cell]));
  return WEEKDAYS.map((name, index) => {
    const cell = byDay.get(index + 1);
    return {
      key: index + 1,
      label: name.slice(0, 3),
      longLabel: name,
      value: cell?.figures.steadyShare ?? null,
      detail: cell === undefined ? lacking(needs, needs.daysPerWeekday) : standingOn(cell.figures, cell.days),
    };
  });
}

/** How many of a set of slots have a bar. */
export function drawn(slots: BarSlot[]): number {
  return slots.filter((slot) => slot.value !== null).length;
}

/** The slots as one sentence, for a screen reader reading the picture. */
export function describeSlots(title: string, slots: BarSlot[]): string {
  const withBars = slots.filter((slot) => slot.value !== null);
  if (withBars.length === 0) {
    return `${title}: no bar has enough behind it to be drawn yet.`;
  }
  const best = withBars.reduce((a, b) => ((b.value ?? 0) > (a.value ?? 0) ? b : a));
  const worst = withBars.reduce((a, b) => ((b.value ?? 1) < (a.value ?? 1) ? b : a));
  const range =
    best.key === worst.key
      ? `${best.longLabel} is ${formatShare(best.value)} steady`
      : `steadiest ${best.longLabel} at ${formatShare(best.value)}, least steady ${worst.longLabel} at ${formatShare(worst.value)}`;
  return `${title}: ${withBars.length} of ${slots.length} bars drawn; ${range}.`;
}

const DATE = new Intl.DateTimeFormat("en-CA", {
  timeZone: "America/Toronto",
  weekday: "short",
  month: "short",
  day: "numeric",
});

const DATE_TIME = new Intl.DateTimeFormat("en-CA", {
  timeZone: "America/Toronto",
  weekday: "short",
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
});

export function formatDay(iso: string): string {
  return DATE.format(new Date(iso));
}

export function formatMoment(iso: string): string {
  return DATE_TIME.format(new Date(iso));
}

/** How much history stands behind the figures, in a sentence. */
export function describeCoverage(coverage: ReliabilityCoverage, windowDays: ReliabilityWindowDays): string {
  const window = windowDays === 1 ? "the last 24 hours" : `the last ${windowDays} days`;
  const base =
    `In ${window}: ${plural(coverage.samples, "sample")} from ${plural(coverage.hoursCovered, "hour")} ` +
    `on ${plural(coverage.days, "day")}`;
  const left =
    coverage.hoursExcluded === 0
      ? ""
      : `. ${plural(coverage.hoursExcluded, "hour")} with too few samples ${coverage.hoursExcluded === 1 ? "is" : "are"} left out`;
  return `${base}${left}.`;
}

/** What is lacking, with the counts that show how far off it is. */
export type Shortfall = {
  headline: string;
  /** Null when there is nothing to count against yet. */
  progress: { have: number; need: number; unit: string } | null;
  detail: string;
};

export function explainShortfall(data: RouteReliability): Shortfall {
  const { needs, coverage } = data;
  const since =
    coverage.firstHour === null
      ? ""
      : ` The first hour recorded for this route starts ${formatMoment(coverage.firstHour)}, Toronto time.`;

  switch (data.reason) {
    case "TOO_FEW_HOURS":
      return {
        headline: "Not enough history yet",
        progress: { have: coverage.hoursCovered, need: needs.coveredHours, unit: "hours recorded" },
        detail:
          `Reliability is shown once ${needs.coveredHours} hours have at least ${needs.samplesPerHour} samples each, ` +
          `so that a figure is not drawn from an afternoon.${since}`,
      };
    case "TOO_FEW_JUDGED_SAMPLES":
      return {
        headline: "Recorded, but too rarely judged",
        progress: { have: coverage.judgedSamples, need: needs.judgedSamples, unit: "judged samples" },
        detail:
          `This route has been recorded for ${plural(coverage.hoursCovered, "hour")}, but too few of its samples ` +
          `reached a verdict. A verdict needs at least two vehicles tracked on a direction at once.${since}`,
      };
    default:
      return {
        headline: "Nothing summarised yet",
        progress: null,
        detail:
          "Route health is recorded about once a minute while vehicles are being tracked on a route, and each hour " +
          "is summarised once it has finished. A route recorded for the first time appears after the hour ends.",
      };
  }
}

/** The headway variation in words: 0.3 is "varies by 30%". */
export function formatVariation(variation: number | null): string {
  return variation === null ? "not available" : `${Math.round(variation * 100)}%`;
}
