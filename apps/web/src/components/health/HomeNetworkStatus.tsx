"use client";

import Link from "next/link";

import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { FreshnessChip } from "@/components/ui/FreshnessChip";
import { SkeletonLines } from "@/components/ui/Skeleton";
import { fetchNetworkStatus } from "@/lib/api/network";
import { usePolledEnvelope } from "@/lib/polling/usePolledEnvelope";

/** The counts change with every vehicle cycle, about every 20 s, so half a minute is plenty. */
const NETWORK_REFRESH_MS = 30_000;

/**
 * The network's counts, measured from the latest vehicle snapshot.
 *
 * Every figure is a count of something observed, with the age of the feed it came from beside
 * it. When nothing has been observed the card says so, since a row of zeros would claim that
 * the network is empty.
 */
export function HomeNetworkStatus() {
  const result = usePolledEnvelope(
    (signal) => fetchNetworkStatus(signal),
    "network",
    NETWORK_REFRESH_MS,
  );

  return (
    <Card as="section" ariaLabelledBy="network-heading">
      <CardHeader
        id="network-heading"
        title="Network status"
        action={
          <FreshnessChip
            ageSeconds={result.status === "ready" ? result.meta.ageSeconds : null}
            degraded={result.status === "ready" && result.meta.degraded}
          />
        }
      />
      <CardBody className="pt-2">
        {result.status === "loading" ? (
          <SkeletonLines lines={2} label="Loading network status" />
        ) : result.status === "error" ? (
          <ErrorState
            title="Network status could not be loaded"
            description={result.message}
            errorId={result.errorId}
          />
        ) : result.meta.source === "NO_DATA" ? (
          <EmptyState
            title="No vehicle positions have been recorded yet"
            description="Trackline has not read the TTC's vehicle feed, so there are no counts to report. That is different from no vehicles running."
          />
        ) : (
          <>
            <dl className="grid grid-cols-3 gap-4">
              <Figure label="Vehicles tracked" value={result.data.vehiclesTracked.toLocaleString("en-CA")} />
              <Figure label="Routes reporting" value={result.data.routesReporting.toLocaleString("en-CA")} />
              <Figure
                label="Active alerts"
                value={result.data.activeAlerts === null ? "Not counted" : String(result.data.activeAlerts)}
                href={result.data.activeAlerts === null ? undefined : "/alerts"}
              />
            </dl>
            {result.refreshFailed ? (
              <p role="status" className="mt-3 text-xs font-medium text-stale">
                The latest refresh failed; these counts are from an earlier one.
              </p>
            ) : null}
          </>
        )}
      </CardBody>
    </Card>
  );
}

function Figure({ label, value, href }: { label: string; value: string; href?: "/alerts" | undefined }) {
  return (
    <div>
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="tabular mt-0.5 text-2xl font-semibold text-ink">
        {href === undefined ? (
          value
        ) : (
          <Link href={href} className="rounded-sm hover:underline">
            {value}
          </Link>
        )}
      </dd>
    </div>
  );
}
