import { describe, expect, it } from "vitest";

import { LiveStore } from "@/lib/live/liveStore";
import {
  INITIAL_STATE,
  classifyFreshness,
  feedAgeSeconds,
  isVehicleStale,
  reduce,
  serverNowSeconds,
  type LiveState,
} from "@/lib/live/liveState";

import { FEED_TIME, delta, feed, snapshot, vehicle } from "./helpers";

const NOW = 1_000_000;

function applied(state: LiveState, ...actions: Parameters<typeof reduce>[1][]): LiveState {
  return actions.reduce(reduce, state);
}

describe("snapshots", () => {
  it("replace everything held", () => {
    const before = applied(
      INITIAL_STATE,
      { type: "snapshot", message: snapshot([vehicle("a"), vehicle("b")]), eventId: "1", nowMs: NOW },
    );

    const after = reduce(before, { type: "snapshot", message: snapshot([vehicle("c")]), eventId: "2", nowMs: NOW });

    // A snapshot is the whole truth: a vehicle missing from it has stopped reporting.
    expect([...after.vehicles.keys()]).toEqual(["c"]);
    expect(after.lastEventId).toBe("2");
  });

  it("keep the last event id when the snapshot carries none", () => {
    const before = applied(INITIAL_STATE, { type: "snapshot", message: snapshot([]), eventId: "7", nowMs: NOW });

    const after = reduce(before, { type: "snapshot", message: snapshot([]), eventId: null, nowMs: NOW });

    expect(after.lastEventId).toBe("7");
  });
});

describe("changes", () => {
  const start = applied(INITIAL_STATE, {
    type: "snapshot",
    message: snapshot([vehicle("a", { latitude: 43.6 }), vehicle("b")]),
    eventId: "1",
    nowMs: NOW,
  });

  it("add and update vehicles and leave the others alone", () => {
    const after = reduce(start, {
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.7, reportedAt: FEED_TIME / 1000 + 20 }), vehicle("c")]),
      eventId: "2",
      nowMs: NOW,
    });

    expect(after.vehicles.get("a")?.latitude).toBe(43.7);
    expect(after.vehicles.has("b")).toBe(true);
    expect(after.vehicles.has("c")).toBe(true);
    expect(after.lastEventId).toBe("2");
  });

  it("remove vehicles that stopped reporting", () => {
    const after = reduce(start, { type: "delta", message: delta([], ["b"]), eventId: "2", nowMs: NOW });

    expect([...after.vehicles.keys()]).toEqual(["a"]);
  });

  it("ignore a reading older than the one held", () => {
    const newer = vehicle("a", { latitude: 43.9, reportedAt: FEED_TIME / 1000 + 60 });
    const held = reduce(start, { type: "delta", message: delta([newer]), eventId: "2", nowMs: NOW });

    // A straggler: applying it would move the vehicle backwards in time.
    const after = reduce(held, {
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.1, reportedAt: FEED_TIME / 1000 + 10 })]),
      eventId: "3",
      nowMs: NOW,
    });

    expect(after.vehicles.get("a")?.latitude).toBe(43.9);
  });

  it("apply a reading with the same timestamp, which may be a correction", () => {
    const after = reduce(start, {
      type: "delta",
      message: delta([vehicle("a", { latitude: 43.8 })]),
      eventId: "2",
      nowMs: NOW,
    });

    expect(after.vehicles.get("a")?.latitude).toBe(43.8);
  });

  it("do not mutate the state they were applied to", () => {
    const before = new Map(start.vehicles);

    reduce(start, { type: "delta", message: delta([vehicle("z")], ["a"]), eventId: "2", nowMs: NOW });

    expect(new Map(start.vehicles)).toEqual(before);
  });
});

describe("the feed's age", () => {
  const withFeed = (ageSeconds: number | null) =>
    reduce(INITIAL_STATE, { type: "heartbeat", feed: feed(ageSeconds), serverTimeMs: FEED_TIME, nowMs: NOW });

  it("is unknown until a message has stated it", () => {
    expect(feedAgeSeconds(INITIAL_STATE, NOW)).toBeNull();
    expect(feedAgeSeconds(withFeed(null), NOW)).toBeNull();
  });

  it("is the age the server stated, plus the time since it said so", () => {
    const state = withFeed(20);

    expect(feedAgeSeconds(state, NOW)).toBe(20);
    expect(feedAgeSeconds(state, NOW + 7_000)).toBe(27);
  });

  it("keeps growing with no further messages, as it must while the connection is down", () => {
    const state = withFeed(20);

    // Frozen at the last good figure, a page that had lost the stream would look current.
    expect(feedAgeSeconds(state, NOW + 600_000)).toBe(620);
  });

  it("is not reset by a heartbeat that does not know the age", () => {
    const state = reduce(withFeed(20), { type: "heartbeat", feed: feed(null), serverTimeMs: FEED_TIME, nowMs: NOW + 5_000 });

    expect(feedAgeSeconds(state, NOW + 5_000)).toBe(25);
  });

  it("is never negative even if the clock steps backwards", () => {
    expect(feedAgeSeconds(withFeed(20), NOW - 5_000)).toBe(20);
  });
});

describe("how current the data is", () => {
  it.each([
    [null, "unknown"],
    [0, "fresh"],
    [60, "fresh"],
    [61, "stale"],
    [300, "stale"],
    [301, "lost"],
    [5_000, "lost"],
  ] as const)("calls an age of %s %s", (age, expected) => {
    expect(classifyFreshness(age)).toBe(expected);
  });
});

describe("the server's clock", () => {
  it("is unknown before any message", () => {
    expect(serverNowSeconds(INITIAL_STATE, NOW)).toBeNull();
  });

  it("is the server's time as of the last message plus the time since", () => {
    const state = reduce(INITIAL_STATE, { type: "heartbeat", feed: feed(5), serverTimeMs: 50_000_000, nowMs: NOW });

    expect(serverNowSeconds(state, NOW)).toBe(50_000);
    expect(serverNowSeconds(state, NOW + 4_000)).toBe(50_004);
  });

  it("does not depend on the browser's own clock being right", () => {
    // Two browsers whose clocks disagree by an hour, receiving the same message.
    const message = { type: "heartbeat", feed: feed(5), serverTimeMs: 50_000_000 } as const;
    const early = reduce(INITIAL_STATE, { ...message, nowMs: 1_000 });
    const hourFast = reduce(INITIAL_STATE, { ...message, nowMs: 1_000 + 3_600_000 });

    expect(serverNowSeconds(early, 1_000)).toBe(serverNowSeconds(hourFast, 1_000 + 3_600_000));
  });
});

describe("a vehicle's own staleness", () => {
  it("is judged against the server's time", () => {
    const old = vehicle("a", { reportedAt: 1_000 });

    expect(isVehicleStale(old, 1_000 + 120)).toBe(false);
    expect(isVehicleStale(old, 1_000 + 121)).toBe(true);
  });

  it("is never claimed when the server's time is not known", () => {
    expect(isVehicleStale(vehicle("a", { reportedAt: 1 }), null)).toBe(false);
  });
});

describe("the connection", () => {
  it("remembers when it was lost, from the first failure", () => {
    const lost = reduce(INITIAL_STATE, { type: "connection", status: "reconnecting", nowMs: 1_000 });
    const stillLost = reduce(lost, { type: "connection", status: "reconnecting", nowMs: 9_000 });

    // Each failed retry must not make it look as though the outage had just begun.
    expect(stillLost.disconnectedSinceMs).toBe(1_000);
  });

  it("forgets the loss once it is back", () => {
    const lost = reduce(INITIAL_STATE, { type: "connection", status: "reconnecting", nowMs: 1_000 });

    expect(reduce(lost, { type: "connection", status: "open", nowMs: 5_000 }).disconnectedSinceMs).toBeNull();
  });

  it("is not an outage merely because it is connecting", () => {
    expect(reduce(INITIAL_STATE, { type: "connection", status: "connecting", nowMs: 1_000 }).disconnectedSinceMs).toBeNull();
  });
});

describe("a new subscription", () => {
  it("empties the picture but keeps what is known about the feed", () => {
    const held = applied(
      INITIAL_STATE,
      { type: "snapshot", message: snapshot([vehicle("a")], 20), eventId: "5", nowMs: NOW },
    );

    const after = reduce(held, { type: "reset", nowMs: NOW + 1_000 });

    expect(after.vehicles.size).toBe(0);
    // An event id from the old question must never be offered to resume the new one.
    expect(after.lastEventId).toBeNull();
    // The feed's age describes the feed, not this subscription, and is still true.
    expect(feedAgeSeconds(after, NOW + 1_000)).toBe(21);
    expect(after.connection).toBe("connecting");
  });
});

describe("the store", () => {
  it("tells listeners when it changes and not after they unsubscribe", () => {
    const store = new LiveStore();
    let notified = 0;
    const unsubscribe = store.subscribe(() => {
      notified += 1;
    });

    store.dispatch({ type: "connection", status: "open", nowMs: NOW });
    unsubscribe();
    store.dispatch({ type: "connection", status: "reconnecting", nowMs: NOW });

    expect(notified).toBe(1);
    expect(store.getState().connection).toBe("reconnecting");
  });

  it("copes with a listener unsubscribing another while being notified", () => {
    const store = new LiveStore();
    const calls: string[] = [];
    let unsubscribeSecond = () => {};
    store.subscribe(() => {
      calls.push("first");
      unsubscribeSecond();
    });
    unsubscribeSecond = store.subscribe(() => calls.push("second"));

    store.dispatch({ type: "connection", status: "open", nowMs: NOW });

    // Neither is skipped nor called twice by the other's unsubscribing.
    expect(calls).toEqual(["first", "second"]);
  });
});
