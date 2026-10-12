import type { ConnectionStatus } from "@/lib/live/types";

/**
 * The part of the browser's EventSource this uses, so a fake can stand in for it in
 * tests. It has no network, and the failure paths are the ones that matter.
 */
export type EventSourceLike = {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
};

export type EventSourceFactory = (url: string) => EventSourceLike;

/** EventSource.CLOSED, spelled out because jsdom provides no EventSource to read it from. */
const CLOSED = 2;

export type StreamConnectionOptions = {
  /** The address to open, given the id to resume from, or null to start afresh. */
  url: (resumeFrom: string | null) => string;
  /** The names of the events to listen for. EventSource has no "any event". */
  eventNames: readonly string[];
  onEvent: (name: string, data: string, id: string | null) => void;
  onStatus: (status: ConnectionStatus) => void;
  factory?: EventSourceFactory;
  /**
   * Silence for this long means the connection is dead, though nothing said so.
   * A proxy that stops forwarding leaves a connection open and quiet forever, and
   * the heartbeat exists so that quiet can be told apart from a healthy idle.
   */
  silenceLimitMs?: number;
  backoff?: { initialMs: number; maxMs: number };
  random?: () => number;
};

const DEFAULT_SILENCE_LIMIT_MS = 45_000;
const DEFAULT_BACKOFF = { initialMs: 1_000, maxMs: 15_000 };

/**
 * One live connection, kept alive.
 *
 * EventSource reconnects by itself when the network drops, and resends the last event
 * id so the server can resume. What it does not do is retry after an HTTP error: a
 * 429 or a 503 closes it for good, silently. And it cannot tell a healthy quiet
 * connection from one a proxy has frozen. This class covers both: it reopens a closed
 * source with backoff, and abandons one that has gone silent.
 */
export class StreamConnection {
  private readonly options: StreamConnectionOptions;
  private readonly factory: EventSourceFactory;
  private readonly silenceLimitMs: number;
  private readonly backoff: { initialMs: number; maxMs: number };
  private readonly random: () => number;

  private source: EventSourceLike | null = null;
  private lastEventId: string | null = null;
  private attempts = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private watchdog: ReturnType<typeof setTimeout> | null = null;
  /** Bumped on every open and on stop, so callbacks from a source since replaced are ignored. */
  private generation = 0;
  private running = false;

  constructor(options: StreamConnectionOptions) {
    this.options = options;
    this.factory = options.factory ?? ((url) => new EventSource(url));
    this.silenceLimitMs = options.silenceLimitMs ?? DEFAULT_SILENCE_LIMIT_MS;
    this.backoff = options.backoff ?? DEFAULT_BACKOFF;
    this.random = options.random ?? Math.random;
  }

  start(): void {
    this.running = true;
    this.attempts = 0;
    this.options.onStatus("connecting");
    this.open();
  }

  /**
   * Opens a fresh subscription, forgetting where the last one got to.
   *
   * For when what is being asked for changes, such as a different route. A resume
   * would deliver events for the old question, so it must not be offered.
   */
  restart(): void {
    this.shutdown();
    this.lastEventId = null;
    this.start();
  }

  stop(): void {
    this.running = false;
    this.shutdown();
  }

  private open(): void {
    const generation = ++this.generation;
    const source = this.factory(this.options.url(this.lastEventId));
    this.source = source;

    source.onopen = () => {
      if (generation !== this.generation) {
        return;
      }
      this.attempts = 0;
      this.options.onStatus("open");
      this.armWatchdog(generation);
    };

    for (const name of this.options.eventNames) {
      source.addEventListener(name, (event) => {
        if (generation !== this.generation) {
          return;
        }
        // The browser keeps the last id across events that carry none, such as a
        // heartbeat, so a non-empty value is always the newest one seen.
        if (event.lastEventId) {
          this.lastEventId = event.lastEventId;
        }
        this.armWatchdog(generation);
        this.options.onEvent(name, event.data, event.lastEventId || null);
      });
    }

    source.onerror = () => {
      if (generation !== this.generation) {
        return;
      }
      this.clearWatchdog();
      this.options.onStatus("reconnecting");

      if (source.readyState === CLOSED) {
        // The browser has given up (an HTTP error, not a dropped connection), and will
        // not try again. Trying again is now this class's job.
        this.scheduleRetry();
      }
      // Otherwise the browser is retrying by itself, and opening a second source
      // alongside it would double every event.
    };
  }

  private armWatchdog(generation: number): void {
    this.clearWatchdog();
    this.watchdog = setTimeout(() => {
      if (generation !== this.generation) {
        return;
      }
      // Nothing, not even a heartbeat, for far longer than the server's interval.
      this.options.onStatus("reconnecting");
      this.closeSource();
      this.scheduleRetry();
    }, this.silenceLimitMs);
  }

  private scheduleRetry(): void {
    if (!this.running) {
      return;
    }
    this.closeSource();

    // Exponential, capped, and jittered. Without the jitter every client dropped by one
    // server restart comes back in the same instant and knocks it over again.
    const base = Math.min(this.backoff.initialMs * 2 ** this.attempts, this.backoff.maxMs);
    const delay = base * (1 + this.random() * 0.25);
    this.attempts += 1;

    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.running) {
        this.open();
      }
    }, delay);
  }

  private shutdown(): void {
    this.clearWatchdog();
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.closeSource();
    this.generation += 1;
  }

  private closeSource(): void {
    if (this.source !== null) {
      this.source.onopen = null;
      this.source.onerror = null;
      this.source.close();
      this.source = null;
    }
  }

  private clearWatchdog(): void {
    if (this.watchdog !== null) {
      clearTimeout(this.watchdog);
      this.watchdog = null;
    }
  }
}
