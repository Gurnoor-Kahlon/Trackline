"use client";

import { RouteAlerts } from "@/components/alerts/RouteAlerts";
import { FavouriteButton } from "@/components/favourites/FavouriteButton";
import { ReliabilitySection } from "@/components/health/ReliabilitySection";
import { RouteBunchingSection } from "@/components/health/RouteBunchingSection";
import { RouteHealthSection } from "@/components/health/RouteHealthSection";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { RouteBadge } from "@/components/ui/RouteBadge";
import { SkeletonLines } from "@/components/ui/Skeleton";
import type { AlertsResult } from "@/lib/alerts/useAlerts";
import type { RouteBunching, RouteDetail, RouteHealth, RouteStop } from "@/lib/api/types";
import { cn } from "@/lib/cn";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";

type RouteDetailPanelProps = {
  route: RouteDetail | null;
  stops: RouteStop[];
  directionId: number;
  onDirectionChange: (directionId: number) => void;
  selectedStopId: string | null;
  onStopSelect: (stopId: string) => void;
  loading: boolean;
  error: string | null;
  /**
   * Vehicles the live stream holds for this route, or null before the first snapshot
   * has arrived. Not the same as zero: null means not known yet, zero means known to be
   * none running.
   */
  liveVehicles?: number | null | undefined;
  /** False once the stream has dropped, so the count is last-known rather than live. */
  liveVehiclesCurrent?: boolean | undefined;
  /** Alerts naming this route. Omitted where the page has none to give. */
  alerts?: AlertsResult | undefined;
  /** The route's health. Omitted where the page has none to give. */
  health?: PolledResult<RouteHealth> | undefined;
  /** Where the route's vehicles are and which are bunched. Omitted where the page has none to give. */
  bunching?: PolledResult<RouteBunching> | undefined;
  /** Whether to show how steady the route has been over time. Off where the page does not fetch history. */
  showReliability?: boolean | undefined;
};

/** GTFS wheelchair_boarding, stated exactly as published and never inferred. */
const ACCESSIBILITY_LABEL: Record<number, string | null> = {
  0: null,
  1: "Accessible",
  2: "Not accessible",
};

/**
 * The selected route: its directions, and the stops it serves in order.
 *
 * The stop list is the keyboard-reachable equivalent of clicking a marker, and it
 * carries what a marker cannot — the stop's name, its position along the route,
 * and whatever the agency has published about its accessibility.
 */
export function RouteDetailPanel({
  route,
  stops,
  directionId,
  onDirectionChange,
  selectedStopId,
  onStopSelect,
  loading,
  error,
  liveVehicles = null,
  liveVehiclesCurrent = true,
  alerts,
  health,
  bunching,
  showReliability = false,
}: RouteDetailPanelProps) {
  if (error !== null) {
    return <ErrorState title="Could not load this route" description={error} />;
  }

  if (route === null) {
    // Distinguished deliberately: nothing selected is a state of the page, while
    // a pending request is a state of the network, and they read differently.
    return loading ? (
      <SkeletonLines lines={5} label="Loading route" />
    ) : (
      <EmptyState
        title="No route selected"
        description="Choose a route to draw it on the map and list the stops it serves."
      />
    );
  }

  const direction = route.directions.find((candidate) => candidate.directionId === directionId);

  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex items-start gap-3">
        <RouteBadge
          number={route.shortName}
          mode={route.mode === "OTHER" ? "BUS" : route.mode}
          colour={route.colour}
          textColour={route.textColour}
          size="lg"
        />
        <div className="min-w-0">
          <h2 className="text-base font-semibold text-ink">{route.longName}</h2>
          <p className="text-sm text-ink-muted">{direction?.headsign ?? "Direction not stated"}</p>
          <FavouriteButton
            kind="routes"
            id={route.routeId}
            name={`route ${route.shortName}`}
            signInReturnTo={`/map?route=${encodeURIComponent(route.routeId)}`}
            className="-ml-2 mt-1"
          />
        </div>
      </div>

      {route.directions.length > 1 ? (
        <div className="flex gap-1.5" role="group" aria-label="Direction">
          {route.directions.map((candidate) => (
            <Button
              key={candidate.directionId}
              variant={candidate.directionId === directionId ? "primary" : "secondary"}
              aria-pressed={candidate.directionId === directionId}
              onClick={() => onDirectionChange(candidate.directionId)}
              className="min-w-0 flex-1"
            >
              <span className="truncate">
                {candidate.headsign ?? `Direction ${candidate.directionId}`}
              </span>
            </Button>
          ))}
        </div>
      ) : null}

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-ink-muted">Length</dt>
        <dd className="tabular text-ink">
          {direction?.lengthMetres == null
            ? "Not known"
            : `${(direction.lengthMetres / 1000).toFixed(1)} km`}
        </dd>
        <dt className="text-ink-muted">Stops</dt>
        <dd className="tabular text-ink">{direction?.stopCount ?? stops.length}</dd>
        <dt className="text-ink-muted">Live vehicles</dt>
        {/* Said plainly, because an empty map on Line 1 is otherwise read as a fault. */}
        <dd className="tabular text-ink">
          {route.supportsRealtime ? describeLiveVehicles(liveVehicles, liveVehiclesCurrent) : "Not published"}
        </dd>
      </dl>

      {health === undefined ? null : (
        <RouteHealthSection
          result={health}
          headsign={(id) => route.directions.find((candidate) => candidate.directionId === id)?.headsign ?? null}
        />
      )}

      {bunching === undefined ? null : (
        <RouteBunchingSection
          result={bunching}
          headsign={(id) => route.directions.find((candidate) => candidate.directionId === id)?.headsign ?? null}
        />
      )}

      {/* Only where vehicles are published: nothing is ever recorded for a subway line. */}
      {showReliability && route.supportsRealtime ? <ReliabilitySection routeId={route.routeId} /> : null}

      {alerts === undefined ? null : <RouteAlerts result={alerts} />}

      <div className="flex min-h-0 flex-1 flex-col">
        <h3 className="mb-1 text-sm font-semibold tracking-wide text-ink-muted uppercase">Stops</h3>
        {loading ? (
          <SkeletonLines lines={6} label="Loading stops" />
        ) : stops.length === 0 ? (
          <EmptyState
            title="No stops listed"
            description="The schedule has no stop pattern for this direction."
          />
        ) : (
          <div className="min-h-0 flex-1 overflow-y-auto">
            <ol aria-label="Stops in order">
              {stops.map((stop) => {
                const selected = stop.stopId === selectedStopId;
                const accessibility = ACCESSIBILITY_LABEL[stop.wheelchairBoarding] ?? null;
                return (
                  <li key={stop.stopId} className="flex items-center gap-1">
                    <button
                      type="button"
                      // The full name is already the button's accessible name;
                      // this is for the sighted reader, whose view of it the
                      // panel's width truncates.
                      title={stop.name}
                      aria-current={selected ? "true" : undefined}
                      onClick={() => onStopSelect(stop.stopId)}
                      className={cn(
                        "flex min-w-0 flex-1 items-baseline gap-2 rounded-md px-2 py-1.5 text-left text-sm",
                        selected
                          ? "bg-accent-quiet text-ink"
                          : "text-ink-muted hover:bg-surface-inset hover:text-ink",
                      )}
                    >
                      <span className="tabular w-6 shrink-0 text-xs text-ink-subtle">
                        {stop.sequence}
                      </span>
                      <span className="min-w-0 flex-1 truncate">{stop.name}</span>
                      {accessibility === null ? null : (
                        <span className="shrink-0 text-xs text-ink-subtle">{accessibility}</span>
                      )}
                    </button>
                    {/* A sibling of the row's button and not inside it: a button cannot hold a button. Nothing for a visitor. */}
                    <FavouriteButton kind="stops" id={stop.stopId} name={stop.name} compact />
                  </li>
                );
              })}
            </ol>
          </div>
        )}
      </div>
    </div>
  );
}

/** What the live count says. Zero is a finding; null is the absence of one. */
function describeLiveVehicles(count: number | null, current: boolean): string {
  if (count === null) {
    return "Connecting…";
  }
  if (!current) {
    return `${count} last known`;
  }
  if (count === 0) {
    return "None reporting now";
  }
  return `${count} tracked now`;
}
