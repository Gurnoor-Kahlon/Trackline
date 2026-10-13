"use client";

import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { RouteBadge } from "@/components/ui/RouteBadge";
import { ServiceStatusPill } from "@/components/ui/ServiceStatusPill";
import { SkeletonLines } from "@/components/ui/Skeleton";
import { confidenceLabel } from "@/lib/health/explain";
import { needingAttention, tally } from "@/lib/health/summary";
import { useHealthRoutes } from "@/lib/health/useHealth";

/** A handful, worst first: the home page points at the routes, it does not list the network. */
const HOME_ROUTE_COUNT = 6;

/**
 * Which routes have uneven spacing right now.
 *
 * Says what it measures, and how much of the network it could measure: a count of flagged
 * routes means little without the number it could not judge, which is most of them in the
 * small hours and on quiet routes.
 */
export function HomeRouteHealth() {
  const result = useHealthRoutes();

  return (
    <Card as="section" ariaLabelledBy="health-heading">
      <CardHeader id="health-heading" title="Route health" />
      <CardBody className="pt-2">
        {result.status === "loading" ? (
          <SkeletonLines lines={3} label="Loading route health" />
        ) : result.status === "error" ? (
          <ErrorState
            title="Route health could not be loaded"
            description={result.message}
            errorId={result.errorId}
          />
        ) : result.meta.source === "NO_DATA" ? (
          <EmptyState
            title="Route health has not been calculated yet"
            description="No vehicle positions have been analysed, which is different from every route being fine."
          />
        ) : (
          <Loaded routes={result.data.routes} stale={result.meta.stale} refreshFailed={result.refreshFailed} />
        )}
      </CardBody>
    </Card>
  );
}

function Loaded({
  routes,
  stale,
  refreshFailed,
}: {
  routes: Parameters<typeof tally>[0];
  stale: boolean;
  refreshFailed: boolean;
}) {
  const counts = tally(routes);
  const flagged = needingAttention(routes, HOME_ROUTE_COUNT);
  const unevenRoutes = counts.byState.UNSTABLE + counts.byState.MAJOR_DISRUPTION;

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-muted">
        {counts.judged === 0
          ? `None of the ${routes.length} routes with vehicles has enough of them to judge.`
          : `Of ${counts.judged} routes with enough vehicles to judge, ${unevenRoutes} ${
              unevenRoutes === 1 ? "has" : "have"
            } uneven spacing. ${counts.notJudged} more ${
              counts.notJudged === 1 ? "has" : "have"
            } too few.`}{" "}
        Judged on how evenly vehicles are spaced, not on delay, which the TTC does not publish.
      </p>

      {stale ? (
        <p role="status" className="text-xs font-medium text-stale">
          The vehicle data behind this is old, so it may not describe the network now.
        </p>
      ) : null}
      {refreshFailed ? (
        <p role="status" className="text-xs font-medium text-stale">
          The latest refresh failed; this is from an earlier one.
        </p>
      ) : null}

      {flagged.length === 0 ? (
        counts.judged === 0 ? null : (
          <p className="text-sm font-medium text-ink">No route is currently judged unstable or disrupted.</p>
        )
      ) : (
        <ul className="divide-y divide-border">
          {flagged.map((route) => (
            <li key={route.routeId} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
              <RouteBadge
                number={route.shortName}
                mode={route.mode === "OTHER" ? "BUS" : route.mode}
                colour={route.colour}
                textColour={route.textColour}
                size="sm"
              />
              <span className="min-w-0 flex-1 truncate text-sm text-ink">{route.longName}</span>
              <ServiceStatusPill status={route.state} className="px-2 py-0.5 text-xs" />
              <span className="tabular text-xs text-ink-subtle">
                {[confidenceLabel(route.confidence), `${route.vehicles} vehicles`].filter(Boolean).join(" · ")}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
