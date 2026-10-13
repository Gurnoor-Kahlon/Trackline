"use client";

import { ServiceStatusPill } from "@/components/ui/ServiceStatusPill";
import type { RouteHealth } from "@/lib/api/types";
import { confidenceLabel, directionFigures, explainHealth } from "@/lib/health/explain";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";
import { formatAge } from "@/lib/format/age";
import { useNow } from "@/lib/live/useNow";

type RouteHealthSectionProps = {
  result: PolledResult<RouteHealth>;
  /** The destination shown for a direction, so the details can say which way they describe. */
  headsign: (directionId: number) => string | null;
};

/**
 * One route's health: the verdict, how far to trust it, and why in words made from the figures.
 *
 * It measures how evenly vehicles are spaced and says so. The TTC publishes no delay, so
 * nothing here means late or on time, and a verdict from few vehicles or no timetable says
 * that beside the verdict instead of leaving it to be discovered.
 */
export function RouteHealthSection({ result, headsign }: RouteHealthSectionProps) {
  const now = useNow();

  return (
    <section aria-labelledby="route-health-heading" className="space-y-2">
      <h3
        id="route-health-heading"
        className="text-sm font-semibold tracking-wide text-ink-muted uppercase"
      >
        Service health
      </h3>

      {result.status === "loading" ? (
        <p className="text-sm text-ink-muted">Measuring…</p>
      ) : result.status === "error" ? (
        <p role="alert" className="text-sm text-status-major">
          Service health could not be loaded.
        </p>
      ) : (
        <Verdict result={result} headsign={headsign} serverNowMs={now === null ? null : now + result.serverOffsetMs} />
      )}
    </section>
  );
}

function Verdict({
  result,
  headsign,
  serverNowMs,
}: {
  result: Extract<PolledResult<RouteHealth>, { status: "ready" }>;
  headsign: (directionId: number) => string | null;
  serverNowMs: number | null;
}) {
  const health = result.data;
  const explanation = explainHealth(health);
  const confidence = confidenceLabel(health.confidence);
  const feedAge =
    serverNowMs === null || result.meta.feedTimestamp === null
      ? null
      : (serverNowMs - Date.parse(result.meta.feedTimestamp)) / 1000;

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <ServiceStatusPill status={health.state} />
        {confidence === null ? null : <span className="text-xs text-ink-muted">{confidence}</span>}
      </div>

      <p className="text-sm text-ink">{explanation.headline}</p>

      {explanation.caveats.length === 0 ? null : (
        <ul className="list-disc space-y-1 pl-5 text-xs text-ink-muted">
          {explanation.caveats.map((caveat) => (
            <li key={caveat}>{caveat}</li>
          ))}
        </ul>
      )}

      {result.meta.stale ? (
        <p role="status" className="text-xs font-medium text-stale">
          The vehicle data behind this is {feedAge === null ? "old" : `${formatAge(feedAge)} old`}, so it may not
          describe the route now.
        </p>
      ) : feedAge === null ? null : (
        <p className="tabular text-xs text-ink-subtle">Vehicle data {formatAge(feedAge)} old.</p>
      )}
      {result.refreshFailed ? (
        <p role="status" className="text-xs font-medium text-stale">
          The latest refresh failed; this is from an earlier one.
        </p>
      ) : null}

      {health.directions.length === 0 ? null : (
        <details className="text-sm">
          <summary className="cursor-pointer rounded-sm font-medium text-ink-muted hover:text-ink">
            How this was measured
          </summary>
          <div className="mt-2 space-y-3">
            {health.directions.map((direction) => (
              <div key={direction.directionId}>
                <h4 className="mb-1 flex flex-wrap items-center gap-2 text-xs font-semibold text-ink">
                  <span>{headsign(direction.directionId) ?? `Direction ${direction.directionId}`}</span>
                  <ServiceStatusPill status={direction.state} className="px-2 py-0.5 text-xs" />
                </h4>
                <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-0.5 text-xs">
                  {directionFigures(direction.metrics).flatMap((row) => [
                    <dt key={`${row.label}-term`} className="text-ink-muted">
                      {row.label}
                    </dt>,
                    <dd key={`${row.label}-value`} className="tabular text-right text-ink">
                      {row.value}
                    </dd>,
                  ])}
                </dl>
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
