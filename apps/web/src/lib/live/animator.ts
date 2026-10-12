import type { LiveStore } from "@/lib/live/liveStore";
import {
  IntervalEstimator,
  isFinished,
  positionAt,
  retarget,
  type LngLat,
  type Track,
} from "@/lib/live/interpolation";
import type { LiveVehicle } from "@/lib/live/types";

/** A vehicle together with where its marker should be drawn this frame. */
export type AnimatedVehicle = {
  vehicle: LiveVehicle;
  position: LngLat;
};

export type AnimatorOptions = {
  /** Asked afresh each frame, because the preference can change while the page is open. */
  reducedMotion: () => boolean;
  /** A move further than this is drawn as a jump, not a slide. */
  snapDistanceMetres?: number;
};

/** Two kilometres in one report is a reassignment or a glitch, not a bus. */
const DEFAULT_SNAP_METRES = 2_000;

/**
 * Keeps a moving marker for every vehicle in the store.
 *
 * Each frame it notices which vehicles have a new report since the last, points their
 * markers at it, and works out where every marker is now. Vehicles that stopped
 * reporting lose their marker. It holds no timers and draws nothing: given a time it
 * returns positions, which keeps the whole of it testable by supplying the clock.
 */
export class VehicleAnimator {
  private readonly tracks = new Map<string, Track>();
  /** The last report each marker was pointed at, compared by identity to spot a new one. */
  private readonly pointedAt = new Map<string, LiveVehicle>();
  private readonly interval = new IntervalEstimator();
  private readonly snapDistanceMetres: number;

  constructor(
    private readonly store: LiveStore,
    private readonly options: AnimatorOptions,
  ) {
    this.snapDistanceMetres = options.snapDistanceMetres ?? DEFAULT_SNAP_METRES;
  }

  /**
   * @param nowMs a clock for animation only. It must be the same clock on every call,
   *              and need not be the wall clock: only differences between calls are used.
   */
  frame(nowMs: number): { vehicles: AnimatedVehicle[]; animating: boolean } {
    const state = this.store.getState();

    const feedMs = state.feed?.info.timestampMs;
    if (feedMs !== null && feedMs !== undefined) {
      this.interval.observe(feedMs);
    }

    const reducedMotion = this.options.reducedMotion();
    for (const [id, vehicle] of state.vehicles) {
      if (this.pointedAt.get(id) === vehicle) {
        continue;
      }
      const held = this.tracks.get(id);
      this.tracks.set(
        id,
        retarget(
          held === undefined ? null : positionAt(held, nowMs),
          [vehicle.longitude, vehicle.latitude],
          nowMs,
          this.interval.durationMs,
          { snapDistanceMetres: this.snapDistanceMetres, reducedMotion },
        ),
      );
      this.pointedAt.set(id, vehicle);
    }

    for (const id of [...this.tracks.keys()]) {
      if (!state.vehicles.has(id)) {
        this.tracks.delete(id);
        this.pointedAt.delete(id);
      }
    }

    const vehicles: AnimatedVehicle[] = [];
    let animating = false;
    for (const [id, vehicle] of state.vehicles) {
      const track = this.tracks.get(id);
      if (track === undefined) {
        continue;
      }
      if (!isFinished(track, nowMs)) {
        animating = true;
      }
      vehicles.push({ vehicle, position: positionAt(track, nowMs) });
    }
    return { vehicles, animating };
  }

  /** How long a move is currently taking, learned from the feed. For tests and display. */
  get moveDurationMs(): number {
    return this.interval.durationMs;
  }
}
