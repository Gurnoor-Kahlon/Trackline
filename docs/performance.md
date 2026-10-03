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
