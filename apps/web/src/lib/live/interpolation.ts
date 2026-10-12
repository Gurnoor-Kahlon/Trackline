/**
 * Moving a marker between the positions a vehicle actually reported.
 *
 * Vehicles report every twenty seconds or so. Drawn as they arrive they would jump
 * a hundred metres at a time; drawn smoothly they look alive. The risk in smoothing
 * is inventing accuracy: sliding a marker past the last fix, towards where the vehicle
 * is guessed to be going, shows a position nobody measured. So the rule here is one
 * line: **a marker is only ever placed on the line between two positions the vehicle
 * reported, and never beyond the latest one.** Nothing is predicted.
 *
 * The price is that the picture lags the newest report by up to one interval. That is
 * the honest trade: a marker that is late is wrong in a way it can be seen to be, and
 * a marker that is ahead is wrong in a way nobody can see.
 */

export type LngLat = readonly [longitude: number, latitude: number];

/** A marker's path from where it is shown to the newest position reported. */
export type Track = {
  from: LngLat;
  /** The newest position the vehicle reported. The marker never goes past it. */
  to: LngLat;
  startMs: number;
  durationMs: number;
};

/**
 * Where a marker is at a given moment.
 *
 * Clamped to the segment: before the start it is at the start, after the end it stays
 * at the end. It stays at the end rather than carrying on, which is the whole point.
 * Linear rather than eased: easing in and out would make a marker stop and start
 * along a vehicle that is moving steadily, and the stops would look like stops.
 */
export function positionAt(track: Track, nowMs: number): LngLat {
  if (track.durationMs <= 0) {
    return track.to;
  }
  const progress = clamp((nowMs - track.startMs) / track.durationMs, 0, 1);
  return [
    track.from[0] + (track.to[0] - track.from[0]) * progress,
    track.from[1] + (track.to[1] - track.from[1]) * progress,
  ];
}

export function isFinished(track: Track, nowMs: number): boolean {
  return track.durationMs <= 0 || nowMs - track.startMs >= track.durationMs;
}

export type RetargetOptions = {
  /** Further than this, the marker jumps instead of sliding. */
  snapDistanceMetres: number;
  /** Someone who has asked for less motion is not shown a marker travelling at all. */
  reducedMotion: boolean;
};

/**
 * A new report has arrived: start moving towards it.
 *
 * The move starts from where the marker is *shown*, not from the previous report. A
 * marker still part-way along its last segment would otherwise leap back to that
 * segment's start and begin again, and every report would visibly jerk it.
 *
 * It snaps rather than slides when the move is implausibly far. A vehicle that jumps
 * kilometres between reports has changed route, been reassigned or glitched, and
 * sliding it across the city would draw a journey it never made.
 */
export function retarget(
  shown: LngLat | null,
  target: LngLat,
  nowMs: number,
  durationMs: number,
  options: RetargetOptions,
): Track {
  const arrivesAtOnce =
    shown === null ||
    options.reducedMotion ||
    distanceMetres(shown, target) > options.snapDistanceMetres;

  return {
    from: arrivesAtOnce ? target : shown,
    to: target,
    startMs: nowMs,
    durationMs: arrivesAtOnce ? 0 : durationMs,
  };
}

/** Equirectangular distance: well under a metre of error over the ranges this compares. */
export function distanceMetres(a: LngLat, b: LngLat): number {
  const metresPerDegree = 111_320;
  const northing = (b[1] - a[1]) * metresPerDegree;
  const easting = (b[0] - a[0]) * metresPerDegree * Math.cos(((a[1] + b[1]) / 2) * (Math.PI / 180));
  return Math.hypot(northing, easting);
}

/**
 * How long a marker should take to travel to its next position, learned from the feed.
 *
 * The duration of a move is the interval at which the feed publishes, so a marker
 * reaches the newest position just as the next one is due. That interval is measured,
 * from the feed's own timestamps, and not assumed: it was measured at about 20 seconds
 * against the live TTC feed (median 20-21, fastest 6, slowest 42), and it can change.
 *
 * Measured from the feed's timestamps and not from when messages arrive, so network
 * jitter does not make the estimate wander.
 */
export class IntervalEstimator {
  private lastFeedMs: number | null = null;
  private estimateMs: number;

  /**
   * @param initialMs the estimate before anything is measured: the interval measured
   *                  against the live feed, used only until real ones replace it
   */
  constructor(
    initialMs = 20_000,
    private readonly minMs = 5_000,
    private readonly maxMs = 40_000,
    private readonly smoothing = 0.3,
  ) {
    this.estimateMs = initialMs;
  }

  /** Records that the feed published at this time. */
  observe(feedTimestampMs: number): void {
    if (this.lastFeedMs !== null) {
      const gap = feedTimestampMs - this.lastFeedMs;
      // A repeat, or a gap so long the feed was evidently down, says nothing about
      // how often it normally publishes.
      if (gap > 0 && gap <= this.maxMs * 3) {
        this.estimateMs = clamp(
          this.estimateMs + this.smoothing * (gap - this.estimateMs),
          this.minMs,
          this.maxMs,
        );
      }
    }
    if (this.lastFeedMs === null || feedTimestampMs > this.lastFeedMs) {
      this.lastFeedMs = feedTimestampMs;
    }
  }

  get durationMs(): number {
    return this.estimateMs;
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
