import type { AlertEffect, AlertRoute, ServiceAlert } from "@/lib/api/types";
import { formatAge } from "@/lib/format/age";
import type { TransitMode } from "@/lib/transit/mode";

/**
 * How alerts are put in front of a reader.
 *
 * All pure, so the rules that decide what a rider is told, and what the interface
 * refuses to claim, can be tested without rendering anything.
 */

/** What an alert says, for display. */
export function alertText(alert: ServiceAlert): string {
  // The publisher cuts the header off mid-word at about 31 characters, so the description
  // is the whole sentence and the header is only a fallback for an alert without one.
  return alert.description ?? alert.header ?? "The TTC published no text for this alert.";
}

/** Effects in the order a reader most needs them, then those that carry no information. */
const EFFECT_ORDER: readonly AlertEffect[] = [
  "NO_SERVICE",
  "REDUCED_SERVICE",
  "SIGNIFICANT_DELAYS",
  "DETOUR",
  "MODIFIED_SERVICE",
  "STOP_MOVED",
  "ACCESSIBILITY_ISSUE",
  "ADDITIONAL_SERVICE",
  "OTHER_EFFECT",
  "NO_EFFECT",
];

const EFFECT_LABEL: Record<AlertEffect, string> = {
  NO_SERVICE: "No service",
  REDUCED_SERVICE: "Reduced service",
  SIGNIFICANT_DELAYS: "Significant delays",
  DETOUR: "Detour",
  ADDITIONAL_SERVICE: "Additional service",
  MODIFIED_SERVICE: "Modified service",
  OTHER_EFFECT: "Other effect",
  UNKNOWN_EFFECT: "Effect not stated",
  STOP_MOVED: "Stop moved",
  NO_EFFECT: "No effect",
  ACCESSIBILITY_ISSUE: "Accessibility",
};

export type AlertGroup = {
  /** The effect the group is for, or null for alerts that state none. */
  effect: AlertEffect | null;
  label: string;
  alerts: ServiceAlert[];
};

/**
 * Groups alerts by the effect the TTC states.
 *
 * The TTC currently states none: every alert carries the specification's UNKNOWN_EFFECT.
 * That is reported as it is, in one group, and not worked out from the wording of the
 * text, because a guess presented as a category would be believed.
 */
export function groupByEffect(alerts: readonly ServiceAlert[]): AlertGroup[] {
  const groups = new Map<AlertEffect | null, ServiceAlert[]>();
  for (const alert of alerts) {
    const effect = alert.effect === null || alert.effect === "UNKNOWN_EFFECT" ? null : alert.effect;
    groups.set(effect, [...(groups.get(effect) ?? []), alert]);
  }

  const ordered: AlertGroup[] = [];
  for (const effect of EFFECT_ORDER) {
    const found = groups.get(effect);
    if (found !== undefined) {
      ordered.push({ effect, label: EFFECT_LABEL[effect], alerts: found });
    }
  }
  const unstated = groups.get(null);
  if (unstated !== undefined) {
    ordered.push({ effect: null, label: EFFECT_LABEL.UNKNOWN_EFFECT, alerts: unstated });
  }
  return ordered;
}

export type AlertFilter = {
  /** Only alerts naming a route of this mode, or null for any. */
  mode: TransitMode | null;
  /** Free text matched against the names and ids of the routes an alert names. */
  routeQuery: string;
};

export const NO_FILTER: AlertFilter = { mode: null, routeQuery: "" };

export function isFiltering(filter: AlertFilter): boolean {
  return filter.mode !== null || filter.routeQuery.trim() !== "";
}

function routeMatches(route: AlertRoute, query: string): boolean {
  return [route.routeId, route.shortName, route.longName].some(
    (field) => field !== null && field.toLowerCase().includes(query),
  );
}

export function filterAlerts(alerts: readonly ServiceAlert[], filter: AlertFilter): ServiceAlert[] {
  const query = filter.routeQuery.trim().toLowerCase();

  return alerts.filter((alert) => {
    // An id the schedule does not know has no mode, so a mode filter cannot match it.
    if (filter.mode !== null && !alert.routes.some((route) => route.mode === filter.mode)) {
      return false;
    }
    return query === "" || alert.routes.some((route) => routeMatches(route, query));
  });
}

/**
 * When Trackline saw an alert, in words that claim no more than is known.
 *
 * The feed gives no start or end, so these are observations: "first seen" is not "began",
 * and "gone from the feed" is not "ended". Ages are measured against the server's clock.
 */
export function describeObserved(alert: ServiceAlert, serverNowMs: number): string {
  const ago = (iso: string) => `${formatAge((serverNowMs - Date.parse(iso)) / 1000)} ago`;

  const parts = [`First seen ${ago(alert.firstSeenAt)}`];
  if (alert.resolvedAt === null) {
    parts.push(`last seen ${ago(alert.lastSeenAt)}`);
  } else {
    parts.push(`gone from the feed ${ago(alert.resolvedAt)}`);
  }
  return parts.join(" · ");
}

/** Whole route lists can run to dozens (an elevator alert names every route serving the station). */
export const ROUTES_SHOWN = 8;

/** How many routes a reader is left to expand, or 0 when all are shown. */
export function hiddenRouteCount(alert: ServiceAlert): number {
  return Math.max(0, alert.routes.length - ROUTES_SHOWN);
}
