"use client";

import Link from "next/link";

import { AlertCard } from "@/components/alerts/AlertCard";
import type { AlertsResult } from "@/lib/alerts/useAlerts";
import { useNow } from "@/lib/live/useNow";

/**
 * The alerts that name one route, for the route panel.
 *
 * "Name" is the exact claim. An alert about an elevator at a station names every route
 * that serves the station, so a route can carry alerts that do not affect its vehicles.
 * The heading says what the feed said and no more.
 */
export function RouteAlerts({ result }: { result: AlertsResult }) {
  const now = useNow();

  return (
    <section aria-labelledby="route-alerts-heading" className="space-y-1.5">
      <h3
        id="route-alerts-heading"
        className="text-sm font-semibold tracking-wide text-ink-muted uppercase"
      >
        Alerts naming this route
      </h3>

      {result.status === "loading" ? (
        <p className="text-sm text-ink-muted">Loading alerts…</p>
      ) : result.status === "error" ? (
        <p role="alert" className="text-sm text-status-major">
          Alerts could not be loaded.
        </p>
      ) : result.meta.source === "NO_DATA" ? (
        <p className="text-sm text-ink-muted">Alerts have not been ingested yet.</p>
      ) : result.total === 0 ? (
        <p className="text-sm text-ink-muted">None in the TTC&apos;s alerts feed.</p>
      ) : (
        <>
          <ul className="space-y-1.5">
            {result.alerts.map((alert) => (
              <AlertCard
                key={alert.id}
                alert={alert}
                serverNowMs={now === null ? null : now + result.serverOffsetMs}
                compact
              />
            ))}
          </ul>
          <p className="text-sm text-ink-muted">
            {result.total > result.alerts.length
              ? `${result.total - result.alerts.length} more. `
              : ""}
            <Link href="/alerts" className="rounded-sm font-medium text-accent underline underline-offset-2">
              All alerts
            </Link>
          </p>
        </>
      )}
      {result.status === "ready" && result.refreshFailed ? (
        <p role="status" className="text-xs text-stale">
          The latest refresh failed; these are from an earlier one.
        </p>
      ) : null}
    </section>
  );
}
