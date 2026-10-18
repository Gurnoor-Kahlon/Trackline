import type {
  DirectionHealth,
  HealthConfidence,
  HealthMetrics,
  HealthReason,
  RouteHealth,
} from "@/lib/api/types";

/**
 * Putting a route's verdict into words.
 *
 * The API returns codes and figures, never sentences, so every claim made here is composed
 * from numbers the reader can also see in the details. Nothing is said that a figure does not
 * support, and a figure the API could not compute is left out, never stated as zero.
 */

export function formatDistance(metres: number): string {
  if (metres < 1000) {
    return `${Math.round(metres / 10) * 10} m`;
  }
  return `${(metres / 1000).toFixed(1)} km`;
}

function percent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`;
}

function times(ratio: number): string {
  return `${ratio.toFixed(1)} times`;
}

export function confidenceLabel(confidence: HealthConfidence): string | null {
  switch (confidence) {
    case "HIGH":
      return "High confidence";
    case "MEDIUM":
      return "Medium confidence";
    case "LOW":
      return "Low confidence";
    case "NONE":
      return null;
  }
}

/**
 * The direction that decided a route's verdict: the one whose state the route took, and
 * among equals the one the route's own reasons and confidence were copied from.
 */
export function decisiveDirection(health: RouteHealth): DirectionHealth | null {
  const sameState = health.directions.filter((direction) => direction.state === health.state);
  return (
    sameState.find(
      (direction) =>
        direction.confidence === health.confidence &&
        direction.reasons.length === health.reasons.length &&
        direction.reasons.every((reason, index) => reason === health.reasons[index]),
    ) ??
    sameState[0] ??
    null
  );
}

export type Explanation = {
  /** One sentence saying what the verdict rests on. */
  headline: string;
  /** What weakens it or limits what it can show. */
  caveats: string[];
  /** The direction the headline describes, or null when there is no verdict. */
  directionId: number | null;
};

const NO_VERDICT: Partial<Record<HealthReason, string>> = {
  NO_REALTIME_FEED: "The TTC publishes no vehicle positions for this route, so spacing cannot be judged.",
  INSUFFICIENT_VEHICLES: "Fewer than two vehicles with a known position are running, so spacing cannot be judged.",
  NOT_ANALYSED_YET: "Route health has not been calculated yet.",
};

const CAVEAT: Partial<Record<HealthReason, (metrics: HealthMetrics | null) => string>> = {
  FEW_VEHICLES: (metrics) =>
    metrics === null ? "Based on very few vehicles." : `Based on only ${metrics.vehicles} vehicles.`,
  STALE_VEHICLES: () => "Many of the vehicles have not reported recently and were left out.",
  NO_SCHEDULE_COVERAGE: () =>
    "The imported timetable does not cover today, so spacing was judged against itself. A route whose every gap is too wide cannot be seen this way.",
  NO_SCHEDULED_HEADWAY: () =>
    "The timetable gives no spacing for this route at this hour, so spacing was judged against itself.",
  SPEED_UNKNOWN: () =>
    "How fast vehicles travel on this route is still being measured, so the timetable could not be compared.",
};

/** Order caveats are shown in, since a reason list is in the order the calculator found them. */
const CAVEAT_ORDER: readonly HealthReason[] = [
  "FEW_VEHICLES",
  "STALE_VEHICLES",
  "NO_SCHEDULE_COVERAGE",
  "NO_SCHEDULED_HEADWAY",
  "SPEED_UNKNOWN",
];

export function explainHealth(health: RouteHealth): Explanation {
  const decisive = decisiveDirection(health);
  const caveats: string[] = [];

  if (decisive === null) {
    // No direction had a verdict. The reasons say why, and the first with a stated meaning leads.
    const lead = health.reasons.find((reason) => NO_VERDICT[reason] !== undefined);
    if (health.unplacedVehicles > 0) {
      caveats.push(unplaced(health.unplacedVehicles));
    }
    return {
      headline:
        (lead !== undefined ? NO_VERDICT[lead] : undefined) ?? "There is not enough live data to judge this route.",
      caveats,
      directionId: null,
    };
  }

  const metrics = decisive.metrics;
  for (const reason of CAVEAT_ORDER) {
    if (decisive.reasons.includes(reason)) {
      caveats.push(CAVEAT[reason]?.(metrics) ?? "");
    }
  }
  if (health.unplacedVehicles > 0) {
    caveats.push(unplaced(health.unplacedVehicles));
  }

  return { headline: measuredClauses(decisive), caveats: caveats.filter((text) => text !== ""), directionId: decisive.directionId };
}

function unplaced(count: number): string {
  return `${count} vehicle${count === 1 ? "" : "s"} on this route could not be placed in a direction and ${count === 1 ? "is" : "are"} not counted.`;
}

/** "Spacing varies by 62%, and the longest gap is 2.1 times the expected spacing of 3.0 km." */
function measuredClauses(direction: DirectionHealth): string {
  const metrics = direction.metrics;
  const clauses: string[] = [];

  if (metrics.variation !== null) {
    clauses.push(`spacing varies by ${percent(metrics.variation)}`);
  }
  if (metrics.gapRatio !== null) {
    clauses.push(
      metrics.basis === "SCHEDULE" && metrics.expectedGapMetres !== null
        ? `the longest gap is ${times(metrics.gapRatio)} the expected spacing of ${formatDistance(metrics.expectedGapMetres)}`
        : `the longest gap is ${times(metrics.gapRatio)} the average gap`,
    );
  }
  if (direction.reasons.includes("FEWER_VEHICLES_THAN_SCHEDULED") && metrics.supplyRatio !== null) {
    clauses.push(`only ${percent(metrics.supplyRatio)} of the vehicles the timetable calls for are running`);
  }
  if (direction.reasons.includes("BUNCHING") && metrics.bunchingClusters > 0) {
    clauses.push(
      metrics.bunchingClusters === 1
        ? "one group of vehicles is running together"
        : `${metrics.bunchingClusters} groups of vehicles are running together`,
    );
  }

  if (clauses.length === 0) {
    return "Spacing was measured, but too few vehicles for a figure to be stated.";
  }
  const sentence = clauses.length === 1 ? clauses[0]! : `${clauses.slice(0, -1).join(", ")}, and ${clauses.at(-1)}`;
  return `${sentence.charAt(0).toUpperCase()}${sentence.slice(1)}.`;
}

export type FigureRow = { label: string; value: string };

const NOT_MEASURED = "Not measured";

/**
 * Every figure behind one direction's verdict, for the reader who wants to check it.
 * Absent figures say so, since "0" would be a claim.
 */
export function directionFigures(metrics: HealthMetrics): FigureRow[] {
  const rows: FigureRow[] = [
    { label: "Vehicles measured", value: String(metrics.vehicles) },
    { label: "Average gap", value: metrics.meanGapMetres === null ? NOT_MEASURED : formatDistance(metrics.meanGapMetres) },
    { label: "Longest gap", value: metrics.maxGapMetres === null ? NOT_MEASURED : formatDistance(metrics.maxGapMetres) },
    { label: "Spacing varies by", value: metrics.variation === null ? NOT_MEASURED : percent(metrics.variation) },
    {
      label: metrics.basis === "SCHEDULE" ? "Expected spacing (timetable)" : "Expected spacing",
      value:
        metrics.expectedGapMetres === null
          ? "No timetable comparison"
          : formatDistance(metrics.expectedGapMetres),
    },
    {
      label: "Vehicles expected",
      value: metrics.expectedVehicles === null ? NOT_MEASURED : String(Math.round(metrics.expectedVehicles)),
    },
    {
      label: "Timetable spacing",
      value:
        metrics.scheduledHeadwaySeconds === null
          ? NOT_MEASURED
          : `${Math.round(metrics.scheduledHeadwaySeconds / 60)} min`,
    },
    {
      label: "Measured speed",
      value:
        metrics.speedMetresPerSecond === null
          ? NOT_MEASURED
          : `${(metrics.speedMetresPerSecond * 3.6).toFixed(1)} km/h`,
    },
  ];
  rows.push({ label: "Groups running together", value: String(metrics.bunchingClusters) });
  if (metrics.staleVehicles > 0) {
    rows.push({ label: "Left out: not reporting", value: String(metrics.staleVehicles) });
  }
  if (metrics.terminalVehicles > 0) {
    rows.push({ label: "Left out: at a terminal", value: String(metrics.terminalVehicles) });
  }
  return rows;
}
