import { beforeEach, describe, expect, it } from "vitest";

import { VehicleAnimator } from "@/lib/live/animator";
import { distanceMetres } from "@/lib/live/interpolation";
import { LiveStore } from "@/lib/live/liveStore";

import { FEED_TIME, delta, snapshot, vehicle } from "./helpers";

/** Four hundred metres of latitude: far enough to see move, near enough to slide. */
const METRES_400 = 400 / 111_320;

describe("the animator", () => {
  let store: LiveStore;
  let reducedMotion: boolean;
  let animator: VehicleAnimator;

  beforeEach(() => {
    store = new LiveStore();
    reducedMotion = false;
    animator = new VehicleAnimator(store, { reducedMotion: () => reducedMotion });
  });

  function apply(...actions: Parameters<LiveStore["dispatch"]>[0][]) {
    actions.forEach((action) => store.dispatch(action));
  }

  function firstSnapshot(vehicles = [vehicle("a", { latitude: 43.6 })], ageSeconds = 5) {
    apply({ type: "snapshot", message: snapshot(vehicles, ageSeconds), eventId: "1", nowMs: 1_000 });
  }

  function positionOf(id: string, nowMs: number) {
    const found = animator.frame(nowMs).vehicles.find((item) => item.vehicle.id === id);
    if (found === undefined) {
      throw new Error(`No marker for ${id}`);
    }
    return found.position;
  }

  it("draws nothing before anything has arrived", () => {
    expect(animator.frame(0)).toEqual({ vehicles: [], animating: false });
  });

  it("places a vehicle seen for the first time where it is, not sliding in from anywhere", () => {
    firstSnapshot();

    const { vehicles, animating } = animator.frame(10_000);

    expect(vehicles).toHaveLength(1);
    expect(vehicles[0]?.position).toEqual([-79.38, 43.6]);
    expect(animating).toBe(false);
  });

  it("slides a vehicle towards its new report over the learned interval", () => {
    firstSnapshot();
    animator.frame(0);

    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });
    const started = animator.frame(10_000);
    const halfway = positionOf("a", 10_000 + animator.moveDurationMs / 2);
    const arrived = positionOf("a", 10_000 + animator.moveDurationMs);

    expect(started.animating).toBe(true);
    expect(positionOf("a", 10_000)[1]).toBeCloseTo(43.6, 6);
    expect(halfway[1]).toBeCloseTo(43.6 + METRES_400 / 2, 6);
    expect(arrived[1]).toBeCloseTo(43.6 + METRES_400, 10);
  });

  it("stops at the newest report and does not run on past it", () => {
    firstSnapshot();
    animator.frame(0);
    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });
    animator.frame(10_000);

    const muchLater = animator.frame(10_000 + 10 * animator.moveDurationMs);

    // The plan's rule: nothing is predicted. With no newer report the marker waits.
    expect(muchLater.vehicles[0]?.position).toEqual([-79.38, 43.6 + METRES_400]);
    expect(muchLater.animating).toBe(false);
  });

  it("starts a new move from where the marker is drawn, with no jump", () => {
    firstSnapshot();
    animator.frame(0);
    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });
    animator.frame(10_000);
    const midway = positionOf("a", 10_000 + animator.moveDurationMs / 2);

    // A second report arrives while the first move is half done.
    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + 2 * METRES_400, reportedAt: FEED_TIME / 1000 + 40 })]),
      eventId: "3",
      nowMs: 3_000,
    });
    const atJoin = positionOf("a", 10_000 + animator.moveDurationMs / 2);

    expect(distanceMetres(atJoin, midway)).toBeLessThan(0.01);
  });

  it("moves only the vehicles that have a new report", () => {
    firstSnapshot([vehicle("a", { latitude: 43.6 }), vehicle("b", { latitude: 43.7 })]);
    animator.frame(0);

    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });
    animator.frame(10_000);

    expect(positionOf("b", 10_000 + animator.moveDurationMs / 2)).toEqual([-79.38, 43.7]);
  });

  it("drops the marker of a vehicle that stopped reporting", () => {
    firstSnapshot([vehicle("a"), vehicle("b")]);
    animator.frame(0);

    apply({ type: "delta", message: delta([], ["b"]), eventId: "2", nowMs: 2_000 });

    expect(animator.frame(10_000).vehicles.map((item) => item.vehicle.id)).toEqual(["a"]);
  });

  it("jumps a vehicle that has moved implausibly far instead of drawing a journey", () => {
    firstSnapshot();
    animator.frame(0);

    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + 0.1, reportedAt: FEED_TIME / 1000 + 20 })]),   // about 11 km
      eventId: "2",
      nowMs: 2_000,
    });

    const frame = animator.frame(10_000);

    expect(frame.vehicles[0]?.position[1]).toBeCloseTo(43.7, 10);
    expect(frame.animating).toBe(false);
  });

  it("does not slide at all for someone who prefers reduced motion", () => {
    reducedMotion = true;
    firstSnapshot();
    animator.frame(0);

    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });
    const frame = animator.frame(10_000);

    expect(frame.vehicles[0]?.position[1]).toBeCloseTo(43.6 + METRES_400, 10);
    expect(frame.animating).toBe(false);
  });

  it("notices the preference changing while the page is open", () => {
    firstSnapshot();
    animator.frame(0);
    reducedMotion = true;

    apply({
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.6 + METRES_400, reportedAt: FEED_TIME / 1000 + 20 })]),
      eventId: "2",
      nowMs: 2_000,
    });

    expect(animator.frame(10_000).animating).toBe(false);
  });

  it("learns how long a move should take from the feed's own timestamps", () => {
    for (let publication = 0; publication < 30; publication++) {
      apply({
        type: "heartbeat",
        feed: { timestampMs: FEED_TIME + publication * 30_000, ageSeconds: 5 },
        serverTimeMs: FEED_TIME + publication * 30_000,
        nowMs: 1_000 + publication,
      });
      animator.frame(publication);
    }

    expect(animator.moveDurationMs).toBeGreaterThan(29_000);
  });
});
