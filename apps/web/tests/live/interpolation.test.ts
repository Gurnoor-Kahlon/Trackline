import { describe, expect, it } from "vitest";

import {
  IntervalEstimator,
  distanceMetres,
  isFinished,
  positionAt,
  retarget,
  type LngLat,
  type Track,
} from "@/lib/live/interpolation";

const OPTIONS = { snapDistanceMetres: 2_000, reducedMotion: false };

/** A fixed-seed generator, so a property that fails fails the same way every run. */
function seeded(seed: number): () => number {
  let state = seed;
  return () => {
    state = (state * 1_664_525 + 1_013_904_223) % 4_294_967_296;
    return state / 4_294_967_296;
  };
}

function randomTrack(random: () => number): Track {
  const lon = -79.6 + random() * 0.4;
  const lat = 43.55 + random() * 0.3;
  return {
    from: [lon, lat],
    to: [lon + (random() - 0.5) * 0.01, lat + (random() - 0.5) * 0.01],
    startMs: random() * 100_000,
    durationMs: 1_000 + random() * 40_000,
  };
}

describe("where a marker is", () => {
  const track: Track = { from: [-79.4, 43.6], to: [-79.3, 43.7], startMs: 1_000, durationMs: 10_000 };

  it("is at the start before the move begins", () => {
    expect(positionAt(track, 0)).toEqual([-79.4, 43.6]);
    expect(positionAt(track, 1_000)).toEqual([-79.4, 43.6]);
  });

  it("is proportionally along the way part-way through", () => {
    const [lon, lat] = positionAt(track, 6_000);

    expect(lon).toBeCloseTo(-79.35, 10);
    expect(lat).toBeCloseTo(43.65, 10);
  });

  it("arrives at the newest position exactly", () => {
    expect(positionAt(track, 11_000)).toEqual([-79.3, 43.7]);
  });

  it("stays at the newest position afterwards and never carries on", () => {
    // The rule of the whole module: nothing is predicted. A marker that kept going
    // would be showing where nobody has seen the vehicle.
    expect(positionAt(track, 11_001)).toEqual([-79.3, 43.7]);
    expect(positionAt(track, 1_000_000)).toEqual([-79.3, 43.7]);
  });

  it("moves in a straight line at a steady pace, with no easing", () => {
    const quarter = positionAt(track, 3_500);
    const half = positionAt(track, 6_000);
    const threeQuarters = positionAt(track, 8_500);

    // Equal steps in time are equal steps in distance. Easing would make a steadily
    // moving vehicle appear to stop and start.
    expect(half[0] - quarter[0]).toBeCloseTo(threeQuarters[0] - half[0], 10);
  });

  it("is at the target at once when the duration is zero", () => {
    expect(positionAt({ ...track, durationMs: 0 }, 1_000)).toEqual([-79.3, 43.7]);
  });
});

describe("the invariant: a marker never leaves the line between two reported positions", () => {
  it("holds for any track at any time", () => {
    const random = seeded(7);

    for (let trial = 0; trial < 5_000; trial++) {
      const track = randomTrack(random);
      const now = track.startMs - 5_000 + random() * (track.durationMs + 20_000);
      const [lon, lat] = positionAt(track, now);

      const minLon = Math.min(track.from[0], track.to[0]);
      const maxLon = Math.max(track.from[0], track.to[0]);
      const minLat = Math.min(track.from[1], track.to[1]);
      const maxLat = Math.max(track.from[1], track.to[1]);

      // Within the box of the two reports on both axes: never past either end.
      expect(lon).toBeGreaterThanOrEqual(minLon - 1e-12);
      expect(lon).toBeLessThanOrEqual(maxLon + 1e-12);
      expect(lat).toBeGreaterThanOrEqual(minLat - 1e-12);
      expect(lat).toBeLessThanOrEqual(maxLat + 1e-12);
    }
  });

  it("never gets further from the newest report as time passes", () => {
    const random = seeded(11);

    for (let trial = 0; trial < 1_000; trial++) {
      const track = randomTrack(random);
      let previous = Number.POSITIVE_INFINITY;

      for (let step = 0; step <= 40; step++) {
        const now = track.startMs - 1_000 + (step / 40) * (track.durationMs + 5_000);
        const away = distanceMetres(positionAt(track, now), track.to);
        // Approaching the newest report, never receding from it and never overshooting.
        expect(away).toBeLessThanOrEqual(previous + 1e-6);
        previous = away;
      }
    }
  });

  it("ends exactly on the newest report, not near it", () => {
    const random = seeded(13);

    for (let trial = 0; trial < 1_000; trial++) {
      const track = randomTrack(random);

      expect(positionAt(track, track.startMs + track.durationMs)).toEqual(track.to);
      expect(positionAt(track, track.startMs + track.durationMs + 50_000)).toEqual(track.to);
    }
  });
});

describe("finishing", () => {
  const track: Track = { from: [0, 0], to: [1, 1], startMs: 1_000, durationMs: 5_000 };

  it("is not finished until the whole duration has passed", () => {
    expect(isFinished(track, 5_999)).toBe(false);
    expect(isFinished(track, 6_000)).toBe(true);
  });

  it("is immediately finished when there is nothing to travel", () => {
    expect(isFinished({ ...track, durationMs: 0 }, 1_000)).toBe(true);
  });
});

describe("a new report", () => {
  const target: LngLat = [-79.38, 43.66];

  it("places a vehicle seen for the first time where it is, with no journey from nowhere", () => {
    const track = retarget(null, target, 5_000, 20_000, OPTIONS);

    expect(track.from).toEqual(target);
    expect(positionAt(track, 5_000)).toEqual(target);
    expect(isFinished(track, 5_000)).toBe(true);
  });

  it("starts moving from where the marker is shown, not from where it was last told", () => {
    const shown: LngLat = [-79.381, 43.659];

    const track = retarget(shown, target, 5_000, 20_000, OPTIONS);

    // Starting from the previous report instead would make the marker leap backwards
    // every time one arrived.
    expect(track.from).toEqual(shown);
    expect(track.to).toEqual(target);
    expect(track.durationMs).toBe(20_000);
    expect(positionAt(track, 5_000)).toEqual(shown);
  });

  it("jumps instead of sliding when the move is implausibly far", () => {
    const farAway: LngLat = [-79.38, 43.66 + 0.05];   // about 5.5 km north

    const track = retarget(farAway, target, 5_000, 20_000, OPTIONS);

    // A vehicle that moves kilometres between reports was reassigned or mis-reported;
    // sliding it across the city would draw a journey it never made.
    expect(track.from).toEqual(target);
    expect(track.durationMs).toBe(0);
  });

  it("still slides for a move just under the limit", () => {
    const justUnder: LngLat = [-79.38, 43.66 + 1_900 / 111_320];

    expect(retarget(justUnder, target, 5_000, 20_000, OPTIONS).durationMs).toBe(20_000);
  });

  it("goes straight to the new position for someone who prefers reduced motion", () => {
    const shown: LngLat = [-79.381, 43.659];

    const track = retarget(shown, target, 5_000, 20_000, { ...OPTIONS, reducedMotion: true });

    expect(track.durationMs).toBe(0);
    expect(positionAt(track, 5_000)).toEqual(target);
  });

  it("keeps the invariant when a new report arrives part-way through a move", () => {
    const first: Track = { from: [-79.40, 43.60], to: [-79.39, 43.61], startMs: 0, durationMs: 20_000 };
    const shown = positionAt(first, 8_000);

    const second = retarget(shown, [-79.385, 43.615], 8_000, 20_000, OPTIONS);

    // Begins exactly where the marker was drawn: no visible jump at the join.
    expect(positionAt(second, 8_000)).toEqual(shown);
    expect(positionAt(second, 28_000)).toEqual([-79.385, 43.615]);
  });
});

describe("how long a move takes", () => {
  it("starts from the interval measured against the live feed", () => {
    expect(new IntervalEstimator().durationMs).toBe(20_000);
  });

  it("learns the feed's interval from its own timestamps", () => {
    const estimator = new IntervalEstimator();

    for (let publication = 0; publication < 40; publication++) {
      estimator.observe(1_000_000 + publication * 30_000);
    }

    // The feed turned out to publish every 30 s here, and the estimate follows.
    expect(estimator.durationMs).toBeGreaterThan(29_000);
    expect(estimator.durationMs).toBeLessThanOrEqual(30_000);
  });

  it("is not moved by a repeat of the same timestamp, as a heartbeat produces", () => {
    const estimator = new IntervalEstimator();
    estimator.observe(1_000_000);

    estimator.observe(1_000_000);
    estimator.observe(1_000_000);

    expect(estimator.durationMs).toBe(20_000);
  });

  it("ignores a gap so long that the feed was evidently down", () => {
    const estimator = new IntervalEstimator();
    estimator.observe(1_000_000);

    estimator.observe(1_000_000 + 3_600_000);

    // An hour's silence says nothing about how often it normally publishes.
    expect(estimator.durationMs).toBe(20_000);
  });

  it("stays within sensible bounds however odd the feed behaves", () => {
    const fast = new IntervalEstimator();
    for (let publication = 0; publication < 100; publication++) {
      fast.observe(publication * 100);
    }
    const slow = new IntervalEstimator();
    for (let publication = 0; publication < 100; publication++) {
      slow.observe(publication * 100_000);
    }

    expect(fast.durationMs).toBeGreaterThanOrEqual(5_000);
    expect(slow.durationMs).toBeLessThanOrEqual(40_000);
  });

  it("is unaffected by events arriving out of order", () => {
    const estimator = new IntervalEstimator();
    estimator.observe(1_020_000);

    estimator.observe(1_000_000);

    expect(estimator.durationMs).toBe(20_000);
  });
});

describe("distance", () => {
  it("is about 111 km for a degree of latitude", () => {
    expect(distanceMetres([-79.4, 43], [-79.4, 44])).toBeCloseTo(111_320, -2);
  });

  it("shrinks for a degree of longitude at Toronto's latitude", () => {
    const eastWest = distanceMetres([-79.4, 43.65], [-78.4, 43.65]);

    expect(eastWest).toBeCloseTo(111_320 * Math.cos((43.65 * Math.PI) / 180), -2);
  });

  it("is zero between a point and itself", () => {
    expect(distanceMetres([-79.4, 43.65], [-79.4, 43.65])).toBe(0);
  });
});
