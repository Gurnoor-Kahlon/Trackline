# Measured Performance

Every figure here was measured on a real run and is dated. Nothing is estimated,
extrapolated or rounded in Trackline's favour. A number without an entry in this
file does not get published anywhere else.

**Environment for the measurements below, unless a section says otherwise**
Windows 11, Docker Desktop 29.2.0 (WSL2), PostgreSQL 16.4 with PostGIS 3.4 in a
container, Java 21.0.10 (Temurin), API run from source via Maven on the host.
A laptop, not a server. The first section, the release pass, is the exception:
it ran the application as the production container images, and says so.

---

## Release measurement pass

**Measured 2026-10-19, between 13:30 and 14:15 Toronto time (17:30 to 18:15 UTC)**, a Monday afternoon, for the release of milestone 18.
This section differs from the rest of the file in one way that matters: the application ran as the **production container images built
from this repository's Dockerfiles**, under the `prod` profile, against **new, empty volumes**, with the schedule imported into them and the
live TTC feeds. Everything else in the file ran the API from source.

**Environment.** 13th-gen Intel Core i7-1355U (10 cores, 12 threads), 15.7 GB, Windows 11 Home, Docker Desktop 29.2.0 (WSL2, given 12 CPUs
and 7.6 GiB). PostgreSQL 16 with PostGIS 3.4 (`postgis/postgis:16-3.4`), Redis 7 (`redis:7-alpine`), the API on Temurin Java 21 (21.0.12 in the
container, 21.0.10 on the host where the tests ran), the web application on Node 24. A laptop, not a server. The development containers were idle
throughout. Where something else was running on the machine (a build, the tests), the section says so, because it changes the figures.

### Importing the schedule in the container

`docker compose --profile full run --rm api --import-gtfs`, into an empty database, downloading the live archive.

| | |
| --- | ---: |
| Whole import, as the importer reports it | **129,686 ms** |
| Wall time of the one-off container, including starting the JVM | 148.5 s |
| Rows loaded | 4,943,365 |
| Derived afterwards | 1,571 route shapes, 108 stations, 20,945 scheduled headways |
| Schedule window of the archive | 2026-09-30 to 2026-10-31 (so it covers the day of the run) |
| Run again, archive unchanged (`status=UNCHANGED`) | 2,539 ms |

Rows by table: 236 routes, 9,409 stops, 10 calendar services, 104 calendar exceptions, 138,036 trips, 438,263 shape points, 4,357,307 stop times.
These are the figures in [data-sources.md](data-sources.md).

Beside the two imports from 2026-09-30 below (184.7 s into an empty database on a cold cache, and 86.6 s replacing data on a warm one), this one took
129.7 s into an empty database in a container. All three are the same order, and the spread between them is the honest answer to "how long does it
take": between one and a half and three minutes on this laptop. An unchanged archive took 11.3 s on 2026-09-30 and 2.5 s here; the difference was
not investigated.

**What the already-running API did meanwhile.** While this import was loading, the API that was already running had two cycles in progress, a vehicle
cycle and a trip-update cycle that began at 13:30:53, and they took 117.1 s and 117.6 s: they ended when the import committed. Every other cycle in the
run took tens or hundreds of milliseconds. The API kept answering from its last snapshot, so a visitor would have seen the data age for two minutes,
not an error. A monthly import makes that a few minutes of staleness a month, and it is why [architecture.md](architecture.md) lists it as a limit. The
one-off import also polled the feeds itself until it was told not to (`TRACKLINE_GTFS_REALTIME_ENABLED=false`); a repeat with that setting logged no
cycles.

### Ingestion cycles

From `ingestion_runs` in the new database, with the API unconstrained, **in the quiet window 13:33:00 to 13:38:30** (the machine doing nothing else), at
about 1,590 vehicles, 1,880 trip updates and 11 alerts per cycle:

| Feed | Outcome | Cycles | Median | p95 | Max |
| --- | --- | ---: | ---: | ---: | ---: |
| Vehicles | SUCCESS | 11 | **508 ms** | 620 ms | 622 ms |
| Vehicles | NO_CHANGE | 21 | 22 ms | 30 ms | 38 ms |
| Trip updates | SUCCESS | 11 | 74 ms | 86 ms | 88 ms |
| Trip updates | NO_CHANGE | 5 | 60 ms | 71 ms | 73 ms |
| Alerts | SUCCESS | 5 | 21 ms | 27 ms | 28 ms |

A vehicle cycle that stores readings takes about half a second, most of it projecting vehicles onto routes, and the feed publishes a new one about every
20 s, so the cycle uses about 2.5% of that time. Two of every three vehicle polls found nothing new (21 `NO_CHANGE` against 11 `SUCCESS`), which is the 10 s
poll against the 20 s publisher doing what the interval was chosen to do.

The same query over **13:33 to 13:51, which included** the endpoint benchmark below, a full `./mvnw verify` (three minutes), and the two streaming
measurements, all on the same machine:

| Feed | Outcome | Cycles | Median | p95 | Max |
| --- | --- | ---: | ---: | ---: | ---: |
| Vehicles | SUCCESS | 36 | 494 ms | 1,807 ms | 3,120 ms |
| Vehicles | NO_CHANGE | 69 | 25 ms | 68 ms | 806 ms |
| Trip updates | SUCCESS | 36 | 72 ms | 599 ms | 714 ms |

The medians barely moved and the tails grew by a factor of three or more. That is what a laptop does when a build and the tests run beside it, and it is
published so that the quiet figures above are not read as what a busy machine does.

Over the whole run, 13:30 to 14:15, there were **422 cycles: 223 `SUCCESS`, 198 `NO_CHANGE` and 1 `FAILED`**. The failure was a vehicle fetch that timed out
connecting to `bustime.ttc.ca` after 3.2 s, at 13:52:21, seconds after the API container was recreated under the 0.5 CPU limit below, while its JVM was still
starting. In one steady trip-update cycle, 7 of 1,851 entities were rejected, each naming a route the imported schedule does not have.

### Read endpoints

`node scripts/bench.mjs --api http://localhost:8090 --requests 200 --warmup 20`: one client, one request at a time over a reused connection, 20 warm-up
requests not counted, against the production container with no resource limit, at 13:35 to 13:36 (inside the quiet window above). Latency is measured by the
client and includes reading the body.

| Endpoint | Source | Bytes | p50 | p95 | Max |
| --- | --- | ---: | ---: | ---: | ---: |
| `GET /network/status` | LIVE | 231 | 8.7 ms | 15.3 ms | 26.1 ms |
| `GET /routes?size=50` | STATIC | 6,999 | 9.0 ms | 14.2 ms | 20.2 ms |
| `GET /routes/501` | STATIC | 573 | 18.7 ms | 22.0 ms | 28.9 ms |
| `GET /routes/501/shape?simplifyMetres=20` | STATIC | 732 | 9.4 ms | 12.9 ms | 18.3 ms |
| `GET /routes/501/stops` | STATIC | 13,959 | 17.3 ms | 26.6 ms | 53.2 ms |
| `GET /stops/nearby` (1 km, downtown) | STATIC | 4,246 | 9.2 ms | 13.7 ms | 21.0 ms |
| `GET /search?q=Kennedy` | STATIC | 2,071 | 35.0 ms | 43.7 ms | 92.9 ms |
| `GET /vehicles` (whole fleet) | LIVE | 346,106 | 15.7 ms | 32.4 ms | 350.2 ms |
| `GET /routes/501/vehicles` | LIVE | 5,066 | 12.3 ms | 15.9 ms | 25.2 ms |
| `GET /routes/501/health` | LIVE | 1,293 | 9.2 ms | 14.4 ms | 66.3 ms |
| `GET /health/routes` (every route) | LIVE | 35,127 | 13.0 ms | 19.5 ms | 26.0 ms |
| `GET /routes/501/bunching` | LIVE | 2,731 | 10.3 ms | 14.7 ms | 21.1 ms |
| `GET /alerts?size=100` | STORED | 12,056 | 10.1 ms | 17.1 ms | 27.0 ms |
| `GET /routes/501/reliability?window=7d` | STORED | 605 | 8.2 ms | 13.9 ms | 20.1 ms |

The whole-fleet read, 346 KB, is 15.7 ms at the median here against 55.9 ms on 2026-10-03. They are not the same measurement (that was a development build run
from source, through a different client, with fewer vehicles), and this section does not claim a speed-up: the cause of the difference was not isolated. What
the rows do show is that no read is slow, and that the filtered reads are still not much cheaper than the whole fleet, as before.

**Redis hit ratio.** Redis's counters were reset immediately before the benchmark and read immediately after. Over the 14 endpoints, 3,080 requests, Redis
recorded **1,320 hits and 0 misses**, which is exactly the six live-state endpoints times their 220 requests each: every one of those reads found its document.
The other eight endpoints read PostgreSQL. This is the ratio for three keys that the API itself rewrites every cycle (the vehicle snapshot at about 442 KB, and
the route health snapshot and spacing at about 163 KB and 124 KB, each with a time to live), so a miss would mean the API had not yet written one. It is not the hit
ratio of a cache in front of a database, which would depend on how often a key could be evicted, and these cannot be.

### Live stream fanout, again

`StreamFanoutMeasurementTest`, as in [Live streaming](#live-streaming), run twice in a row at 13:49 and 13:50, **with the container stack (the API polling the live
feeds) running on the same machine and nothing else**: 25 SSE clients, 30 cycles, a synthetic fleet of 1,500 vehicles of which 1,000 move 30 m a cycle.

| Measure | Run 1 | Run 2 |
| --- | ---: | ---: |
| Snapshot on connect | 77,913 B | 77,913 B |
| Delta, median / max | 51,707 / 78,047 B | 51,707 / 78,047 B |
| `publish()` p50 / p95 / max | 1.63 / 11.40 / 13.16 ms | 1.68 / 8.40 / 10.08 ms |
| Publish to received, p50 / p95 / max | 11.1 / 20.9 / 27.9 ms | 10.5 / 24.2 / 35.2 ms |
| Deliveries (25 clients x 30 cycles) | 750 | 750 |
| Replay ring of 30 events | about 7.8 MB | about 8.2 MB |

As before, clients and server share a JVM and a machine, so these are an upper bound and not a model of a network, and 25 clients is the largest number tested.

### The planned task size: 0.5 vCPU and 1 GiB

The deployment plan runs the API in a task of that size, so at 13:51 the API container was recreated with `mem_limit: 1g` and `cpus: 0.5` and left to run against
the live feed with the same database. These are its figures **from 13:59:45 to 14:09:00**, after its first minutes of starting up (which included the one failed
cycle above), with nothing else running on the machine:

| Feed | Outcome | Cycles | Median | p95 | Max |
| --- | --- | ---: | ---: | ---: | ---: |
| Vehicles | SUCCESS | 20 | **393 ms** | 522 ms | 629 ms |
| Vehicles | NO_CHANGE | 35 | 25 ms | 63 ms | 93 ms |
| Trip updates | SUCCESS | 19 | 91 ms | 216 ms | 339 ms |
| Alerts | SUCCESS | 9 | 20 ms | 24 ms | 25 ms |

The medians are not worse than the unconstrained run's. They are not better in any way that means anything either: the samples are 20 and 11 cycles, from different
minutes, and the fleet is changing. What can be said is that a half-CPU task completes a vehicle cycle in well under a second, against a poll interval of ten.

| | |
| --- | ---: |
| Peak memory (cgroup `memory.peak`) | **436 MB** of 1 GiB |
| Memory at the end, resident | 413 MiB |
| CPU periods throttled over the whole run, including start-up | 1,049 of 5,945 (17.6%) |
| Out-of-memory events | none |

The same benchmark against that container (100 requests per endpoint after 20 warm-up) beside the unconstrained figures above:

| Endpoint | Unconstrained p50 / p95 | 0.5 vCPU p50 / p95 / max |
| --- | ---: | ---: |
| `GET /network/status` | 8.7 / 15.3 ms | 14.7 / 100.7 / 177.2 ms |
| `GET /routes/501/vehicles` | 12.3 / 15.9 ms | 17.5 / 89.2 / 100.6 ms |
| `GET /vehicles` (whole fleet) | 15.7 / 32.4 ms | 20.3 / 113.3 / 373.0 ms |
| `GET /routes/501/health` | 9.2 / 14.4 ms | 8.7 / 51.1 / 82.0 ms |
| `GET /routes/501/bunching` | 10.3 / 14.7 ms | 9.0 / 63.1 / 84.5 ms |
| `GET /search?q=Kennedy` | 35.0 / 43.7 ms | 30.7 / 36.5 / 85.0 ms |
| `GET /alerts?size=100` | 10.1 / 17.1 ms | 8.0 / 13.0 / 51.4 ms |

Medians are about what they were. The **tails are several times longer for the endpoints that read live state**, up to about 100 ms at the 95th percentile. The likely
reason is that a vehicle cycle uses the whole half-CPU for a few hundred milliseconds and a read arriving then has to wait for it; that agrees with the throttling count and
was not isolated. For one client at a time this is comfortable. It is a result for one client and says nothing about many at once.

### Test suites

| Suite | Result | Time |
| --- | --- | --- |
| API, `./mvnw verify` (this machine, 13:38) | 920 tests in 80 classes, 0 failures, 0 skipped, no compiler warnings under `-Xlint:all` | 3 min |
| Web unit, `pnpm test` | 716 tests in 49 files, all passing | 39 s |
| Web end to end, `pnpm e2e` | 29 tests, desktop and phone projects, all passing | 2.9 to 3.8 min, over two runs |

The API figure was taken before the last test classes were added; the final count is in the [README](../README.md#test-it).

### Images and builds

| | |
| --- | --- |
| API image, on disk | 610 MB (a Temurin JRE, the application jar of about 80 MB, and curl for the health check) |
| Web image, on disk | 393 MB |
| API image build after a change to its Dockerfile, Maven downloads included | 2 min 39 s |
| API image rebuild and restart after a source change | 53 s |
| Web image build | 58 s |
| `docker compose --profile full up -d --build --wait` from empty volumes, build cache warm | 38.7 s until every service was healthy |

### Storage after the run

After the import and about 40 minutes of live ingestion the database was **751 MB**, holding 86,291 stored positions, 9,880 health samples and 260 bunching events. That
is the same order as the figures in [history-and-reliability.md](history-and-reliability.md), whose warning about how quickly positions accumulate still stands: 14 days of
them is what a volume has to be sized for.

### Not measured

Concurrent load of any kind on the read endpoints; more than 25 stream clients; a real network between a client and the API; production hardware; anything behind a load
balancer; stability over more than the 45 minutes of this run; the web application's own rendering time.

---

## Hardening measurements (milestone 19)

**Measured 2026-10-19, in the evening (about 17:00 Toronto time)**, on the same laptop as the release pass, with the packaged API jar run from the command line
under the `prod` profile against a real PostgreSQL and the development Redis container, and the end-to-end suite's fixture feed standing in for the TTC (a
2 s vehicle poll). Not the live feed: what is measured is how the API behaves, not how the feed does.

### Redis down, with and without the circuit breaker

The same jar twice, once with the breaker effectively switched off (`breaker-failure-threshold` of 1,000,000) and once with its defaults (three failed calls in a
row, then ten seconds of not trying). In each, `GET /api/v1/vehicles` was timed 30 times in a row, one at a time, starting the moment the Redis container was
stopped, and then polled until the answer was `LIVE` again after the container was started.

| | Breaker off | Breaker on (default) |
| --- | ---: | ---: |
| The first three requests after Redis stops | 531.6, 524.9, 534.7 ms | 531.0, 527.1, 517.6 ms |
| Every request after that | about 510 to 540 ms | **roughly 6 to 12 ms** |
| 30 requests, total | 15.6 s | **1.84 s** |
| 30 requests, median | 518.9 ms | **10.0 ms** |
| Every answer | `200`, `CACHED`, `degraded: true` | `200`, `CACHED`, `degraded: true` |
| Back to `LIVE` after Redis was started | 3.2 s | **10.4 s** |
| Redis up, for comparison | 44.9 ms | 51.5 ms |

The first three requests are the ones that find out, and pay the 500 ms timeout each; after that the breaker answers from the fallback at once. **The price is the
last row**: the breaker does not look again until its ten-second pause is over, so recovery is slower by about the length of the pause. That is the trade the
setting `breaker-open-for` controls, and ten seconds is a starting point, not a tuned value: short enough that a map recovers within a person's patience, long enough that a Redis that is down
costs almost nothing. The breaker's log shows it opening (`Redis failed 3 times running; not trying it for PT10S`), one call let through when the pause ended, and its closing
within 7 ms of that call (`Redis answered again; using it`). A late failure of a call that was already running when the circuit opened was first found to log the
outage again and to push the end of the pause out; it no longer does, and a unit test holds that.

Ingestion keeps writing history throughout, and its own Redis writes stop costing 500 ms a time while the circuit is open, which the figures above do not show.

### A standby taking over

Two API processes on one database, the first started first. `pg_locks` showed one advisory lock, held by the first; in 20 s of a 2 s vehicle poll the database
recorded 9 vehicle cycles, where two instances polling would have recorded about 20, so the second was not polling. The first was then killed (a forced stop of its process, as a crash would be) and the
second logged that it now held the lock **about 2 s later**, which is one poll interval; it carried on polling, and recorded 4 cycles in the next 8 s. With a real
10 s interval the takeover would take up to 10 s. This was a process kill on one machine; a task that loses its network may hold the lock until the server notices the
dead connection, which was not measured (see [deployment.md](deployment.md#what-was-not-verified)).

### Rate limiting over HTTP

A second instance with the read budget turned down to a burst of 5: seven `GET /api/v1/network/status` in a row from one address returned `200` five times and then
`429`, with `Retry-After: 59`, `Content-Type: application/problem+json` and, because the request carried an `Origin`, the matching `Access-Control-Allow-Origin`. Eight
requests to `/actuator/health` and three failed sign-ins were not counted. The cost of the check itself was not measured separately and no claim is made about it;
`scripts/bench.mjs` now stops and says so if it is refused, because a benchmark against a limited instance would measure the limit.

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
but it gets ten times slower. That was fixed by a circuit breaker; see
[Redis down, with and without the circuit breaker](#redis-down-with-and-without-the-circuit-breaker).

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

## Accounts and sign-in

**Measured 2026-10-18 to 19**, on the live API from source, over `curl` to localhost with a new connection each time and BCrypt at
the shipped cost of 12. Small samples; the full table and what they show is in [authentication.md](authentication.md).

| | Min | Median | Max |
| --- | ---: | ---: | ---: |
| Sign in, correct password (10 requests) | 265 ms | 296 ms | 333 ms |
| `GET /favourites` with a token (25 requests) | 6.8 ms | 8.0 ms | 37.4 ms |

A sign-in is almost all BCrypt, on purpose: the cost is what makes a guessed password expensive, and it is paid once per sign-in
and never by an ordinary page view, which carries an access token checked without touching the database. Each step of cost
doubles the time. Not measured: sign-in under concurrent load, where a slow hash holds a request thread for a quarter of a second.

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
