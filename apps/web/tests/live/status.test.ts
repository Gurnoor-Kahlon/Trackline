import { describe, expect, it } from "vitest";

import { INITIAL_STATE, reduce, type LiveState } from "@/lib/live/liveState";
import { UNAVAILABLE_AFTER_SECONDS, describeLiveStatus } from "@/lib/live/status";

import { FEED_TIME, feed, snapshot, vehicle } from "./helpers";

const NOW = 5_000_000;

/** A page that has received a snapshot whose feed was `ageSeconds` old when it arrived. */
function live(ageSeconds: number | null, receivedAtMs = NOW): LiveState {
  let state = reduce(INITIAL_STATE, { type: "connection", status: "open", nowMs: receivedAtMs });
  state = reduce(state, {
    type: "snapshot",
    message: { ...snapshot([vehicle("a")], ageSeconds), feed: feed(ageSeconds) },
    eventId: "1",
    nowMs: receivedAtMs,
  });
  return state;
}

function lost(state: LiveState, atMs: number): LiveState {
  return reduce(state, { type: "connection", status: "reconnecting", nowMs: atMs });
}

describe("describing how live the data is", () => {
  it("says it is connecting before anything has arrived", () => {
    const status = describeLiveStatus(INITIAL_STATE, NOW);

    expect(status.label).toBe("Connecting to live data");
    expect(status.tone).toBe("muted");
  });

  it("says it is live, with how old, when the feed is recent", () => {
    const status = describeLiveStatus(live(12), NOW);

    expect(status.label).toBe("Live");
    expect(status.detail).toBe("updated 12s ago");
    expect(status.tone).toBe("live");
  });

  it("lets the age tick up as time passes", () => {
    const state = live(12);

    expect(describeLiveStatus(state, NOW + 20_000).detail).toBe("updated 32s ago");
  });

  it("says the data is old, in a warning tone, once the feed passes a minute", () => {
    const status = describeLiveStatus(live(90), NOW);

    expect(status.label).toBe("Data is 1m old");
    expect(status.tone).toBe("warning");
  });

  it("is still live at exactly a minute and stale just after", () => {
    expect(describeLiveStatus(live(60), NOW).label).toBe("Live");
    expect(describeLiveStatus(live(61), NOW).label).toContain("old");
  });

  it("says plainly that these are last-known positions after five minutes", () => {
    const status = describeLiveStatus(live(400), NOW);

    // Past this nothing on the map is live, and the label must not let it look so.
    expect(status.label).toBe("Last known positions");
    expect(status.detail).toBe("6m old");
    expect(status.tone).toBe("warning");
  });

  it("says it is waiting when connected but nothing has stated the feed's age", () => {
    const connected = reduce(INITIAL_STATE, { type: "connection", status: "open", nowMs: NOW });
    const withData = reduce(connected, { type: "snapshot", message: snapshot([], null), eventId: "1", nowMs: NOW });

    expect(describeLiveStatus(withData, NOW).label).toBe("Waiting for data");
  });

  describe("when the connection is lost", () => {
    it("says it is reconnecting and how old the last update was", () => {
      const state = lost(live(10), NOW + 2_000);

      const status = describeLiveStatus(state, NOW + 2_000);

      expect(status.label).toBe("Reconnecting");
      expect(status.detail).toBe("last update 12s ago");
      expect(status.tone).toBe("warning");
    });

    it("keeps ageing the last update while offline instead of freezing it", () => {
      const state = lost(live(10), NOW);

      expect(describeLiveStatus(state, NOW + 20_000).detail).toBe("last update 30s ago");
      expect(describeLiveStatus(state, NOW + 200_000).detail).toBe("last update 3m ago");
    });

    it("calls a long loss an outage, not a blip", () => {
      const state = lost(live(10), NOW);

      expect(describeLiveStatus(state, NOW + (UNAVAILABLE_AFTER_SECONDS - 1) * 1_000).label).toBe("Reconnecting");
      expect(describeLiveStatus(state, NOW + UNAVAILABLE_AFTER_SECONDS * 1_000).label).toBe(
        "Live data unavailable. Retrying",
      );
    });

    it("measures the outage from the first failure, not the latest retry", () => {
      let state = lost(live(10), NOW);
      state = lost(state, NOW + 25_000);
      state = lost(state, NOW + 29_000);

      // Each failed retry must not restart the clock and keep a long outage looking brief.
      expect(describeLiveStatus(state, NOW + 31_000).label).toBe("Live data unavailable. Retrying");
    });

    it("says reconnecting with no age when nothing was ever received", () => {
      const state = reduce(INITIAL_STATE, { type: "connection", status: "reconnecting", nowMs: NOW });

      const status = describeLiveStatus(state, NOW);

      expect(status.label).toBe("Reconnecting");
      expect(status.detail).toBeNull();
    });

    it("goes back to live when the connection returns", () => {
      const state = reduce(lost(live(10), NOW), { type: "connection", status: "open", nowMs: NOW + 5_000 });

      expect(describeLiveStatus(state, NOW + 5_000).label).toBe("Live");
    });
  });

  describe("what a screen reader is told", () => {
    it("does not change as the age ticks, so a live region stays quiet between events", () => {
      const state = live(12);

      const announcements = new Set(
        [0, 1_000, 5_000, 20_000, 40_000].map((offset) => describeLiveStatus(state, NOW + offset).announcement),
      );

      // "updated 12 seconds ago, 13, 14" read out aloud would be unusable.
      expect(announcements.size).toBe(1);
    });

    it("changes when the state does", () => {
      const fresh = describeLiveStatus(live(12), NOW).announcement;
      const stale = describeLiveStatus(live(90), NOW).announcement;
      const lastKnown = describeLiveStatus(live(400), NOW).announcement;
      const reconnecting = describeLiveStatus(lost(live(10), NOW), NOW).announcement;
      const outage = describeLiveStatus(lost(live(10), NOW), NOW + 60_000).announcement;

      expect(new Set([fresh, stale, lastKnown, reconnecting, outage]).size).toBe(5);
    });

    it("never promises live data when the positions are last-known", () => {
      expect(describeLiveStatus(live(400), NOW).announcement).toMatch(/last known/i);
      expect(describeLiveStatus(live(400), NOW).announcement).not.toMatch(/live data connected/i);
    });
  });

  it("uses the feed time from the server and not the browser clock", () => {
    // The feed says it is 12 s old; whatever the browser thinks the date is.
    const state = live(12, FEED_TIME + 999_000_000);

    expect(describeLiveStatus(state, FEED_TIME + 999_000_000).detail).toBe("updated 12s ago");
  });
});
