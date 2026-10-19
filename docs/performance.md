# Measured Performance

Every figure here was measured on a real run and is dated. Nothing is estimated,
extrapolated or rounded in Trackline's favour. A number without an entry in this
file does not get published anywhere else.

**Environment for all measurements below**
Windows 11, Docker Desktop 29.2.0 (WSL2), PostgreSQL 16.4 with PostGIS 3.4 in a
container, Java 21.0.10 (Temurin), API run from source via Maven on the host.
A laptop, not a server.

---

## GTFS static import

**Measured 2026-09-30**, importing the live TTC archive
(`opendata_ttc_schedules.zip`, 36,417,654 bytes,
sha256 `b4f78c1e…`) end to end from the command line.

| Stage | Rows | Duration |
| --- | ---: | ---: |
| routes | 236 | 269 ms |
| stops | 9,409 | 835 ms |
| calendar_services | 10 | 13 ms |
| calendar_exceptions | 104 | 13 ms |
| trips | 138,036 | 6,360 ms |
| shape_points | 438,263 | 10,236 ms |
| stop_times | 4,357,307 | 160,082 ms |
| **Whole import** (resolve, download, hash, load, activate) | **4,943,365** | **184,701 ms** |

Row counts match the feed exactly and independently reproduce the figures
recorded in [data-sources.md](data-sources.md).

### Second run, with geometry derivation (2026-09-30)

A repeat import on an already-populated, warmed database, now including the
derived-network step:

| Stage | Rows | Duration |
| --- | ---: | ---: |
| trips | 138,036 | 3,046 ms |
| shape_points | 438,263 | 3,736 ms |
| stop_times | 4,357,307 | 74,802 ms |
| **Derivation** (shapes, stations, headways) | 1,571 + 108 + 20,945 | **3,097 ms** |
| **Whole import** | — | **86,630 ms** |

The two whole-import figures differ by more than a factor of two — 184.7 s against
86.6 s — for the same archive and the same row counts. The first was into an empty
database on a cold cache; the second replaced existing data on a warm one. Both are
recorded rather than averaged, because the honest statement is that this import
takes somewhere between one and a half and three minutes on this hardware, not that
it takes any single number.

Derivation is 3.6 % of the second run: building 1,571 linestrings, clustering 231
platforms into 108 stations and computing 20,945 headway entries costs almost
nothing next to loading stop_times.

**Re-importing an unchanged archive: 11,253 ms.** The archive is still downloaded
and hashed, but the SHA-256 matches the active version so nothing is parsed or
loaded. Since the TTC republishes monthly, this is the usual outcome of a run.

## Static API endpoints

**Measured 2026-10-02**, against the full imported network (236 routes, 9,409
stops, 1,571 shapes), 20 requests per endpoint over a reused connection, server
and database both on this machine.

| Endpoint | Mean | Max |
| --- | ---: | ---: |
| `GET /routes?size=50` | 8.1 ms | 13.7 ms |
| `GET /routes/501` | 19.2 ms | 25.6 ms |
| `GET /routes/501/shape?simplifyMetres=20` | 9.5 ms | 20.5 ms |
| `GET /routes/1/stops` | 15.2 ms | 23.1 ms |
| `GET /stops/nearby?radiusMetres=1000` | 7.5 ms | 23.6 ms |
| `GET /search?q=Kennedy` | 28.5 ms | 30.3 ms |

Measured over a reused connection on purpose: a fresh `curl` per request reported
77–151 ms, almost all of it process startup. That would have been a misleading
number to publish.

`EXPLAIN ANALYZE` confirms the spatial index carries the proximity query — a
bitmap index scan on `stops_location_gix` reduces 9,409 stops to the 46 within
500 m in 0.03 ms. Search is the slowest endpoint because it unions three trigram
queries; the route table is small enough (236 rows) that the planner correctly
prefers a sequential scan over the trigram index there.

## Realtime ingestion

### How often the TTC actually publishes

**Measured 2026-10-02**, polling all three feeds every 5 s for 5 minutes and
recording when each header timestamp advanced.

| Feed | Advances | Wall gap min / median / max | Payload |
| --- | ---: | --- | ---: |
| vehicles | 13 | 9 / **21** / 42 s | 84–85 KB |
| trips | 16 | 6 / **20** / 38 s | 473–480 KB |
| alerts | 16 | 6 / **20** / 39 s | 2 KB |

The poll intervals follow from this rather than from preference: **vehicles 10 s**
(about half the publish interval, so a new position is picked up within roughly ten
seconds), **trip updates 20 s** (matching the publish rate, because halving it would
cost five times the bandwidth on a 480 KB payload for predictions that move slowly),
**alerts 60 s**. Polling faster than a publisher updates buys nothing.

### Cycles against the live feed

**Measured 2026-10-03**, roughly 3.5 minutes of real ingestion at about 03:40
Toronto time.

| Feed | Outcome | Cycles | Duration min / median / max |
| --- | --- | ---: | --- |
| vehicles | SUCCESS | 5 | 41 / 56 / 190 ms |
| vehicles | NO_CHANGE | 7 | 16 / 22 / 28 ms |
| trip updates | SUCCESS | 5 | 41 / 60 / 105 ms |
| trip updates | NO_CHANGE | 1 | 43 ms |
| alerts | SUCCESS | 2 | 22 / 388 / 753 ms |

**Seven of twelve vehicle polls found nothing new**, which is what 10 s polling
against a ~20 s publish cadence should produce. That is the interval working as
intended, not waste: the alternative is picking up each publication up to 20 s late.

**Zero entities were rejected** across every cycle.

### What ingestion stores

| | |
| --- | ---: |
| Observations stored | 2,300 |
| Entities accepted across successful cycles | 3,895 |
| Distinct vehicles seen | 655 |
| Storage across partitions | 1,424 kB |
| Partitions created automatically | 3 (2026-10-02 … 04) |

Deduplication kept **59 %** of accepted readings; the rest were vehicles that had
not re-reported or had barely moved. Without it the table would grow by roughly
1,300 rows every ten seconds.

**Feed latency — the gap between a vehicle reporting and Trackline receiving it —
was a median of 31 s, with a maximum of 486 s.** The maximum is not a fault: a
parked vehicle reports rarely, so its reading is genuinely old when it arrives. This
is the number the freshness envelope exists to expose rather than hide.

## Placing vehicles on routes

How long it takes to project a whole cycle of vehicles onto their routes, and what
it adds to a cycle. Method and results of the placement itself are in
[route-progress.md](route-progress.md).

**Measured 2026-10-06, 21:46-21:59 Toronto time**, API running from source against
the live feed, the full imported network (470 primary shapes) and a database already
holding ~120,000 observations. Each cycle places about 1,500 vehicles, of which
about 940 are assigned a route.

### The two queries, in isolation

`EXPLAIN (ANALYZE)` against the real database on a batch of 950 route-assigned
vehicles (the latest reading of each):

| Query | Rows | Execution |
| --- | ---: | ---: |
| Project onto both directions' shapes (`ShapeProjectionRepository`) | 1,900 | 132 ms (including 12 ms of JIT) |
| Previous reading per vehicle (`PreviousReadingRepository`) | 950 | 7 ms |

The previous-reading plan shows partition pruning working: only today's partition is
read, and the other six report "never executed". That is the point of spelling the
timestamp window out as constant bounds as well as joining on it.

### Inside a running cycle

Read from the `trackline.analytics.projection.duration` timer as the difference
between two readings, over intervals in which nothing else was running on the
machine. One interval that overlapped a frontend test run read 305 ms and is not
used: it measured the contention, not the code.

| Condition | Quiet cycles | Mean projection time |
| --- | ---: | ---: |
| Server reusing a generic plan (before the fix below) | 8 | 266 ms |
| Planned with the real arrays every time (current) | 9 | **166 ms** |

**The 100 ms difference is a planner mistake, not the work.** After five executions
the PostgreSQL driver lets the server reuse one plan, and a reused plan cannot see
how long the arrays passed to `unnest` are. It assumed ten vehicles where there were
947, concluded a nested loop was cheap (estimated cost 3,053 against 280,674 for the
alternative), and re-read each wide shape row once per vehicle:

| Plan for the shape query, 947 vehicles | Execution |
| --- | ---: |
| Generic (what the driver switched to) | 207 ms |
| Planned with the real parameters (hash join) | 126 ms |

It showed in the live log as cycle times creeping from about 250 ms to about 400 ms
around the sixth cycle. Server-side preparation is now switched off for that one
statement, and a test pins it. The same comparison on the previous-reading query
found the generic plan no worse (5.9 ms against 11.2 ms), so that one is left alone.

### What it adds to a cycle

The whole vehicle cycle (fetch, parse, project, store, publish), `SUCCESS` cycles
from `ingestion_runs`:

| Period | Cycles | Vehicles | Median | p90 | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Before this milestone, 2026-10-04 15:30-16:21 EDT | 103 | ~1,291 | 138 ms | 223 ms | 1,624 ms |
| After, generic plan, 2026-10-06 21:47-22:00 EDT | 28 | ~1,508 | 388 ms | 492 ms | 1,097 ms |
| After, current, 22:03-22:10 EDT | 15 | ~1,500 | **303 ms** | 449 ms | 1,457 ms |

The median rose by about 165 ms, which is the measured cost of projection (166 ms)
almost exactly. A cycle now takes about 1.5% of the 20 s the publisher takes to
produce the next one.

These are **not identical conditions**: the earlier period is mid-afternoon with
about 16% fewer vehicles reporting. The comparison shows the order of magnitude and
that the added time is the projection, not that the two figures differ by exactly
this much for the same fleet.

About two-thirds of readings are stored each cycle (1,008 of 1,507 on average): the
rest are vehicles that have not moved 25 m or re-reported in 60 s. Placement is
computed for all of them, because spacing needs the whole fleet, but only stored
readings carry it into the table.

Two of ten consecutive vehicle cycles in one stretch failed on the 5 s read timeout
against `bustime.ttc.ca` ("Request cancelled"), before projection ran. Recorded as
`FAILED`, and the next cycle was normal. That is the failure handling working, and
a reminder that the publisher is not always prompt.

## Live vehicle endpoints

**Measured 2026-10-03** at about 12:40 Toronto time, with 1,459-1,462 vehicles in
the snapshot. 25 requests per endpoint over a reused connection.

| Endpoint | Mean | Median | Max |
| --- | ---: | ---: | ---: |
| `GET /vehicles` (all 1,462) | 63.3 ms | 55.9 ms | 143.5 ms |
| `GET /routes/501/vehicles` (32 of them) | 58.7 ms | 51.6 ms | 134.9 ms |
| `GET /vehicles?bbox=` (179 of them) | 46.3 ms | 39.5 ms | 134.4 ms |

The filtered endpoints are barely faster than the unfiltered one, and that is the
most useful thing these numbers say: the cost is deserialising the whole snapshot
on every request, not the Redis round trip or the filtering. Storing a key per
route would address it, at the price of write amplification and the risk of a
half-rewritten key set mixing two feed cycles. At 60 ms that trade is not worth
making yet; the measurement is here so it can be revisited against evidence.

The database fallback's query — the last tier — runs in **23.8 ms** against 2,327
rows using the per-route index, with 6.0 ms of planning. It will grow with
retention, which is why it is the fallback rather than the normal path.

### Behaviour during a cache outage

**Measured 2026-10-03** by stopping the Redis container while the API was serving.

| | |
| --- | --- |
| Response | still `200`, 1,459 vehicles |
| Provenance | `source: CACHED`, `degraded: true` |
| Health endpoint | stays `UP` |
| Ingestion | continues, 1,456 observations stored during the outage |
| Latency during outage | **0.54-0.56 s** |
| Recovery | automatic, back to `source: LIVE` on the next cycle |

That latency is the honest cost of the current design: every request pays the full
500 ms Redis timeout before falling through. The service stays up and stays honest,
but it gets ten times slower. A circuit breaker that stops attempting Redis after
repeated failures would fix it, and is not built yet.

### Storage

| Item | Size |
| --- | ---: |
| Database total after import | 719 MB |
| `stop_times` including indexes | 598 MB |
| `route_shapes` including indexes | 19 MB |
| `route_scheduled_headways` | 2.3 MB |

### Notes on the numbers

`stop_times` is 87 % of the import time and 86 % of the storage. Its per-row cost
is dominated by the two foreign keys, which are validated for all 4.36 million
rows inside the import transaction. That was a deliberate choice — correctness
first — and the documented option if it becomes a problem is to drop and recreate
those constraints within the same transaction.

The import is a monthly operator task, not a request path, so a few minutes is
acceptable today. It is recorded here so that a future change can be judged
against a real baseline rather than a memory of one.

---

## Live streaming

**Measured 2026-10-07** by `StreamFanoutMeasurementTest`, which runs a real HTTP
server, 25 real SSE clients and 30 publish cycles of a synthetic fleet shaped like
the real one: 1,500 vehicles, 1,000 of them moving 30 m per cycle. Clients and
server share one JVM on one laptop, so each client's own parsing is included in the
latencies. They are an upper bound on what a client would see, not a model of a
network. The fleet is synthetic because the point is the fanout, not the feed; the
size and the share that moves per cycle are taken from the real feed.

| Measure | Run 1 | Run 2 |
| --- | ---: | ---: |
| Snapshot on connect | 77,911 B | 77,911 B |
| Delta, median / max | 51,705 / 78,045 B | 51,705 / 78,045 B |
| `publish()` p50 / p95 / max | 2.40 / 11.39 / 15.63 ms | 1.65 / 26.90 / 68.52 ms |
| Publish to received, p50 / p95 / max | 11.8 / 21.8 / 25.9 ms | 20.4 / 57.4 / 92.0 ms |
| Deliveries (25 clients x 30 cycles) | 750, none lost | 750, none lost |
| Replay ring of 30 events | about 8-9 MB | about 8.2 MB |

Run 2 was taken during a full `./mvnw verify` with the development API also running
on the same machine, which is the likely reason its tails are longer; run 1's
conditions were not recorded. Both are published rather than the better one, because
the spread between them is the honest picture of a laptop. Sizes do not vary, since
they depend only on the data.

What these say:

- The cost of a cycle is a few milliseconds of computation and then the writes to
  clients. With 25 clients, the slowest delivery in either run was under 100 ms,
  against a feed that publishes about every 20 s.
- A delta is two thirds the size of a snapshot because two thirds of the fleet
  moves each cycle. Deltas pay off less for the whole fleet than for a client
  watching one route, which is what the web page does when a route is selected:
  server-side filtering is the larger saving and is why the page subscribes by route.
- The replay ring is bounded by event count, not bytes. It costs about 8 MB at this
  fleet size, and would grow with the fleet.

Not measured: more than 25 clients, a real network, or a browser's rendering cost.
The 500-connection cap is a configured limit, not a measured capacity.

### In the browser

**Measured 2026-10-07** against the live TTC feed through the API, in the desktop
app's embedded browser, with about 1,800 vehicles streaming. The map drew all of
them as arrow and dot markers, and a vehicle's drawn position advanced smoothly
between reports (four samples over 14 s moved it steadily along its street). No frame
rate or `setData` cost was recorded, so none is claimed.

---

## Service alerts

**Measured 2026-10-12** on the live TTC feed, with the API run from source on the same
laptop as the database. Eleven alerts, 97 route references, 99 stored entity rows.

### Cycles

Seven consecutive alert cycles, one a minute, from the dev run's `ingestion_runs`:

| Cycle | Duration | What it did |
| --- | ---: | --- |
| 1 | 828 ms | opened all 11 alerts (cold: first use of the new tables and statements) |
| 2-7 | 25, 25, 30, 26, 27, 37 ms | confirmed all 11, nothing opened, changed or resolved |

The steady state is a median of **26.5 ms** for a cycle that fetches, parses, validates,
fingerprints and reconciles the alerts and writes the confirmations. Seven cycles is a small
sample, and cycles 4-7 ran while a full `./mvnw verify` was using the same machine, so read
the tail as "tens of milliseconds", not as a distribution.

Every cycle was a `SUCCESS` and none was `NO_CHANGE`: the alerts publisher's header
timestamp advances roughly every 20 s, so at a 60 s poll the payload is always new. The
`NO_CHANGE` path (which only advances `last_seen_at`) exists for a publisher that stalls.

### Endpoints

25 requests each, over `curl` to localhost with a new connection per request (so these
include connection setup), before the verify run started:

| Endpoint | Min | Median | Max |
| --- | ---: | ---: | ---: |
| `GET /alerts?size=100` (11 alerts) | 11.6 ms | 17.3 ms | 46.9 ms |
| `GET /alerts?route=501` (1 alert) | 10.4 ms | 12.9 ms | 30.7 ms |

An alert read is two queries however many alerts a page holds: the page, then every route
and stop it names in one statement. Not measured: a large history of resolved alerts, which
is why retention matters (now in place, see the history section below), or any load beyond one client.

### Storage

At 11 alerts and 99 entity rows each table, indexes included, is **64 kB**, which is the
smallest size PostgreSQL reports for a table with indexes and says nothing about growth.
Growth depends on how often the TTC's alerts turn over, which has not been measured over
more than a few minutes.

---

## Route health

**Measured 2026-10-12** on the live TTC feed, API from source on the same laptop as the
database, about 13 minutes after start, with around 1,000 vehicles in each snapshot.

| | |
| --- | --- |
| Analysis passes | 26 |
| Mean time per pass | **18.6 ms** |
| Slowest pass | 20.0 ms |
| For comparison, projection onto routes in the same cycles (mean) | 159.6 ms |

A pass reads the placed vehicles the cycle already holds and two small tables (470 primary
shapes and at most as many scheduled headways for one hour), so it adds roughly a tenth to a cycle
that already spends about 160 ms placing vehicles. The sample is small and the maximum is the
only tail there is, so read it as "tens of milliseconds".

| Endpoint | Min | Median | Max |
| --- | ---: | ---: | ---: |
| `GET /routes/501/health` | 7.1 ms | 8.9 ms | 38.5 ms |
| `GET /health/routes` (155 routes, 31 KB) | 8.9 ms | 10.9 ms | 36.8 ms |

25 requests each, over `curl` to localhost with a new connection per request, so these include
connection setup. Both are a Redis read and a small amount of mapping; the list adds one query to
name the routes. Not measured: the Redis-down path, or load beyond one client.

## Bunching detection

**Measured 2026-10-13**, evening rush, on the live TTC feed, API from source on the same laptop as the
database. This is a loaded run and the cost of detection could not be separated from it.

The detector shares the route health pass: both read the same vehicles, sorted once, and the strip
is published beside the verdict. The pass was **18.6 ms** on average in the route health measurement above (2026-10-12, about
1,000 vehicles). Measured again on the 2026-10-13 run, with bunching in the pass, it averaged **278 ms** over 26
passes (slowest 1.4 s) and 84 ms over the next two. That is not the cost of bunching. In the same cycles
projection onto routes rose from about 160 ms to about 660 ms on average, roughly four times, and the snapshot held
more vehicles (1,813 vehicles on 184 routes at the evening peak, against about 1,000 earlier), so the host or the feed was
simply busier. The one safe reading is that the pass stayed under a second at the peak. A clean figure needs both builds run
back to back at the same hour, which was not done.

| Endpoint | Min | Median | Max |
| --- | ---: | ---: | ---: |
| `GET /routes/501/bunching` (about 5.0 KB) | 24.4 ms | 28.6 ms | 392 ms |

25 requests over `curl` to localhost with a new connection per request, so these include connection
setup, measured at the same loaded time as the pass above. The response combines a Redis read of the spacing
document with one indexed query for the route's events (open ones, and those resolved in the last 24 hours), which is why it is
slower than the health endpoints. Not measured: the Redis-down path, or more than one client.

Storage: events are written once and updated in place, 306 rows over the 21-minute window in
[bunching-detection.md](bunching-detection.md). Resolved ones are deleted after 180 days.

## History and reliability

**Measured 2026-10-18**, Sunday evening, 19:30 to 20:19 Toronto time, on the live feed, API from source on the same
laptop as the database. The full account, with what it costs to keep and what the samples say, is in
[history-and-reliability.md](history-and-reliability.md); these are the figures that bear on speed.

| | |
| --- | --- |
| Recording a health sample (42 samples, 274 rows each on average) | mean **37 ms**, maximum 142 ms (the first, which creates the partitions) |
| Vehicle cycles in the same run (99) | mean 945 ms, maximum 5.8 s (the first) |
| Hourly summary of one finished hour (321 rows from 6,911 samples) | 14 ms, and 53 ms when rebuilt |
| `GET /routes/501/reliability`, 25 requests, `INSUFFICIENT_HISTORY` path | min 16.4 ms, median 19.3 ms, max 28.0 ms, 659 bytes |

A sample is written about once in every two or three cycles, so it adds roughly 15 ms to the average cycle; the figure is the write alone and
the run is too short and too quiet to see it in the cycle total. The cycle mean here is the whole cycle (fetch, projection,
analysis, storage) and is not comparable to the milestone 14 and 15 figures, which were taken at different hours with
different vehicle counts.

Storage is the larger finding. A stored position cost 353 bytes and a health sample 189, and at the rates seen the plan's 14 days
of positions and 90 days of samples would hold about 19 GB between them (about 13.5 GB and 5.7 GB). That is an extrapolation
from one quiet evening, and the page says so; it is not a measurement of a day, and it does not fit a 20 GB database.

Not measured: the available path of the reliability endpoint over a full window, a trim that drops a partition under load
(it was tested against held locks, not under ingestion), and anything over more than one hour of continuous running.
