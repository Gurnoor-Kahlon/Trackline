/**
 * What the live streams give the frontend, once parsed.
 *
 * These are the frontend's own types, not the wire format: the wire carries vehicles
 * as positional arrays to keep events small, and parsing turns them into something a
 * person can read. Keeping the two apart means a change to the wire touches the
 * parser and nothing else.
 */

/** One vehicle, as the TTC reported it. Every field is published or null. */
export type LiveVehicle = {
  id: string;
  /** The route being worked, or null for a vehicle with no trip assignment. */
  routeId: string | null;
  latitude: number;
  longitude: number;
  /** Degrees clockwise from north, or null. Never defaulted to 0: due north is a claim. */
  bearing: number | null;
  /** Metres per second as published, or null. Frequently zero, so low-trust. */
  speed: number | null;
  occupancy: number | null;
  /** When the vehicle reported, in epoch seconds. */
  reportedAt: number;
};

/**
 * How old the feed was when the server sent a message.
 *
 * Both fields are null before anything has been ingested. Null means unknown, never
 * zero: a feed of unknown age must not be shown as brand new.
 */
export type FeedInfo = {
  timestampMs: number | null;
  ageSeconds: number | null;
};

/**
 * The state of the connection to the stream.
 *
 * `connecting` is the first attempt; `reconnecting` is every attempt after a loss.
 * They are different to read: one is a page loading, the other is something having
 * gone wrong.
 */
export type ConnectionStatus = "connecting" | "open" | "reconnecting";

/**
 * How current the live data is, for the person looking at it.
 *
 * - `fresh`: the feed is within a minute, which is what the API calls current.
 * - `stale`: older than that. Still shown, but said to be old.
 * - `lost`: older than five minutes. Positions are last-known, not live, and drawn so.
 * - `unknown`: nothing has been received, so there is no age to state.
 */
export type Freshness = "fresh" | "stale" | "lost" | "unknown";
