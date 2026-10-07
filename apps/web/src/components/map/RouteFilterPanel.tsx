"use client";

import { useId, useMemo } from "react";

import { RouteBadge } from "@/components/ui/RouteBadge";
import { EmptyState } from "@/components/ui/EmptyState";
import type { RouteSummary, TransitMode } from "@/lib/api/types";
import { cn } from "@/lib/cn";
import { filterRoutes, sortRoutes, toggleMode, type RouteFilter } from "@/lib/map/routeFilter";

const SELECTABLE_MODES: TransitMode[] = ["SUBWAY", "STREETCAR", "BUS"];

const MODE_LABEL: Record<TransitMode, string> = {
  SUBWAY: "Subway",
  STREETCAR: "Streetcar",
  BUS: "Bus",
  OTHER: "Other",
};

type RouteFilterPanelProps = {
  routes: RouteSummary[];
  filter: RouteFilter;
  onFilterChange: (filter: RouteFilter) => void;
  selectedRouteId: string | null;
  onRouteSelect: (routeId: string) => void;
};

/**
 * Filters and the route list.
 *
 * This is the accessible path through the whole map page: every route the map can
 * draw is reachable here by keyboard, named in text, and selectable with Enter.
 * A map is a picture, and a picture cannot be the only way to use a transit site.
 */
export function RouteFilterPanel({
  routes,
  filter,
  onFilterChange,
  selectedRouteId,
  onRouteSelect,
}: RouteFilterPanelProps) {
  const searchId = useId();
  const visible = useMemo(() => sortRoutes(filterRoutes(routes, filter)), [routes, filter]);

  return (
    <div className="flex h-full flex-col gap-3">
      <div>
        <label htmlFor={searchId} className="text-sm font-medium text-ink">
          Find a route
        </label>
        <input
          id={searchId}
          type="search"
          value={filter.query}
          onChange={(event) => onFilterChange({ ...filter, query: event.target.value })}
          placeholder="501, Queen, Finch"
          className="mt-1 w-full rounded-md border border-border bg-surface-raised px-3 py-2 text-sm text-ink placeholder:text-ink-subtle"
        />
      </div>

      <fieldset>
        <legend className="text-sm font-medium text-ink">Modes</legend>
        <div className="mt-1 flex flex-wrap gap-1.5">
          {SELECTABLE_MODES.map((mode) => {
            const active = filter.modes.has(mode);
            return (
              <button
                key={mode}
                type="button"
                // aria-pressed rather than a styled checkbox: these are toggles
                // that change what is shown, not fields that get submitted.
                aria-pressed={active}
                onClick={() => onFilterChange(toggleMode(filter, mode))}
                className={cn(
                  "rounded-pill border px-3 py-1 text-sm font-medium transition-colors",
                  active
                    ? "border-accent bg-accent-quiet text-accent"
                    : "border-border bg-surface-raised text-ink-muted hover:text-ink",
                )}
              >
                {MODE_LABEL[mode]}
              </button>
            );
          })}
        </div>
      </fieldset>

      <p className="text-xs text-ink-subtle" aria-live="polite">
        {visible.length} of {routes.length} routes
      </p>

      {visible.length === 0 ? (
        <EmptyState
          title="No routes match"
          description="Try a different number or name, or clear the mode filters."
        />
      ) : (
        // min-h-0 is load-bearing: a flex item's default min-height is its
        // content, so without it 236 routes refuse to shrink, overflow the
        // panel and scroll the whole page instead of the list.
        <ul className="min-h-0 flex-1 overflow-y-auto" aria-label="Routes">
          {visible.map((route) => {
            const selected = route.routeId === selectedRouteId;
            return (
              <li key={route.routeId}>
                <button
                  type="button"
                  // aria-current marks the one being shown on the map, which is a
                  // different thing from a pressed toggle.
                  aria-current={selected ? "true" : undefined}
                  onClick={() => onRouteSelect(route.routeId)}
                  className={cn(
                    "flex w-full items-center gap-3 rounded-md px-2 py-2 text-left",
                    selected ? "bg-accent-quiet" : "hover:bg-surface-inset",
                  )}
                >
                  <RouteBadge
                    number={route.shortName}
                    mode={route.mode === "OTHER" ? "BUS" : route.mode}
                    colour={route.colour}
                    textColour={route.textColour}
                    size="sm"
                  />
                  <span className="min-w-0 flex-1 truncate text-sm text-ink">{route.longName}</span>
                  {!route.supportsRealtime ? (
                    // Stated here rather than discovered as an empty map later.
                    <span className="shrink-0 text-xs text-ink-subtle">No live data</span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
