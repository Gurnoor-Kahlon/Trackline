"use client";

import { useEffect, useRef } from "react";

import { VehicleAnimator, type AnimatedVehicle } from "@/lib/live/animator";
import type { LiveStore } from "@/lib/live/liveStore";
import type { LiveState } from "@/lib/live/liveState";

/**
 * Called with where every marker should be drawn. Returns whether the frame was
 * actually drawn, so the loop knows whether to try the same state again.
 */
export type FrameHandler = (vehicles: readonly AnimatedVehicle[], state: LiveState, wallClockMs: number) => boolean;

/**
 * Roughly ten frames a second. A bus moves a few metres a second, so ten updates a
 * second moves a marker well under a pixel at city zoom between frames; sixty would
 * make the map re-index every vehicle sixty times a second for no visible gain.
 */
const FRAME_INTERVAL_MS = 100;

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * Drives the markers: each frame, work out where every vehicle is shown and hand it on.
 *
 * Runs on the browser's animation frames, which stop on their own while the tab is
 * hidden, so a backgrounded map costs nothing. And it does nothing at all when nothing
 * is moving and nothing new has arrived, rather than redrawing an unchanged picture.
 */
export function useInterpolatedPositions(
  store: LiveStore | null,
  onFrame: FrameHandler,
  /**
   * Changing this restarts the loop, which makes it draw again even though nothing in
   * the store has changed. For when the map has lost what was drawn on it, as a new
   * theme does by replacing the map's whole style.
   */
  restartKey?: unknown,
): void {
  // The newest handler, read by the loop without restarting it each render.
  const handler = useRef(onFrame);
  useEffect(() => {
    handler.current = onFrame;
  });

  useEffect(() => {
    if (store === null) {
      return;
    }

    const animator = new VehicleAnimator(store, { reducedMotion: prefersReducedMotion });
    let handle = 0;
    let lastDrawn = Number.NEGATIVE_INFINITY;
    let lastState: LiveState | null = null;

    const loop = (timestamp: number) => {
      handle = requestAnimationFrame(loop);
      if (timestamp - lastDrawn < FRAME_INTERVAL_MS) {
        return;
      }

      const { vehicles, animating } = animator.frame(timestamp);
      const state = store.getState();
      if (!animating && state === lastState) {
        return;
      }

      // Only counted as drawn if it was. If the map was not ready the same state is
      // offered again, instead of being marked done and never drawn.
      if (handler.current(vehicles, state, Date.now())) {
        lastDrawn = timestamp;
        lastState = state;
      }
    };

    handle = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(handle);
  }, [store, restartKey]);
}
