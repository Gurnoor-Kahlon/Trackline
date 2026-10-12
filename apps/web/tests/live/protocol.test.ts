import { describe, expect, it } from "vitest";

import {
  ProtocolError,
  parseDelta,
  parseHeartbeat,
  parseNetwork,
  parseSnapshot,
} from "@/lib/live/protocol";

import { FIELDS, wireEnvelope, wireRow } from "./helpers";
import { REAL_DELTA, REAL_HEARTBEAT, REAL_SNAPSHOT } from "./realEvents";

describe("what the real server sends", () => {
  it("parses a snapshot recorded from the live API", () => {
    const message = parseSnapshot(REAL_SNAPSHOT);

    expect(message.vehicles).toHaveLength(3);
    expect(message.vehicles[0]).toMatchObject({
      id: "4501",
      routeId: "501",
      latitude: 43.635773,
      longitude: -79.473236,
      bearing: 217,
    });
    expect(message.feed.ageSeconds).toBe(49);
    expect(message.feed.timestampMs).toBe(Date.parse("2026-10-07T21:42:18Z"));
    expect(message.serverTimeMs).toBe(Date.parse("2026-10-07T21:43:07.153Z"));
  });

  it("parses a change recorded from the live API", () => {
    const message = parseDelta(REAL_DELTA);

    expect(message.changed).toHaveLength(3);
    expect(message.removed).toEqual([]);
    expect(message.feed.ageSeconds).toBe(9);
  });

  it("parses a heartbeat recorded from the live API", () => {
    const message = parseHeartbeat(REAL_HEARTBEAT);

    expect(message.feed.ageSeconds).toBe(58);
  });
});

describe("snapshots", () => {
  it("refuses a field list it was not built for", () => {
    // Reordered: read with the old order, every vehicle would land in the wrong place.
    const reordered = [...FIELDS];
    [reordered[2], reordered[3]] = [reordered[3]!, reordered[2]!];
    const raw = wireEnvelope({ fields: reordered, vehicles: [wireRow()] });

    expect(() => parseSnapshot(raw)).toThrow(ProtocolError);
    expect(() => parseSnapshot(raw)).toThrow(/field list/);
  });

  it("refuses a snapshot with no field list at all", () => {
    expect(() => parseSnapshot(wireEnvelope({ vehicles: [] }))).toThrow(ProtocolError);
  });

  it("accepts an empty fleet", () => {
    expect(parseSnapshot(wireEnvelope({ fields: FIELDS, vehicles: [] })).vehicles).toEqual([]);
  });
});

describe("vehicles", () => {
  const parse = (row: unknown[]) => parseDelta(wireEnvelope({ changed: [row], removed: [] })).changed[0]!;

  it("keeps a vehicle's absent fields as null, never zero", () => {
    const bare = parse(wireRow([undefined, null, undefined, undefined, null, null, null]));

    // A vehicle with no route, heading or speed says so. A bearing of 0 would claim due north.
    expect(bare.routeId).toBeNull();
    expect(bare.bearing).toBeNull();
    expect(bare.speed).toBeNull();
    expect(bare.occupancy).toBeNull();
  });

  it.each([
    ["an id that is empty", wireRow([""])],
    ["an id that is not text", wireRow([42])],
    ["a route that is a number", wireRow([undefined, 501])],
    ["a latitude past the pole", wireRow([undefined, undefined, 91])],
    ["a longitude past the date line", wireRow([undefined, undefined, undefined, 181])],
    ["a latitude that is not a number", wireRow([undefined, undefined, "43.6"])],
    ["a bearing of 361", wireRow([undefined, undefined, undefined, undefined, 361])],
    ["a negative bearing", wireRow([undefined, undefined, undefined, undefined, -1])],
    ["a negative speed", wireRow([undefined, undefined, undefined, undefined, undefined, -3])],
    ["an absurd speed", wireRow([undefined, undefined, undefined, undefined, undefined, 900])],
    ["a timestamp of zero", wireRow([undefined, undefined, undefined, undefined, undefined, undefined, undefined, 0])],
    ["a timestamp that is not a number", wireRow([undefined, undefined, undefined, undefined, undefined, undefined, undefined, "now"])],
    ["a latitude of NaN", wireRow([undefined, undefined, Number.NaN])],
  ])("rejects %s", (_name, row) => {
    expect(() => parse(row)).toThrow(ProtocolError);
  });

  it("rejects a vehicle with too few or too many values", () => {
    expect(() => parse(wireRow().slice(0, 7))).toThrow(/eight values/);
    expect(() => parse([...wireRow(), "extra"])).toThrow(/eight values/);
  });

  it("rejects the whole event when one vehicle among many is bad", () => {
    const raw = wireEnvelope({ changed: [wireRow(), wireRow([undefined, undefined, 999])], removed: [] });

    // One bad vehicle means the payload cannot be trusted, so none of it is applied.
    expect(() => parseDelta(raw)).toThrow(ProtocolError);
  });

  it("accepts a bearing of exactly 0 and exactly 360", () => {
    expect(parse(wireRow([undefined, undefined, undefined, undefined, 0])).bearing).toBe(0);
    expect(parse(wireRow([undefined, undefined, undefined, undefined, 360])).bearing).toBe(360);
  });
});

describe("changes", () => {
  it("carries removed vehicle ids", () => {
    const message = parseDelta(wireEnvelope({ changed: [], removed: ["a", "b"] }));

    expect(message.removed).toEqual(["a", "b"]);
  });

  it("rejects removed ids that are not text", () => {
    expect(() => parseDelta(wireEnvelope({ changed: [], removed: [1] }))).toThrow(ProtocolError);
    expect(() => parseDelta(wireEnvelope({ changed: [], removed: "a" }))).toThrow(ProtocolError);
  });
});

describe("the feed's age", () => {
  it("is null when the server does not know it, never zero", () => {
    const raw = JSON.stringify({
      serverTime: "2026-10-07T18:00:20Z",
      feed: { timestamp: null, ageSeconds: null },
    });

    const message = parseHeartbeat(raw);

    expect(message.feed).toEqual({ timestampMs: null, ageSeconds: null });
  });

  it.each([
    ["a negative age", { timestamp: "2026-10-07T18:00:00Z", ageSeconds: -5 }],
    ["an age that is text", { timestamp: "2026-10-07T18:00:00Z", ageSeconds: "5" }],
    ["a timestamp that is not a date", { timestamp: "yesterday", ageSeconds: 5 }],
  ])("rejects %s", (_name, feed) => {
    const raw = JSON.stringify({ serverTime: "2026-10-07T18:00:20Z", feed });

    expect(() => parseHeartbeat(raw)).toThrow(ProtocolError);
  });

  it("is required on every event", () => {
    expect(() => parseHeartbeat(JSON.stringify({ serverTime: "2026-10-07T18:00:20Z" }))).toThrow(/age of the feed/);
  });

  it("needs a server time that is a real timestamp", () => {
    const raw = JSON.stringify({ serverTime: "soon", feed: { timestamp: null, ageSeconds: null } });

    expect(() => parseHeartbeat(raw)).toThrow(ProtocolError);
  });
});

describe("the network summary", () => {
  const network = (extra: Record<string, unknown>) =>
    wireEnvelope({ vehiclesTracked: 1500, routesReporting: 110, activeAlerts: null, ...extra });

  it("reads the counts", () => {
    const message = parseNetwork(network({}));

    expect(message.vehiclesTracked).toBe(1500);
    expect(message.routesReporting).toBe(110);
  });

  it("keeps unmeasured alerts as null, which is not the same as none", () => {
    expect(parseNetwork(network({ activeAlerts: null })).activeAlerts).toBeNull();
    expect(parseNetwork(network({ activeAlerts: 0 })).activeAlerts).toBe(0);
  });

  it.each([
    ["a negative count", { vehiclesTracked: -1 }],
    ["a fractional count", { routesReporting: 2.5 }],
    ["a count that is text", { vehiclesTracked: "1500" }],
    ["negative alerts", { activeAlerts: -2 }],
  ])("rejects %s", (_name, extra) => {
    expect(() => parseNetwork(network(extra))).toThrow(ProtocolError);
  });
});

describe("the payload itself", () => {
  it.each([
    ["text that is not JSON", "not json"],
    ["a JSON array", "[]"],
    ["a JSON string", '"hello"'],
    ["null", "null"],
  ])("rejects %s", (_name, raw) => {
    expect(() => parseHeartbeat(raw)).toThrow(ProtocolError);
  });
});
