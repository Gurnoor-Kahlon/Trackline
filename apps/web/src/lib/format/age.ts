/**
 * Formatting for data age.
 *
 * Every feed-derived value in Trackline is shown with how old it is, so this is
 * used wherever live data appears. It deliberately never rounds an age down to
 * "now": the whole point is to be honest about staleness.
 */

/** Matches the API's own staleness threshold so the UI and API agree. */
export const STALE_AFTER_SECONDS = 60;

/**
 * A short age for a chip: "12s", "3m", "1h 4m".
 *
 * Ages below a second read as "0s" rather than "just now", because a caller
 * showing this is asking for a measurement, not a reassurance.
 */
export function formatAge(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));

  if (whole < 60) {
    return `${whole}s`;
  }
  if (whole < 3600) {
    return `${Math.floor(whole / 60)}m`;
  }

  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
}

/**
 * A sentence for screen readers and tooltips, where a terse chip is not enough.
 */
export function describeAge(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));

  if (whole < 60) {
    return `Updated ${whole} second${whole === 1 ? "" : "s"} ago`;
  }
  if (whole < 3600) {
    const minutes = Math.floor(whole / 60);
    return `Updated ${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  }

  const hours = Math.floor(whole / 3600);
  return `Updated ${hours} hour${hours === 1 ? "" : "s"} ago`;
}

export function isStale(seconds: number): boolean {
  return seconds > STALE_AFTER_SECONDS;
}
