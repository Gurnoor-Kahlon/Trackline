"use client";

import Link from "next/link";

import { AlertCard } from "@/components/alerts/AlertCard";
import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { SkeletonLines } from "@/components/ui/Skeleton";
import { useAlerts } from "@/lib/alerts/useAlerts";
import { useNow } from "@/lib/live/useNow";

/** A handful, newest first: the home page points to the alerts, it does not reproduce them. */
const HOME_ALERT_COUNT = 3;

/**
 * The newest active alerts, with a way to the rest.
 *
 * Newest, not most severe: the feed publishes no severity, so there is nothing to rank by,
 * and this says so in its own heading rather than implying a judgement it has not made.
 */
export function HomeAlerts() {
  const result = useAlerts({ active: true, size: HOME_ALERT_COUNT });
  const now = useNow();

  return (
    <Card as="section" ariaLabelledBy="alerts-heading">
      <CardHeader
        id="alerts-heading"
        title="Service alerts"
        action={
          <Link href="/alerts" className="rounded-sm text-sm font-medium text-accent underline underline-offset-2">
            All alerts
          </Link>
        }
      />
      <CardBody className="pt-2">
        {result.status === "loading" ? (
          <SkeletonLines lines={3} label="Loading alerts" />
        ) : result.status === "error" ? (
          <ErrorState
            title="Alerts could not be loaded"
            description={result.message}
            errorId={result.errorId}
          />
        ) : result.meta.source === "NO_DATA" ? (
          <EmptyState
            title="Alerts have not been ingested yet"
            description="Trackline has not read the TTC's alerts feed, which is different from there being no alerts."
          />
        ) : result.total === 0 ? (
          <EmptyState title="No active alerts" description="The TTC's alerts feed currently carries none." />
        ) : (
          <div className="space-y-2">
            <p className="text-sm text-ink-muted">
              {result.total} active alert{result.total === 1 ? "" : "s"}. The newest{" "}
              {result.alerts.length === 1 ? "is" : `${result.alerts.length} are`} shown; the TTC does not
              rank alerts by severity.
            </p>
            <ul className="space-y-2">
              {result.alerts.map((alert) => (
                <AlertCard
                  key={alert.id}
                  alert={alert}
                  serverNowMs={now === null ? null : now + result.serverOffsetMs}
                  compact
                />
              ))}
            </ul>
          </div>
        )}
      </CardBody>
    </Card>
  );
}
