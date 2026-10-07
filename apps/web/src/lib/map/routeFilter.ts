import type { RouteSummary, TransitMode } from "@/lib/api/types";

/**
 * Filtering and ordering for the route list.
 *
 * Kept out of the components so it can be tested without rendering anything, and
 * so the map and any later list view cannot drift into filtering differently.
 */

export type RouteFilter = {
  /** Modes to include. An empty set means no restriction, not "none". */
  modes: Set<TransitMode>;
  /** Free text matched against number and name. */
  query: string;
};

export const EMPTY_FILTER: RouteFilter = { modes: new Set(), query: "" };

/**
 * Applies a filter.
 *
 * An empty mode set includes everything. Treating it as "exclude all" would make
 * the list vanish the moment a user unticked their last mode, which reads as a
 * bug rather than as a filter.
 */
export function filterRoutes(routes: RouteSummary[], filter: RouteFilter): RouteSummary[] {
  const query = filter.query.trim().toLowerCase();

  return routes.filter((route) => {
    if (filter.modes.size > 0 && !filter.modes.has(route.mode)) {
      return false;
    }
    if (query.length === 0) {
      return true;
    }
    // Number and name together, so "501" and "queen" both find 501 Queen.
    return `${route.shortName} ${route.longName}`.toLowerCase().includes(query);
  });
}

/**
 * Orders routes the way riders read them.
 *
 * Route ids are text in GTFS, so the default ordering gives 1, 10, 100, 41.
 * Numbers sort numerically and anything else falls to the end alphabetically.
 */
export function sortRoutes(routes: RouteSummary[]): RouteSummary[] {
  return [...routes].sort((left, right) => {
    const leftNumber = numericName(left.shortName);
    const rightNumber = numericName(right.shortName);

    if (leftNumber !== null && rightNumber !== null) {
      return leftNumber - rightNumber;
    }
    if (leftNumber !== null) {
      return -1;
    }
    if (rightNumber !== null) {
      return 1;
    }
    return left.shortName.localeCompare(right.shortName);
  });
}

function numericName(shortName: string): number | null {
  return /^\d+$/.test(shortName) ? Number.parseInt(shortName, 10) : null;
}

/**
 * Toggles one mode in a filter, returning a new filter.
 *
 * Immutable because the filter lives in React state, where mutating a Set in
 * place would leave the component showing the previous selection.
 */
export function toggleMode(filter: RouteFilter, mode: TransitMode): RouteFilter {
  const modes = new Set(filter.modes);
  if (modes.has(mode)) {
    modes.delete(mode);
  } else {
    modes.add(mode);
  }
  return { ...filter, modes };
}
