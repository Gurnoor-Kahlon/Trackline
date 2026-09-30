import { cn } from "@/lib/cn";
import { describeAge, formatAge, isStale } from "@/lib/format/age";

type FreshnessChipProps = {
  /** Age of the underlying feed data, or null when nothing has been measured. */
  ageSeconds: number | null;
  /** True when a dependency failed and a fallback was served. */
  degraded?: boolean | undefined;
  className?: string | undefined;
};

/**
 * How old the data on screen is.
 *
 * This exists because Trackline never presents feed data as current without
 * saying how current. A null age is rendered as "not measured" rather than "0s":
 * unknown and brand new are very different claims.
 *
 * `aria-live="polite"` so a screen reader user learns when data goes stale
 * without being interrupted mid-sentence on every refresh.
 */
export function FreshnessChip({ ageSeconds, degraded = false, className }: FreshnessChipProps) {
  const unknown = ageSeconds === null;
  const stale = !unknown && isStale(ageSeconds);
  const flagged = stale || degraded;

  return (
    <span
      aria-live="polite"
      className={cn(
        "tabular inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-xs font-medium",
        flagged
          ? "bg-stale-quiet text-stale"
          : "bg-surface-inset text-ink-muted",
        className,
      )}
    >
      <span
        aria-hidden
        className={cn(
          "size-1.5 rounded-full",
          flagged ? "bg-stale" : "bg-status-excellent",
        )}
      />
      {unknown ? "Not measured" : formatAge(ageSeconds)}
      <span className="sr-only">
        {unknown
          ? ". No live data has been recorded yet."
          : `. ${describeAge(ageSeconds)}${stale ? ". This data is stale." : ""}`}
        {degraded ? " Serving from a fallback source." : ""}
      </span>
    </span>
  );
}
