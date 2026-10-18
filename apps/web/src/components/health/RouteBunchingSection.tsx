"use client";

import { SpacingStrip } from "@/components/health/SpacingStrip";
import type { BunchingEvent, RouteBunching } from "@/lib/api/types";
import { describeEvent } from "@/lib/bunching/strip";
import { formatDistance } from "@/lib/health/explain";
import { formatAge } from "@/lib/format/age";
import { useNow } from "@/lib/live/useNow";
import type { PolledResult } from "@/lib/polling/usePolledEnvelope";

/** Enough to show the recent pattern without turning a busy route's panel into a log. */
const EVENTS_SHOWN = 5;

type RouteBunchingSectionProps = {
  result: PolledResult<RouteBunching>;
  /** The destination shown for a direction. */
  headsign: (directionId: number) => string | null;
};

/**
 * Where a route's vehicles are right now, which are running together, and what has been seen lately.
 *
 * A group of vehicles counts as bunched only while a large gap exists elsewhere on the same direction,
 * and the section says so rather than marking every close pair. The history lists only what Trackline
 * was running to see, with times that are when it saw each bunch and not when one began or ended.
 */
export function RouteBunchingSection({ result, headsign }: RouteBunchingSectionProps) {
  const now = useNow();

  return (
    <section aria-labelledby="route-bunching-heading" className="space-y-2">
      <h3
        id="route-bunching-heading"
        className="text-sm font-semibold tracking-wide text-ink-muted uppercase"
      >
        Spacing along the route
      </h3>

      {result.status === "loading" ? (
        <p className="text-sm text-ink-muted">Measuring…</p>
      ) : result.status === "error" ? (
        <p role="alert" className="text-sm text-status-major">
          Spacing could not be loaded.
        </p>
      ) : (
        <Loaded
          result={result}
          headsign={headsign}
          serverNowMs={now === null ? null : now + result.serverOffsetMs}
        />
      )}
    </section>
  );
}

function Loaded({
  result,
  headsign,
  serverNowMs,
}: {
  result: Extract<PolledResult<RouteBunching>, { status: "ready" }>;
  headsign: (directionId: number) => string | null;
  serverNowMs: number | null;
}) {
  const { directions, events } = result.data;
  const feedAge =
    serverNowMs === null || result.meta.feedTimestamp === null
      ? null
      : (serverNowMs - Date.parse(result.meta.feedTimestamp)) / 1000;

  return (
    <div className="space-y-3">
      {result.meta.source === "NO_DATA" ? (
        <p className="text-sm text-ink-muted">Spacing has not been calculated yet.</p>
      ) : directions.length === 0 ? (
        <p className="text-sm text-ink-muted">No vehicles are placed on this route right now.</p>
      ) : (
        directions.map((direction) => (
          <SpacingStrip
            key={direction.directionId}
            direction={direction}
            label={headsign(direction.directionId) ?? `Direction ${direction.directionId}`}
          />
        ))
      )}

      {result.meta.stale ? (
        <p role="status" className="text-xs font-medium text-stale">
          The vehicle data behind this is {feedAge === null ? "old" : `${formatAge(feedAge)} old`}, so it may not
          describe the route now.
        </p>
      ) : null}
      {result.refreshFailed ? (
        <p role="status" className="text-xs font-medium text-stale">
          The latest refresh failed; this is from an earlier one.
        </p>
      ) : null}

      <Events events={events} headsign={headsign} serverNowMs={serverNowMs} />
    </div>
  );
}

function Events({
  events,
  headsign,
  serverNowMs,
}: {
  events: BunchingEvent[];
  headsign: (directionId: number) => string | null;
  serverNowMs: number | null;
}) {
  return (
    <div className="space-y-1">
      <h4 className="text-xs font-semibold text-ink">Groups running together in the last 24 hours</h4>
      {events.length === 0 ? (
        <p className="text-xs text-ink-muted">
          None recorded, in the time Trackline has been watching.
        </p>
      ) : (
        <>
          <ul className="space-y-1.5">
            {events.slice(0, EVENTS_SHOWN).map((event) => (
              <li key={event.id} className="rounded-md border border-border px-2 py-1.5 text-xs">
                <p className="text-ink">
                  {event.status === "ACTIVE" ? "Now: " : ""}
                  {headsign(event.directionId) ?? `Direction ${event.directionId}`}:{" "}
                  {event.peakVehicles === event.vehicleIds.length
                    ? `${event.vehicleIds.length} vehicles`
                    : `${event.vehicleIds.length} vehicles, ${event.peakVehicles} at most`}
                  {", "}
                  {event.peakSeverity === "SEVERE" ? "severe" : "moderate"}, with a gap of{" "}
                  {formatDistance(event.serviceGapMetres)} elsewhere.
                </p>
                <p className="tabular mt-0.5 text-ink-subtle">
                  {serverNowMs === null ? " " : describeEvent(event, serverNowMs)}
                </p>
              </li>
            ))}
          </ul>
          {events.length > EVENTS_SHOWN ? (
            <p className="text-xs text-ink-muted">and {events.length - EVENTS_SHOWN} more.</p>
          ) : null}
        </>
      )}
    </div>
  );
}
