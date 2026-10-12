import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  StreamConnection,
  type EventSourceLike,
  type StreamConnectionOptions,
} from "@/lib/live/streamConnection";
import type { ConnectionStatus } from "@/lib/live/types";

const CONNECTING = 0;
const OPEN = 1;
const CLOSED = 2;

/**
 * A stand-in for the browser's EventSource that the test drives by hand.
 *
 * There is no network in a unit test, and the interesting behaviour is exactly what a
 * network does badly: dropping, refusing, and going quiet.
 */
class FakeEventSource implements EventSourceLike {
  static instances: FakeEventSource[] = [];

  readyState = CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
    this.readyState = CLOSED;
  }

  // --- driven by the test ---

  open(): void {
    this.readyState = OPEN;
    this.onopen?.(new Event("open"));
  }

  emit(type: string, data: string, lastEventId = ""): void {
    const event = { data, lastEventId } as MessageEvent<string>;
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }

  /** The network dropped: the browser will retry by itself. */
  networkDrop(): void {
    this.readyState = CONNECTING;
    this.onerror?.(new Event("error"));
  }

  /** An HTTP error: the browser gives up and will not retry. */
  httpError(): void {
    this.readyState = CLOSED;
    this.onerror?.(new Event("error"));
  }

  static latest(): FakeEventSource {
    const latest = FakeEventSource.instances[FakeEventSource.instances.length - 1];
    if (latest === undefined) {
      throw new Error("No EventSource has been created");
    }
    return latest;
  }
}

describe("the stream connection", () => {
  let statuses: ConnectionStatus[];
  let events: { name: string; data: string; id: string | null }[];

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.instances = [];
    statuses = [];
    events = [];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function connection(overrides: Partial<StreamConnectionOptions> = {}): StreamConnection {
    return new StreamConnection({
      url: (resume) => `/stream${resume === null ? "" : `?lastEventId=${resume}`}`,
      eventNames: ["snapshot", "vehicles", "heartbeat"],
      onEvent: (name, data, id) => events.push({ name, data, id }),
      onStatus: (status) => statuses.push(status),
      factory: (url) => new FakeEventSource(url),
      // No jitter, so delays are exact.
      random: () => 0,
      ...overrides,
    });
  }

  describe("connecting", () => {
    it("starts as connecting and becomes open when the server answers", () => {
      const stream = connection();
      stream.start();
      expect(statuses).toEqual(["connecting"]);

      FakeEventSource.latest().open();

      expect(statuses).toEqual(["connecting", "open"]);
      stream.stop();
    });

    it("opens the address it was given, with nothing to resume from", () => {
      connection().start();

      expect(FakeEventSource.latest().url).toBe("/stream");
    });

    it("hands each named event on with its payload and id", () => {
      connection().start();
      const source = FakeEventSource.latest();
      source.open();

      source.emit("snapshot", "{}", "100");
      source.emit("heartbeat", "{}");

      expect(events).toEqual([
        { name: "snapshot", data: "{}", id: "100" },
        { name: "heartbeat", data: "{}", id: null },
      ]);
    });
  });

  describe("a dropped network", () => {
    it("is reported as reconnecting and left to the browser, which retries by itself", () => {
      connection().start();
      const source = FakeEventSource.latest();
      source.open();

      source.networkDrop();

      expect(statuses.at(-1)).toBe("reconnecting");
      // A second source opened alongside the browser's own retry would double every event.
      vi.advanceTimersByTime(60_000);
      expect(FakeEventSource.instances).toHaveLength(1);
    });

    it("is open again when the browser gets back", () => {
      connection().start();
      const source = FakeEventSource.latest();
      source.open();
      source.networkDrop();

      source.open();

      expect(statuses.at(-1)).toBe("open");
    });
  });

  describe("an HTTP error", () => {
    it("is retried by this class, because the browser never will", () => {
      connection().start();
      FakeEventSource.latest().httpError();

      expect(statuses.at(-1)).toBe("reconnecting");
      expect(FakeEventSource.instances).toHaveLength(1);

      vi.advanceTimersByTime(1_000);

      expect(FakeEventSource.instances).toHaveLength(2);
    });

    it("backs off exponentially up to a cap", () => {
      connection().start();
      const delays: number[] = [];

      for (let attempt = 0; attempt < 7; attempt++) {
        const before = FakeEventSource.instances.length;
        FakeEventSource.latest().httpError();
        let waited = 0;
        while (FakeEventSource.instances.length === before) {
          vi.advanceTimersByTime(100);
          waited += 100;
        }
        delays.push(waited);
      }

      // 1 s, 2, 4, 8, then held at the 15 s cap rather than growing without limit.
      expect(delays).toEqual([1_000, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000]);
    });

    it("adds jitter so a crowd dropped together does not return together", () => {
      connection({ random: () => 1 }).start();
      FakeEventSource.latest().httpError();

      vi.advanceTimersByTime(1_000);
      expect(FakeEventSource.instances).toHaveLength(1);

      vi.advanceTimersByTime(250);
      expect(FakeEventSource.instances).toHaveLength(2);
    });

    it("starts the backoff over once a connection succeeds", () => {
      connection().start();
      FakeEventSource.latest().httpError();
      vi.advanceTimersByTime(1_000);
      FakeEventSource.latest().httpError();
      vi.advanceTimersByTime(2_000);
      FakeEventSource.latest().open();

      FakeEventSource.latest().httpError();
      const before = FakeEventSource.instances.length;
      vi.advanceTimersByTime(1_000);

      expect(FakeEventSource.instances).toHaveLength(before + 1);
    });

    it("resumes from the last event id, in the query, since a header cannot be set", () => {
      connection().start();
      const first = FakeEventSource.latest();
      first.open();
      first.emit("vehicles", "{}", "555");

      first.httpError();
      vi.advanceTimersByTime(1_000);

      expect(FakeEventSource.latest().url).toBe("/stream?lastEventId=555");
    });

    it("closes the dead source before opening another", () => {
      connection().start();
      const first = FakeEventSource.latest();
      first.httpError();
      vi.advanceTimersByTime(1_000);

      expect(first.closed).toBe(true);
    });
  });

  describe("silence", () => {
    it("is treated as a dead connection, which a frozen proxy leaves looking open", () => {
      connection({ silenceLimitMs: 45_000 }).start();
      const first = FakeEventSource.latest();
      first.open();

      vi.advanceTimersByTime(45_000);

      expect(statuses.at(-1)).toBe("reconnecting");
      expect(first.closed).toBe(true);
      vi.advanceTimersByTime(1_000);
      expect(FakeEventSource.instances).toHaveLength(2);
    });

    it("is not triggered while heartbeats keep arriving", () => {
      connection({ silenceLimitMs: 45_000 }).start();
      const source = FakeEventSource.latest();
      source.open();

      for (let beat = 0; beat < 20; beat++) {
        vi.advanceTimersByTime(15_000);
        source.emit("heartbeat", "{}");
      }

      expect(statuses.at(-1)).toBe("open");
      expect(FakeEventSource.instances).toHaveLength(1);
    });

    it("is not judged while the browser is retrying by itself", () => {
      connection({ silenceLimitMs: 45_000 }).start();
      const source = FakeEventSource.latest();
      source.open();
      source.networkDrop();

      vi.advanceTimersByTime(120_000);

      // The browser owns the retry; this class opening a second source would race it.
      expect(FakeEventSource.instances).toHaveLength(1);
    });
  });

  describe("restarting for a different question", () => {
    it("opens a fresh connection and forgets where the last one got to", () => {
      let route = "501";
      const stream = connection({ url: (resume) => `/stream?route=${route}&resume=${resume ?? "none"}` });
      stream.start();
      const first = FakeEventSource.latest();
      first.open();
      first.emit("vehicles", "{}", "999");

      route = "504";
      stream.restart();

      // An id from the old question would resume the new one with the wrong events.
      expect(FakeEventSource.latest().url).toBe("/stream?route=504&resume=none");
      expect(first.closed).toBe(true);
      expect(statuses.at(-1)).toBe("connecting");
    });

    it("ignores anything a closed source says afterwards", () => {
      const stream = connection();
      stream.start();
      const old = FakeEventSource.latest();
      old.open();
      stream.restart();
      const eventsBefore = events.length;
      const statusesBefore = statuses.length;

      old.emit("snapshot", "{}", "1");
      old.open();
      old.httpError();

      expect(events).toHaveLength(eventsBefore);
      expect(statuses).toHaveLength(statusesBefore);
    });
  });

  describe("stopping", () => {
    it("closes the connection and cancels any pending retry", () => {
      const stream = connection();
      stream.start();
      const source = FakeEventSource.latest();
      source.httpError();

      stream.stop();
      vi.advanceTimersByTime(60_000);

      expect(source.closed).toBe(true);
      expect(FakeEventSource.instances).toHaveLength(1);
    });

    it("silences the watchdog", () => {
      const stream = connection({ silenceLimitMs: 45_000 });
      stream.start();
      FakeEventSource.latest().open();

      stream.stop();
      const statusesBefore = statuses.length;
      vi.advanceTimersByTime(120_000);

      expect(statuses).toHaveLength(statusesBefore);
    });
  });
});
