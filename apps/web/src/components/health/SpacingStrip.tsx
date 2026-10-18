import { cn } from "@/lib/cn";
import type { DirectionSpacing, VehicleRole } from "@/lib/api/types";
import { formatDistance } from "@/lib/health/explain";
import { describeDirection, describeRole, layoutStrip, scaleLabels } from "@/lib/bunching/strip";

type SpacingStripProps = {
  direction: DirectionSpacing;
  /** The destination, so a reader knows which way this strip runs. */
  label: string;
};

/**
 * One direction's vehicles laid out along the route, with any group running together marked.
 *
 * Shape as well as colour tells the roles apart, so none rests on colour alone: a filled circle is a
 * vehicle in the spacing, a diamond one in a group, and a hollow circle one left out. The same
 * information is a sentence for a screen reader and a list of positions below the picture.
 */
export function SpacingStrip({ direction, label }: SpacingStripProps) {
  const { markers, bunches } = layoutStrip(direction);
  const summary = describeDirection(direction);
  const scale = scaleLabels(direction);

  return (
    <figure className="space-y-1">
      <figcaption className="text-xs font-semibold text-ink">{label}</figcaption>

      <div role="img" aria-label={summary} className="relative mx-1.5 h-8">
        <div aria-hidden className="absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 rounded bg-border-strong" />

        {bunches.flatMap((bunch, bunchIndex) =>
          bunch.segments.map((segment, segmentIndex) => (
            <div
              key={`${bunchIndex}-${segmentIndex}`}
              aria-hidden
              data-testid="bunch"
              className={cn(
                "absolute top-1 bottom-1 min-w-3 -translate-x-1.5 rounded-md border-2",
                bunch.severity === "SEVERE"
                  ? "border-status-major bg-status-major-quiet"
                  : "border-status-unstable bg-status-unstable-quiet",
              )}
              style={{
                left: `${segment.start * 100}%`,
                width: `calc(${(segment.end - segment.start) * 100}% + 0.75rem)`,
              }}
            />
          )),
        )}

        {markers.map((marker) => (
          <span
            key={marker.vehicleId}
            aria-hidden
            data-role={marker.role}
            className={cn("absolute top-1/2 -translate-x-1/2 -translate-y-1/2", MARKER[marker.role])}
            style={{ left: `${marker.fraction * 100}%` }}
          />
        ))}
      </div>

      <div aria-hidden className="tabular mx-1.5 flex justify-between text-[11px] text-ink-subtle">
        <span>{scale.start}</span>
        {direction.expectedGapMetres === null ? null : (
          <span>
            {direction.basis === "SCHEDULE" ? "timetable spacing" : "average spacing"}{" "}
            {formatDistance(direction.expectedGapMetres)}
          </span>
        )}
        <span>{scale.end}</span>
      </div>

      <p className="text-xs text-ink-muted">{summary}</p>

      {direction.vehicles.length === 0 ? null : (
        <details className="text-xs">
          <summary className="cursor-pointer rounded-sm font-medium text-ink-muted hover:text-ink">
            Vehicles along the route
          </summary>
          <ul className="tabular mt-1 space-y-0.5">
            {direction.vehicles.map((vehicle) => (
              <li key={vehicle.vehicleId} className="flex justify-between gap-3">
                <span className="text-ink">
                  {vehicle.vehicleId} · {formatDistance(vehicle.progressMetres)}
                </span>
                <span className="text-right text-ink-muted">{describeRole(vehicle.role)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </figure>
  );
}

const MARKER: Record<VehicleRole, string> = {
  SPACED: "size-2.5 rounded-full bg-ink",
  BUNCHED: "size-3 rotate-45 bg-status-unstable ring-2 ring-surface-raised",
  AT_TERMINAL: "size-2 rounded-full border-2 border-ink-subtle bg-surface-raised",
  STALE: "size-2 rounded-full border-2 border-dashed border-ink-subtle bg-surface-raised",
};
