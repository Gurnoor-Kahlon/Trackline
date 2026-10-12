import { STALE_AFTER_SECONDS } from "@/lib/format/age";
import type { DeltaMessage, SnapshotMessage } from "@/lib/live/protocol";
import type { ConnectionStatus, FeedInfo, Freshness, LiveVehicle } from "@/lib/live/types";

/**
 * The picture of the fleet the page holds, and the pure rules for changing it.
 *
 * A reducer rather than component state because the rules matter: a snapshot replaces
 * everything, a change patches, an out-of-order reading is ignored, and all of it must
 * be checkable without rendering anything.
 */

/**
 * Beyond this the feed is `lost`: positions are last-known, not live. The stale
 * threshold, one minute, is shared with the rest of the app and lives with age formatting.
 */
export const LOST_AFTER_SECONDS = 300;

/** A vehicle whose own report is older than this is dimmed even when the feed is fresh. */
export const VEHICLE_STALE_AFTER_SECONDS = 120;

/**
 * The server's clock as of the last message, with when this page received it.
 *
 * Ages are worked out against this and not the browser's own clock, so a computer set
 * to the wrong time still shows the right age. Between messages the page advances it by
 * its own elapsed time, which is accurate to the clock's drift over seconds.
 */
export type ServerClock = {
  serverMs: number;
  receivedAtMs: number;
};

export type LiveState = {
  vehicles: ReadonlyMap<string, LiveVehicle>;
  /** The id of the last event applied, sent back to resume without a new snapshot. */
  lastEventId: string | null;
  feed: { info: FeedInfo; receivedAtMs: number } | null;
  clock: ServerClock | null;
  connection: ConnectionStatus;
  /** When the connection was last lost, or null while it is up or has never been. */
  disconnectedSinceMs: number | null;
};

export const INITIAL_STATE: LiveState = {
  vehicles: new Map(),
  lastEventId: null,
  feed: null,
  clock: null,
  connection: "connecting",
  disconnectedSinceMs: null,
};

export type LiveAction =
  | { type: "snapshot"; message: SnapshotMessage; eventId: string | null; nowMs: number }
  | { type: "delta"; message: DeltaMessage; eventId: string | null; nowMs: number }
  | { type: "heartbeat"; feed: FeedInfo; serverTimeMs: number; nowMs: number }
  | { type: "connection"; status: ConnectionStatus; nowMs: number }
  /** A new subscription is starting, so nothing held belongs to it. */
  | { type: "reset"; nowMs: number };

export function reduce(state: LiveState, action: LiveAction): LiveState {
  switch (action.type) {
    case "snapshot": {
      const vehicles = new Map<string, LiveVehicle>();
      for (const vehicle of action.message.vehicles) {
        vehicles.set(vehicle.id, vehicle);
      }
      return withFeed(
        { ...state, vehicles, lastEventId: action.eventId ?? state.lastEventId },
        action.message.feed,
        action.message.serverTimeMs,
        action.nowMs,
      );
    }

    case "delta": {
      const vehicles = new Map(state.vehicles);
      for (const incoming of action.message.changed) {
        const held = vehicles.get(incoming.id);
        // An older reading than the one held is a straggler, not news. Applying it
        // would move a vehicle backwards in time.
        if (held === undefined || incoming.reportedAt >= held.reportedAt) {
          vehicles.set(incoming.id, incoming);
        }
      }
      for (const id of action.message.removed) {
        vehicles.delete(id);
      }
      return withFeed(
        { ...state, vehicles, lastEventId: action.eventId ?? state.lastEventId },
        action.message.feed,
        action.message.serverTimeMs,
        action.nowMs,
      );
    }

    case "heartbeat":
      return withFeed(state, action.feed, action.serverTimeMs, action.nowMs);

    case "connection":
      return {
        ...state,
        connection: action.status,
        // Remembered from the moment of loss, so how long the page has been blind is
        // known, and not reset by each failed attempt to reconnect.
        disconnectedSinceMs:
          action.status === "open"
            ? null
            : action.status === "reconnecting"
              ? (state.disconnectedSinceMs ?? action.nowMs)
              : state.disconnectedSinceMs,
      };

    case "reset":
      // The feed's age is deliberately kept: it describes the feed, not this
      // subscription, and the page should not claim fresh data it has not got.
      return { ...INITIAL_STATE, feed: state.feed, clock: state.clock, connection: "connecting" };
  }
}

function withFeed(state: LiveState, feed: FeedInfo, serverTimeMs: number, nowMs: number): LiveState {
  return {
    ...state,
    feed: feed.ageSeconds === null ? state.feed : { info: feed, receivedAtMs: nowMs },
    clock: { serverMs: serverTimeMs, receivedAtMs: nowMs },
  };
}

/**
 * How old the feed is now, in seconds, or null if it has never been stated.
 *
 * The server's stated age plus the time since it said so. This keeps growing while the
 * connection is down, which is the honest behaviour: a page that has lost the stream
 * knows less every second, and must say so rather than freezing at the last good age.
 */
export function feedAgeSeconds(state: LiveState, nowMs: number): number | null {
  if (state.feed === null || state.feed.info.ageSeconds === null) {
    return null;
  }
  return state.feed.info.ageSeconds + Math.max(0, nowMs - state.feed.receivedAtMs) / 1000;
}

export function classifyFreshness(ageSeconds: number | null): Freshness {
  if (ageSeconds === null) {
    return "unknown";
  }
  if (ageSeconds > LOST_AFTER_SECONDS) {
    return "lost";
  }
  if (ageSeconds > STALE_AFTER_SECONDS) {
    return "stale";
  }
  return "fresh";
}

/** The server's time now, in epoch seconds, or null before any message has arrived. */
export function serverNowSeconds(state: LiveState, nowMs: number): number | null {
  if (state.clock === null) {
    return null;
  }
  return (state.clock.serverMs + Math.max(0, nowMs - state.clock.receivedAtMs)) / 1000;
}

/** True when the vehicle's own last report is old, whatever the feed's age. */
export function isVehicleStale(vehicle: LiveVehicle, serverNow: number | null): boolean {
  return serverNow !== null && serverNow - vehicle.reportedAt > VEHICLE_STALE_AFTER_SECONDS;
}
