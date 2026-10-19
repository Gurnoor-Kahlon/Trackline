"use client";

import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";

import { LiveStatusChip } from "@/components/map/LiveStatusChip";
import { RouteDetailPanel } from "@/components/map/RouteDetailPanel";
import { RouteFilterPanel } from "@/components/map/RouteFilterPanel";
import { Button } from "@/components/ui/Button";
import { Sheet } from "@/components/ui/Sheet";
import { useAlerts } from "@/lib/alerts/useAlerts";
import { useRouteBunching, useRouteHealth } from "@/lib/health/useHealth";
import { ApiError } from "@/lib/api/client";
import { fetchRoute, fetchRouteShape, fetchRouteStops } from "@/lib/api/routes";
import type { RouteDetail, RouteShape, RouteStop, RouteSummary } from "@/lib/api/types";
import { useLiveVehicles } from "@/lib/live/useLiveVehicles";
import { useNetworkStatus } from "@/lib/live/useNetworkStatus";
import { EMPTY_FILTER, type RouteFilter } from "@/lib/map/routeFilter";

/**
 * MapLibre is loaded only in the browser.
 *
 * It reaches for `window` and a WebGL context as it initialises, so rendering it
 * on the server would fail. It is also by far the largest thing on the page, and
 * keeping it out of the first payload means the route list is usable before the
 * map has finished arriving.
 */
const MapCanvas = dynamic(
  () => import("@/components/map/MapCanvas").then((module) => module.MapCanvas),
  {
    ssr: false,
    loading: () => (
      <div className="flex size-full items-center justify-center bg-surface-inset">
        <p className="text-sm text-ink-muted">Loading map…</p>
      </div>
    ),
  },
);

/**
 * How much geometry detail to discard.
 *
 * 15 m is under a pixel at the zoom levels a whole route is viewed at, so the
 * line looks identical while the payload is a fraction of the size.
 */
const SIMPLIFY_METRES = 15;

/**
 * A fetched result, labelled with the selection it belongs to.
 *
 * The label is the point. Each result is kept with the route and direction it
 * describes, and read back only when that still matches what is selected, so a
 * reply that arrives after the user has moved on is discarded by construction
 * rather than by a chain of effects racing to clear it.
 */
type RouteResult = { routeId: string; detail: RouteDetail };
type GeometryResult = {
  routeId: string;
  directionId: number;
  shape: RouteShape;
  stops: RouteStop[];
};
type Failure = { routeId: string; directionId: number | null; message: string };

const NO_STOPS: RouteStop[] = [];

type MapExplorerProps = {
  routes: RouteSummary[];
  /** A route to start with selected, from a link such as one on a saved-routes list. Ignored if it is not in `routes`. */
  initialRouteId?: string | undefined;
};

/**
 * The map page's state and layout.
 *
 * Two panels either side of the map on a desktop; below that breakpoint the map
 * takes the full width and the panels move into a bottom sheet, because a 375 px
 * column split three ways is useless for all three.
 */
export function MapExplorer({ routes, initialRouteId }: MapExplorerProps) {
  const [filter, setFilter] = useState<RouteFilter>(EMPTY_FILTER);
  // A link can name the route to open with. One the schedule does not have is dropped, so a stale link opens the map.
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(() =>
    initialRouteId !== undefined && routes.some((candidate) => candidate.routeId === initialRouteId)
      ? initialRouteId
      : null,
  );
  const [directionId, setDirectionId] = useState(0);
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);

  // The vehicles shown follow the selection: the whole fleet until a route is chosen,
  // then just that route. Changing the route opens a new subscription, so the server
  // does the filtering and the page is never sent vehicles it would only discard.
  const liveStore = useLiveVehicles(selectedRouteId === null ? NO_ROUTES : [selectedRouteId]);
  const network = useNetworkStatus();
  const routeColours = useMemo(() => colourByRoute(routes), [routes]);
  // The health of the selected route. Nothing is fetched while none is selected.
  const routeHealth = useRouteHealth(selectedRouteId);
  const routeBunching = useRouteBunching(selectedRouteId);
  // The alerts that name the selected route. Nothing is fetched while none is selected.
  const routeAlerts = useAlerts(
    selectedRouteId === null
      ? { active: true }
      : { active: true, route: selectedRouteId, size: ROUTE_ALERT_COUNT },
    selectedRouteId !== null,
  );
  const liveFeed = useMemo(() => ({ store: liveStore, routeColours }), [liveStore, routeColours]);

  // How many vehicles the stream holds, or null until the first snapshot has arrived.
  // A number, so the page re-renders when the count changes and not for every event.
  const liveVehicleCount = useSyncExternalStore(
    liveStore.subscribe,
    () => {
      const live = liveStore.getState();
      return live.lastEventId === null ? null : live.vehicles.size;
    },
    () => null,
  );
  // Whether those vehicles are being kept current: after the stream drops, the count is
  // what was last known and must not be worded as though it were live.
  const liveConnected = useSyncExternalStore(
    liveStore.subscribe,
    () => liveStore.getState().connection === "open",
    () => false,
  );

  const [routeResult, setRouteResult] = useState<RouteResult | null>(null);
  const [geometryResult, setGeometryResult] = useState<GeometryResult | null>(null);
  const [routeFailure, setRouteFailure] = useState<Failure | null>(null);
  const [geometryFailure, setGeometryFailure] = useState<Failure | null>(null);

  // What is on screen: whatever has been fetched for the current selection, and
  // nothing otherwise. Derived rather than stored, so there is no moment at which
  // one route's geometry sits under another route's name.
  const route = routeResult?.routeId === selectedRouteId ? routeResult.detail : null;
  const geometry =
    geometryResult !== null &&
    geometryResult.routeId === selectedRouteId &&
    geometryResult.directionId === directionId
      ? geometryResult
      : null;
  const shape = geometry?.shape ?? null;
  const stops = geometry?.stops ?? NO_STOPS;

  const error =
    (routeFailure?.routeId === selectedRouteId ? routeFailure.message : null) ??
    (geometryFailure?.routeId === selectedRouteId && geometryFailure.directionId === directionId
      ? geometryFailure.message
      : null);

  // Something is in flight exactly when a selection has produced neither data nor
  // an answer yet. A route whose schedule serves no direction at all is excluded:
  // no geometry is coming for it, and reporting it as loading forever would be a
  // spinner that never resolves.
  const loading =
    error === null &&
    selectedRouteId !== null &&
    (route === null || (hasDirection(route, directionId) && geometry === null));

  // The route and its directions. Fetched before any geometry, because which
  // directions exist decides which geometry it is even valid to ask for.
  useEffect(() => {
    if (selectedRouteId === null) {
      return;
    }

    // Abandoning the previous request matters here: arrowing down a list of
    // routes starts one request per route, and without this the slowest reply
    // wins and the map settles on a route the user has already moved past.
    const controller = new AbortController();

    fetchRoute(selectedRouteId, controller.signal)
      .then((detail) => {
        if (controller.signal.aborted) {
          return;
        }
        setRouteResult({ routeId: selectedRouteId, detail });
        // Direction 0 is not guaranteed to exist. A one-way loop route can
        // publish only direction 1, and asking for 0 would be a 404 presented to
        // the user as a failure when nothing has actually gone wrong.
        setDirectionId((current) =>
          hasDirection(detail, current) ? current : (detail.directions[0]?.directionId ?? 0),
        );
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setRouteFailure({ routeId: selectedRouteId, directionId: null, message: describe(cause) });
        }
      });

    return () => controller.abort();
  }, [selectedRouteId]);

  // The geometry and stops for the chosen direction.
  useEffect(() => {
    // The effect above is about to replace an unserved direction with one that
    // exists; requesting this one would only produce a 404 on the way there.
    if (route === null || !hasDirection(route, directionId)) {
      return;
    }

    const routeId = route.routeId;
    const controller = new AbortController();

    Promise.all([
      fetchRouteShape(routeId, directionId, SIMPLIFY_METRES, controller.signal),
      fetchRouteStops(routeId, directionId, controller.signal),
    ])
      .then(([routeShape, routeStops]) => {
        if (!controller.signal.aborted) {
          setGeometryResult({ routeId, directionId, shape: routeShape, stops: routeStops });
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setGeometryFailure({ routeId, directionId, message: describe(cause) });
        }
      });

    return () => controller.abort();
  }, [route, directionId]);

  const selectRoute = useCallback((routeId: string) => {
    setSelectedRouteId(routeId);
    setSelectedStopId(null);
    setDirectionId(0);
  }, []);

  const changeDirection = useCallback((nextDirectionId: number) => {
    setDirectionId(nextDirectionId);
    // The two directions of a route do not serve the same stop ids, so keeping
    // the selection would highlight a stop that is no longer in the list.
    setSelectedStopId(null);
  }, []);

  const selectedSummary = routes.find((candidate) => candidate.routeId === selectedRouteId) ?? null;

  const detailPanel = (
    <RouteDetailPanel
      route={route}
      stops={stops}
      directionId={directionId}
      onDirectionChange={changeDirection}
      selectedStopId={selectedStopId}
      onStopSelect={setSelectedStopId}
      loading={loading}
      error={error}
      liveVehicles={selectedRouteId === null ? null : liveVehicleCount}
      liveVehiclesCurrent={liveConnected}
      alerts={selectedRouteId === null ? undefined : routeAlerts}
      health={selectedRouteId === null ? undefined : routeHealth}
      bunching={selectedRouteId === null ? undefined : routeBunching}
      showReliability
    />
  );

  const filterPanel = (onSelect: (routeId: string) => void) => (
    <RouteFilterPanel
      routes={routes}
      filter={filter}
      onFilterChange={setFilter}
      selectedRouteId={selectedRouteId}
      onRouteSelect={onSelect}
    />
  );

  // 14rem is the measured height of everything above this row — the sticky
  // header, the page padding and the heading block — so the map ends inside
  // the viewport rather than pushing the page into a scroll it does not need.
  return (
    <div className="flex flex-col gap-3 lg:h-[calc(100dvh-14rem)] lg:min-h-[28rem] lg:flex-row">
      <aside
        aria-label="Route filters"
        className="hidden min-h-0 w-72 shrink-0 rounded-card border border-border bg-surface-raised p-3 lg:block"
      >
        {filterPanel(selectRoute)}
      </aside>

      {/* flex-1 is applied only from lg. Below it the row is a column, where
          flex-1 would set flex-basis to 0 and collapse the map's height to
          nothing against a container that is sized by its content. */}
      <div
        role="region"
        aria-label="Route map"
        className="relative h-[55dvh] min-w-0 overflow-hidden rounded-card border border-border lg:h-auto lg:flex-1"
      >
        <MapCanvas
          shape={shape}
          stops={stops}
          routeColour={selectedSummary?.colour ?? null}
          onStopSelect={setSelectedStopId}
          selectedStopId={selectedStopId}
          vehicles={liveFeed}
        />
        {/* Over the map, not beside it, so it is seen at a glance and costs no layout. At
            the top, clear of the zoom buttons, because the bottom edge carries the basemap
            attribution that must stay readable. It passes clicks through everywhere except
            on itself. */}
        <div className="pointer-events-none absolute top-2 right-14 left-2 z-10 sm:top-3 sm:left-3">
          <LiveStatusChip store={liveStore} network={network} className="pointer-events-auto" />
        </div>
      </div>

      <aside
        aria-label="Selected route"
        className="hidden min-h-0 w-80 shrink-0 rounded-card border border-border bg-surface-raised p-3 lg:block"
      >
        {detailPanel}
      </aside>

      {/* Below the desktop breakpoint the panels move into a sheet, so the map
          keeps the width it needs to be worth showing at all. */}
      <Button variant="secondary" className="lg:hidden" onClick={() => setSheetOpen(true)}>
        {selectedSummary
          ? `Route ${selectedSummary.shortName} — change or view stops`
          : "Choose a route"}
      </Button>

      {/* Rendered only while open. The panels inside are a second copy of 236
          routes, and on a desktop, where this sheet is never opened, building
          and holding that copy buys nothing. */}
      <Sheet open={sheetOpen} onClose={() => setSheetOpen(false)} title="Routes">
        {sheetOpen ? (
          <div className="space-y-4">
            {filterPanel((routeId) => {
              selectRoute(routeId);
              // Closed on selection to uncover the map, which is the point of
              // having chosen a route. The trigger button is then the way back.
              setSheetOpen(false);
            })}
            {selectedRouteId === null ? null : (
              <div className="border-t border-border pt-4">{detailPanel}</div>
            )}
          </div>
        ) : null}
      </Sheet>
    </div>
  );
}

function hasDirection(route: RouteDetail, directionId: number): boolean {
  return route.directions.some((candidate) => candidate.directionId === directionId);
}

function describe(cause: unknown): string {
  if (cause instanceof ApiError) {
    return cause.isResponse
      ? cause.message
      : "The Trackline API could not be reached. Check that it is running.";
  }
  return "Something went wrong loading this route.";
}

/** A stable empty list, so "no route chosen" is the same value on every render. */
const NO_ROUTES: readonly string[] = [];

/** Alerts shown in the route panel; the rest are one link away on the alerts page. */
const ROUTE_ALERT_COUNT = 3;

/** Route id to its published colour, leaving out the routes that published none. */
function colourByRoute(routes: readonly RouteSummary[]): ReadonlyMap<string, string> {
  const colours = new Map<string, string>();
  for (const route of routes) {
    if (route.colour !== null) {
      colours.set(route.routeId, route.colour);
    }
  }
  return colours;
}
