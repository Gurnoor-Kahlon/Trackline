import type { DeltaMessage, SnapshotMessage } from "@/lib/live/protocol";
import type { FeedInfo, LiveVehicle } from "@/lib/live/types";

export const FEED_TIME = Date.parse("2026-10-07T18:00:00Z");

export function vehicle(id: string, overrides: Partial<LiveVehicle> = {}): LiveVehicle {
  return {
    id,
    routeId: "501",
    latitude: 43.65,
    longitude: -79.38,
    bearing: 90,
    speed: 5,
    occupancy: 1,
    reportedAt: FEED_TIME / 1000,
    ...overrides,
  };
}

export function feed(ageSeconds: number | null = 5): FeedInfo {
  return {
    timestampMs: ageSeconds === null ? null : FEED_TIME,
    ageSeconds,
  };
}

export function snapshot(vehicles: LiveVehicle[], ageSeconds: number | null = 5): SnapshotMessage {
  return { kind: "snapshot", vehicles, feed: feed(ageSeconds), serverTimeMs: FEED_TIME + (ageSeconds ?? 0) * 1000 };
}

export function delta(changed: LiveVehicle[], removed: string[] = [], ageSeconds: number | null = 5): DeltaMessage {
  return {
    kind: "delta",
    changed,
    removed,
    feed: feed(ageSeconds),
    serverTimeMs: FEED_TIME + (ageSeconds ?? 0) * 1000,
  };
}

/** A vehicle as the wire carries it: a positional array. */
export function wireRow(overrides: unknown[] = []): unknown[] {
  const row: unknown[] = ["4501", "501", 43.635773, -79.473236, 217, 7.6, 1, 1_791_409_302];
  overrides.forEach((value, index) => {
    if (value !== undefined) {
      row[index] = value;
    }
  });
  return row;
}

export function wireEnvelope(extra: Record<string, unknown>): string {
  return JSON.stringify({
    serverTime: "2026-10-07T18:00:20Z",
    feed: { timestamp: "2026-10-07T18:00:00Z", ageSeconds: 20 },
    ...extra,
  });
}

export const FIELDS = ["id", "route", "lat", "lon", "bearing", "speed", "occupancy", "timestamp"];
