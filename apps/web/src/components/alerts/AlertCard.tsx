import { RouteBadge } from "@/components/ui/RouteBadge";
import { cn } from "@/lib/cn";
import type { ServiceAlert } from "@/lib/api/types";
import { alertText, describeObserved, hiddenRouteCount, ROUTES_SHOWN } from "@/lib/alerts/display";

type AlertCardProps = {
  alert: ServiceAlert;
  /** Server time in milliseconds, or null before the first client render. */
  serverNowMs: number | null;
  /** A compact card for a narrow panel: no route badges, which would crowd it. */
  compact?: boolean;
  className?: string | undefined;
};

/**
 * One alert.
 *
 * The text shown is the description, because the publisher's header is cut off
 * mid-word. The times are labelled as sightings: the feed gives no start or end, so
 * "first seen" and "gone from the feed" are all that is known.
 */
export function AlertCard({ alert, serverNowMs, compact = false, className }: AlertCardProps) {
  const ended = alert.status === "RESOLVED";
  const shown = alert.routes.slice(0, ROUTES_SHOWN);
  const hidden = alert.routes.slice(ROUTES_SHOWN);

  return (
    <li
      className={cn(
        "rounded-card border border-border bg-surface-raised p-3",
        ended && "opacity-80",
        className,
      )}
    >
      <div className="flex items-start gap-2">
        {ended ? (
          <span className="mt-0.5 shrink-0 rounded-pill border border-border px-2 text-xs font-medium text-ink-muted">
            Ended
          </span>
        ) : null}
        <p className="text-sm text-ink">{alertText(alert)}</p>
      </div>

      {compact || alert.routes.length === 0 ? null : (
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <span className="sr-only">Routes named by this alert:</span>
          {shown.map((route) => (
            <RouteChip key={route.routeId} route={route} />
          ))}
          {hidden.length === 0 ? null : (
            <details className="contents">
              <summary className="cursor-pointer rounded-md px-1.5 py-0.5 text-xs font-medium text-ink-muted hover:bg-surface-inset hover:text-ink">
                +{hiddenRouteCount(alert)} more
              </summary>
              <span className="flex w-full flex-wrap gap-1.5 pt-1.5">
                {hidden.map((route) => (
                  <RouteChip key={route.routeId} route={route} />
                ))}
              </span>
            </details>
          )}
        </div>
      )}

      {alert.stops.length === 0 || compact ? null : (
        <p className="mt-2 text-xs text-ink-muted">
          <span className="font-medium">Stops: </span>
          {alert.stops.map((stop) => stop.name ?? `Stop ${stop.stopId}`).join(", ")}
        </p>
      )}

      <p className="tabular mt-2 text-xs text-ink-subtle">
        {serverNowMs === null ? " " : describeObserved(alert, serverNowMs)}
      </p>

      {alert.url === null ? null : (
        <a
          href={alert.url}
          rel="noopener noreferrer"
          target="_blank"
          className="mt-1 inline-block rounded-sm text-xs font-medium text-accent underline underline-offset-2"
        >
          More information
          <span className="sr-only"> (opens in a new tab)</span>
        </a>
      )}
    </li>
  );
}

function RouteChip({ route }: { route: ServiceAlert["routes"][number] }) {
  if (!route.known || route.shortName === null || route.mode === null) {
    // The alert names a route the schedule does not have. It is shown as published, plainly,
    // rather than dropped or dressed up with a colour it was never given.
    return (
      <span
        className="tabular rounded-md border border-dashed border-border px-1.5 py-0.5 text-xs text-ink-muted"
        title="This route is not in the imported schedule"
      >
        {route.routeId}
      </span>
    );
  }
  return (
    <RouteBadge
      number={route.shortName}
      mode={route.mode === "OTHER" ? "BUS" : route.mode}
      colour={route.colour}
      textColour={route.textColour}
      size="sm"
    />
  );
}
