# Live Streaming

How vehicle positions get from the ingestion cycle to a moving marker in the
browser: the protocol, the guarantees it makes, the limits it enforces, and where it
deliberately falls short of the original plan.

Figures quoted here are measured and dated in [performance.md](performance.md).

---

## Endpoints

Both are Server-Sent Events (`text/event-stream`) under `/api/v1/stream`.

| Endpoint | Carries | Parameters |
| --- | --- | --- |
| `GET /stream/vehicles` | the vehicle fleet, as a snapshot and then deltas | `routes` (up to 20 route ids, comma separated), `bbox` (`minLon,minLat,maxLon,maxLat`, at most 1° on a side), `lastEventId` |
| `GET /stream/network` | whole-network counts: vehicles tracked, routes reporting | `lastEventId` |

`Last-Event-ID` is honoured as a header, and also as the `lastEventId` query
parameter, because a browser's `EventSource` cannot set headers when a page has to
reopen a connection itself after an HTTP error.

Bad parameters are answered as RFC 7807 problem documents before any stream is
opened, including for a client that sent `Accept: text/event-stream`. An unknown
route id is a `404`.

## Events

| Event | `id` | When | Payload |
| --- | --- | --- | --- |
| `snapshot` | yes | on connect, and on any ingestion cycle after which deltas can no longer be proven correct | every matching vehicle, plus `fields` naming the array order |
| `vehicles` | yes | each cycle in which something a client can see changed | `changed` (vehicle arrays) and `removed` (ids) |
| `network` | yes | `/stream/network`, each cycle | `vehiclesTracked`, `routesReporting`, `activeAlerts` |
| `heartbeat` | no | every 15 s | `latestEventId` |

Every payload carries `serverTime` and `feed: { timestamp, ageSeconds }`. A client
works out how old its picture is from those, not from its own clock, so a laptop
with the wrong time still reports the right age.

A vehicle is a positional array rather than an object, to keep a full snapshot to
about 78 KB for a 1,500-vehicle fleet:

```
["id", "route", lat, lon, bearing, speed, occupancy, timestamp]
```

Latitude and longitude are rounded to six decimal places (about 11 cm). `route`,
`bearing`, `speed` and `occupancy` are `null` when the feed did not state them —
never zero, because a bearing of 0 is a confident northward heading. The snapshot's
`fields` list restates the order, so a client can detect a server whose order has
changed instead of silently drawing vehicles at the wrong coordinates.

`activeAlerts` is `null`, not `0`: alerts are ingested but not yet persisted or
counted, and zero would be a measurement Trackline has not made.

## What counts as a change

The server sends a delta entry for a vehicle only when it is worth drawing:

- it is new to the client;
- its route or occupancy changed;
- it moved more than 5 m from the position **last sent to that client**; or
- a minute has passed and its own timestamp has advanced — a keepalive, so a
  stationary vehicle is still seen to be reporting.

The 5 m is measured from the last position sent, not from the last reading, so a
vehicle creeping 2 m a cycle is still sent every third cycle instead of never. About
two thirds of the fleet changes each cycle in the real feed.

## Reconnecting without a gap

Every event id is the larger of the previous id plus one and the wall clock in
milliseconds, so ids are unique across a server restart and a stale id can never be
mistaken for a current one. The server keeps the last 30 events (about ten minutes
at the real cycle rate) in an in-process ring.

A client that reconnects with `Last-Event-ID` is replayed the events after it **only
if that replay can be proved complete**: each event records the id of the one before
it, and the chain from the client's id to the newest has to be unbroken. If it is
not — the id fell out of the ring, the server restarted, or the id is not one this
server issued — the client gets a fresh `snapshot` instead. It never gets a replay
that might be missing something.

The first publish after a restart is itself a `snapshot` event, because there is no
earlier fleet to diff against.

Joining is atomic. A client's opening snapshot (or replay) and its registration for
live events happen under one lock, so a cycle publishing at that instant is neither
missed nor delivered twice.

## Filtering

Filtering is done per client, when sending, against that client's `routes` and
`bbox`. Both edges are handled: a vehicle entering the view is sent, and a vehicle
that left it — by driving out of the box or by changing route — is sent as a
`removed` entry, judged from where the client last saw it. A filtered map therefore
does not accumulate ghosts.

The web client subscribes by route, not by box: it asks for the selected route, or
for nothing but the snapshot of the whole fleet when none is selected. The `bbox`
parameter is implemented, tested and available to other clients, but nothing in the
current UI uses it.

## Slow and dead clients

Each client has a bounded queue (32 events) drained on its own virtual thread. A
client that falls that far behind is evicted rather than allowed to hold memory or
slow the others, and is left to resume with `Last-Event-ID`. A render bug and an I/O
failure are told apart, so a defect in one client's filter is logged as a defect and
not mistaken for a disconnect.

Emitters are removed from the registry on completion, timeout and error. The tests
assert this by the registry's size, not by the absence of an exception.

## Limits

| Limit | Value | Property (`trackline.stream`) |
| --- | --- | --- |
| Concurrent streams | 500 | `max-subscribers` |
| Streams per remote address | 20 | `max-subscribers-per-client` |
| Heartbeat | 15 s | `heartbeat-interval` |
| Replay ring | 30 events | `ring-capacity` |
| Per-client queue | 32 events | `queue-capacity` |
| Connection lifetime | 30 min, then the browser reconnects | `emitter-timeout` |
| Movement threshold | 5 m | `move-threshold-metres` |
| Keepalive for a still vehicle | 60 s | `keepalive-interval` |

Over a cap the response is `429` with `Retry-After: 5`. Responses carry
`Cache-Control: no-cache` and `X-Accel-Buffering: no`, so a reverse proxy streams
them instead of buffering until the connection closes.

The per-address limit counts the address a connection comes from, which behind a load balancer is the balancer's, so the whole
site would share one allowance. The production profile therefore sets `server.forward-headers-strategy: native`: Tomcat takes the
visitor's address from `X-Forwarded-For`, but only when the connection arrives from a proxy on a private address, and from the right of
the header, where the balancer put it, so a visitor cannot choose their own address by sending the header. Against the real server over
HTTP, `ForwardedClientAddressIntegrationTest` shows visitors named by a trusted proxy being counted separately, one visitor still being
limited, a made-up leading address being ignored, and a connection with no forwarded address being counted against where it came from.
What the test cannot show is an actual load balancer: that the balancer's address falls within Tomcat's trusted private ranges and that it
appends to the header as documented are readings of its documentation (see [deployment.md](deployment.md#what-was-not-verified)).

A full replay ring of 1,500-vehicle events holds about 8-9 MB of heap. It is capped
by event count, not bytes, so a fleet several times larger would cost several times
that; the figure and its date are in [performance.md](performance.md).

## The browser

`StreamConnection` wraps `EventSource` and adds what it does not do:

- **A dropped network** the browser retries itself. The page shows "Reconnecting"
  and does not open a second source, which would double every event.
- **An HTTP error** — a `429`, a `503` while the API restarts — makes the browser
  give up for good. The class retries with exponential backoff, 1 s doubling to a
  15 s cap, with jitter so a crowd dropped together does not return together, and
  resumes from the last event id.
- **Silence.** A proxy that freezes a connection leaves it looking open. If nothing,
  not even a heartbeat, arrives for 45 s the source is closed and reopened.

`LiveStore` holds the fleet as a map keyed by vehicle id, applies snapshots and
deltas through a pure reducer, and keeps the last known state while disconnected.

### Showing vehicles honestly

- **Interpolation** is linear between two real reports and clamped at the newest
  one. A marker is never drawn ahead of the latest fix: there is no extrapolation
  and no dead reckoning, because that would invent precision the feed does not have.
  A new report retargets from where the marker is drawn, so it never jumps; a jump
  over 2 km snaps. The tween's duration is the measured gap between feed updates
  (an exponentially weighted average of feed timestamps, clamped to 5-40 s,
  starting from 20 s until two updates have been seen).
- **`prefers-reduced-motion`** snaps markers to their latest report.
- **Heading.** A vehicle with a bearing is an arrow rotated to it; one without is a
  dot, not an arrow pointing north.
- **Staleness.** A feed up to a minute old is "Live" with its age. Past a minute
  the chip says the data is old. Past five minutes it says "Last known positions"
  and every marker is drawn at 30 % opacity. A single vehicle whose own report is
  over two minutes old is drawn at half opacity.
- **Connection.** A lost connection shows "Reconnecting" with the age of the last
  update, still counting up. After 30 s it becomes "Live data unavailable.
  Retrying". The route panel's count changes from "tracked now" to "last known".
- **Assistive technology.** A single live region announces only state changes. The
  ticking age is hidden from it, so a screen reader is not interrupted every
  second.

## Where this differs from the original plan

| Plan | What was built | Why |
| --- | --- | --- |
| Ring buffer in Redis | in-process ring | Streaming reads the same snapshot ingestion already holds in memory. A Redis ring adds a round trip per reconnect and a second source of truth that can disagree with the first, and buys nothing while there is one API instance. It would be needed to scale to several; replays would then need a shared ring. |
| `freshness` event | feed age on every event | A separate event is a second thing to lose or reorder. The age travels with the data it describes. |
| Zustand store | a small observable store | The store is a reducer plus subscribe; a state library was not justified by it. |
| Playwright reconnect test | manual verification, recorded in the milestone report | There is no Playwright harness or seeded backend yet. Reconnect behaviour is covered by unit tests of `StreamConnection` and the status logic. |

## Known limitations

- A vehicle that drifts across the edge of a `bbox` within the 5 m suppression
  window can leave a ghost until its next reported change.
- Event ids order events within one server. They are not comparable between
  instances; a second API instance would need the shared ring described above.
- Alerts are not counted yet (`activeAlerts: null`).
- Latency figures in performance.md come from clients and server sharing one JVM and
  one machine, so they are an upper bound on what a client would see, not a model
  of a real network.
