import type { FeedInfo, LiveVehicle } from "@/lib/live/types";

/**
 * Turns what the server sent into typed messages, refusing anything malformed.
 *
 * The stream is external input and is validated like any other: a field of the wrong
 * type, an out-of-range coordinate or a vehicle array of the wrong length rejects the
 * whole event rather than putting a vehicle in the ocean. A rejected event costs one
 * update; trusting it could corrupt the picture for as long as the page is open.
 */

/** The order of every vehicle array. The server restates it in each snapshot. */
export const VEHICLE_FIELDS = [
  "id",
  "route",
  "lat",
  "lon",
  "bearing",
  "speed",
  "occupancy",
  "timestamp",
] as const;

export class ProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProtocolError";
  }
}

export type SnapshotMessage = {
  kind: "snapshot";
  vehicles: LiveVehicle[];
  feed: FeedInfo;
  serverTimeMs: number;
};

export type DeltaMessage = {
  kind: "delta";
  changed: LiveVehicle[];
  removed: string[];
  feed: FeedInfo;
  serverTimeMs: number;
};

export type HeartbeatMessage = {
  kind: "heartbeat";
  feed: FeedInfo;
  serverTimeMs: number;
};

export type NetworkMessage = {
  kind: "network";
  vehiclesTracked: number;
  routesReporting: number;
  /** Null while alerts are not counted. Not zero: zero would claim they were. */
  activeAlerts: number | null;
  feed: FeedInfo;
  serverTimeMs: number;
};

export function parseSnapshot(raw: string): SnapshotMessage {
  const body = parseObject(raw);

  const fields = body["fields"];
  if (!Array.isArray(fields) || fields.length !== VEHICLE_FIELDS.length || fields.some((f, i) => f !== VEHICLE_FIELDS[i])) {
    // A server that reorders or renames the fields would otherwise be read with the
    // old order, and every vehicle would silently land in the wrong place.
    throw new ProtocolError("The snapshot's field list is not the one this client was built for.");
  }

  return {
    kind: "snapshot",
    vehicles: parseVehicles(body["vehicles"]),
    feed: parseFeed(body["feed"]),
    serverTimeMs: parseTime(body["serverTime"], "serverTime"),
  };
}

export function parseDelta(raw: string): DeltaMessage {
  const body = parseObject(raw);

  const removed = body["removed"];
  if (!Array.isArray(removed) || removed.some((id) => typeof id !== "string")) {
    throw new ProtocolError("removed must be a list of vehicle ids.");
  }

  return {
    kind: "delta",
    changed: parseVehicles(body["changed"]),
    removed: removed as string[],
    feed: parseFeed(body["feed"]),
    serverTimeMs: parseTime(body["serverTime"], "serverTime"),
  };
}

export function parseHeartbeat(raw: string): HeartbeatMessage {
  const body = parseObject(raw);
  return {
    kind: "heartbeat",
    feed: parseFeed(body["feed"]),
    serverTimeMs: parseTime(body["serverTime"], "serverTime"),
  };
}

export function parseNetwork(raw: string): NetworkMessage {
  const body = parseObject(raw);

  const alerts = body["activeAlerts"];
  if (alerts !== null && !isCount(alerts)) {
    throw new ProtocolError("activeAlerts must be a count or null.");
  }

  const vehiclesTracked = body["vehiclesTracked"];
  const routesReporting = body["routesReporting"];
  if (!isCount(vehiclesTracked) || !isCount(routesReporting)) {
    throw new ProtocolError("vehiclesTracked and routesReporting must be counts.");
  }

  return {
    kind: "network",
    vehiclesTracked,
    routesReporting,
    activeAlerts: alerts,
    feed: parseFeed(body["feed"]),
    serverTimeMs: parseTime(body["serverTime"], "serverTime"),
  };
}

function parseVehicles(rows: unknown): LiveVehicle[] {
  if (!Array.isArray(rows)) {
    throw new ProtocolError("Vehicles must be a list.");
  }
  return rows.map(parseVehicle);
}

function parseVehicle(row: unknown): LiveVehicle {
  if (!Array.isArray(row) || row.length !== VEHICLE_FIELDS.length) {
    throw new ProtocolError("A vehicle must be an array of exactly eight values.");
  }

  const [id, route, lat, lon, bearing, speed, occupancy, timestamp] = row as unknown[];

  if (typeof id !== "string" || id.length === 0) {
    throw new ProtocolError("A vehicle needs an id.");
  }
  if (route !== null && typeof route !== "string") {
    throw new ProtocolError(`Vehicle ${id}: route must be text or null.`);
  }
  if (!isNumberIn(lat, -90, 90) || !isNumberIn(lon, -180, 180)) {
    throw new ProtocolError(`Vehicle ${id}: coordinates are out of range.`);
  }
  if (bearing !== null && !isNumberIn(bearing, 0, 360)) {
    throw new ProtocolError(`Vehicle ${id}: bearing is out of range.`);
  }
  if (speed !== null && !isNumberIn(speed, 0, 200)) {
    throw new ProtocolError(`Vehicle ${id}: speed is out of range.`);
  }
  if (occupancy !== null && !isNumberIn(occupancy, 0, 20)) {
    throw new ProtocolError(`Vehicle ${id}: occupancy is out of range.`);
  }
  if (!isNumberIn(timestamp, 1, Number.MAX_SAFE_INTEGER)) {
    throw new ProtocolError(`Vehicle ${id}: timestamp is invalid.`);
  }

  return {
    id,
    routeId: route,
    latitude: lat,
    longitude: lon,
    bearing,
    speed,
    occupancy,
    reportedAt: timestamp,
  };
}

function parseFeed(value: unknown): FeedInfo {
  if (!isObjectLike(value)) {
    throw new ProtocolError("Every event states the age of the feed.");
  }

  const timestamp = value["timestamp"];
  const age = value["ageSeconds"];

  return {
    timestampMs: timestamp === null ? null : parseTime(timestamp, "feed.timestamp"),
    ageSeconds: age === null ? null : requireNonNegative(age, "feed.ageSeconds"),
  };
}

function parseObject(raw: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new ProtocolError("The event was not valid JSON.");
  }
  if (!isObjectLike(value)) {
    throw new ProtocolError("The event was not an object.");
  }
  return value;
}

function parseTime(value: unknown, name: string): number {
  if (typeof value !== "string") {
    throw new ProtocolError(`${name} must be a timestamp.`);
  }
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) {
    throw new ProtocolError(`${name} is not a valid timestamp.`);
  }
  return ms;
}

function requireNonNegative(value: unknown, name: string): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new ProtocolError(`${name} must be a non-negative number.`);
  }
  return value;
}

function isObjectLike(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNumberIn(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}
