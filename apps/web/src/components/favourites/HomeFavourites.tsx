"use client";

import Link from "next/link";

import { useAuth } from "@/components/auth/AuthProvider";
import { FavouriteButton } from "@/components/favourites/FavouriteButton";
import { useFavourites } from "@/components/favourites/FavouritesProvider";
import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { RouteBadge } from "@/components/ui/RouteBadge";
import { ServiceStatusPill } from "@/components/ui/ServiceStatusPill";
import { SkeletonLines } from "@/components/ui/Skeleton";
import type { FavouriteRoute, FavouriteStop } from "@/lib/favourites/api";
import { confidenceLabel } from "@/lib/health/explain";
import { useHealthRoutes } from "@/lib/health/useHealth";
import type { RouteHealthSummary } from "@/lib/api/types";

/**
 * What the signed-in person has saved, with how each saved route is running now.
 *
 * Shown only to someone signed in: for a visitor there is nothing to show and nothing to say, and a box inviting them
 * to sign in would sit on a page that works perfectly well without. The health beside a route is the one the rest of the
 * site shows, judged on spacing and not on delay, and a route with no vehicles being tracked says that and not "fine".
 */
export function HomeFavourites() {
  const { state } = useAuth();
  const favourites = useFavourites();
  const health = useHealthRoutes();

  if (state.status !== "authenticated") {
    return null;
  }

  const byRoute = new Map<string, RouteHealthSummary>(
    health.status === "ready" ? health.data.routes.map((route) => [route.routeId, route]) : [],
  );

  return (
    <Card as="section" ariaLabelledBy="favourites-heading">
      <CardHeader id="favourites-heading" title="Your saved routes and stops" />
      <CardBody className="pt-2">
        {favourites.status === "loading" || favourites.status === "idle" ? (
          <SkeletonLines lines={2} label="Loading your saved routes and stops" />
        ) : favourites.status === "error" || favourites.favourites === null ? (
          <p role="alert" className="text-sm font-medium text-status-major">
            {favourites.error ?? "Your saved routes and stops could not be loaded."}
          </p>
        ) : favourites.favourites.routes.length === 0 && favourites.favourites.stops.length === 0 ? (
          <EmptyState
            title="Nothing saved yet"
            description="Open a route on the map and press Save to keep it here, with how it is running."
          />
        ) : (
          <div className="space-y-4">
            {favourites.error === null ? null : (
              <p role="alert" className="text-sm font-medium text-status-major">
                {favourites.error}
              </p>
            )}
            {favourites.favourites.routes.length === 0 ? null : (
              <ul aria-label="Saved routes" className="divide-y divide-border">
                {favourites.favourites.routes.map((saved) => (
                  <SavedRoute key={saved.routeId} saved={saved} health={byRoute.get(saved.routeId)} />
                ))}
              </ul>
            )}
            {favourites.favourites.stops.length === 0 ? null : (
              <div className="space-y-1">
                <h3 className="text-xs font-semibold tracking-wide text-ink-muted uppercase">Stops</h3>
                <ul aria-label="Saved stops" className="divide-y divide-border">
                  {favourites.favourites.stops.map((saved) => (
                    <SavedStop key={saved.stopId} saved={saved} />
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </CardBody>
    </Card>
  );
}

function SavedRoute({ saved, health }: { saved: FavouriteRoute; health: RouteHealthSummary | undefined }) {
  const route = saved.route;
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
      {route === null ? (
        <>
          <span className="min-w-0 flex-1 text-sm text-ink">
            Route {saved.routeId}
            <span className="ml-2 text-xs text-ink-subtle">Not in the current schedule</span>
          </span>
        </>
      ) : (
        <>
          <RouteBadge
            number={route.shortName}
            mode={route.mode === "OTHER" ? "BUS" : route.mode}
            colour={route.colour}
            textColour={route.textColour}
            size="sm"
          />
          <Link
            href={{ pathname: "/map", query: { route: saved.routeId } }}
            className="min-w-0 flex-1 truncate text-sm text-ink underline-offset-2 hover:underline"
          >
            {route.longName}{" "}
            <span className="sr-only">on the map</span>
          </Link>
          {health === undefined ? (
            <span className="text-xs text-ink-subtle">No vehicles tracked now</span>
          ) : (
            <>
              <ServiceStatusPill status={health.state} className="px-2 py-0.5 text-xs" />
              <span className="tabular text-xs text-ink-subtle">
                {[confidenceLabel(health.confidence), `${health.vehicles} vehicles`].filter(Boolean).join(" · ")}
              </span>
            </>
          )}
        </>
      )}
      <FavouriteButton kind="routes" id={saved.routeId} name={`route ${route?.shortName ?? saved.routeId}`} compact />
    </li>
  );
}

function SavedStop({ saved }: { saved: FavouriteStop }) {
  const stop = saved.stop;
  return (
    <li className="flex items-center gap-3 py-2">
      <span className="min-w-0 flex-1 truncate text-sm text-ink">
        {stop === null ? `Stop ${saved.stopId}` : stop.name}
        {stop === null ? (
          <span className="ml-2 text-xs text-ink-subtle">Not in the current schedule</span>
        ) : stop.code === null ? null : (
          <span className="tabular ml-2 text-xs text-ink-subtle">#{stop.code}</span>
        )}
      </span>
      <FavouriteButton kind="stops" id={saved.stopId} name={stop?.name ?? `stop ${saved.stopId}`} compact />
    </li>
  );
}
