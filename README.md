# Trackline

**See Toronto move.**

Trackline is a real-time Toronto transit intelligence platform. It ingests the
TTC's public GTFS Static and GTFS-Realtime feeds and turns them into live and
historical transit intelligence: live vehicles, arrivals, service alerts, vehicle
spacing, bunching detection, and route health.

> **Status: in development.** This README grows with the project. Sections are
> added only once the functionality behind them actually works, and every number
> published here is measured — never estimated.

---

## Current state

Local infrastructure (PostgreSQL + PostGIS, Redis) runs via Docker Compose. The
Spring Boot API runs, migrates its schema with Flyway on startup, and serves
`GET /api/v1/network/status`. It also polls the TTC's three GTFS-Realtime feeds on
a schedule, validating and deduplicating vehicle positions into a partitioned
observation table, and serves them live from a Redis-backed cache. Service alerts are
stored with a lifecycle Trackline observes itself, because the feed publishes no start
or end time, and are served from `GET /api/v1/alerts`. The TTC schedule feed
can be imported: one command loads the whole network — 236 routes, 9,409 stops,
138,036 trips and 4,357,307 scheduled calls — into PostgreSQL, then derives what
the feed does not provide: 1,571 route linestrings, 108 stations clustered from
platform stops, and 20,945 scheduled headway entries. Nothing reads that data over
the API yet, but the schedule half of it is queryable: routes, their geometry and
stops, stop detail, proximity search and text search all return real TTC data.
Each stored vehicle reading also carries where that vehicle is along its route and
which way it is going, derived from its position (see
[docs/route-progress.md](docs/route-progress.md)). From those placements each cycle
judges how evenly every route's vehicles are spaced, against the timetable where the
imported schedule covers the day, and serves a verdict with its reasons and the figures
behind it (see [docs/route-health.md](docs/route-health.md)). Packs of vehicles running together while a gap opens elsewhere are found and recorded as events
(see [docs/bunching-detection.md](docs/bunching-detection.md)).

The Next.js application renders the shell and design system: header, navigation,
search affordance, light and dark themes, and the route, status, freshness, card,
sheet, skeleton, empty and error primitives. The home page reads the API: the
network's counts and their age, how many routes are unevenly spaced and which, and the
newest service alerts, each saying what it measured and how old it is. `/alerts` lists every alert
with filters by mode and route, and `/map` does call the API: it draws any of the 236 routes on a
MapLibre basemap from the agency's own schedule geometry, with the stops that
route serves, filters by mode and name, and a list beside the map that offers
every interaction the map does. Live vehicles move on it: the API streams them over
Server-Sent Events, and the page draws each as an arrow facing its heading, coloured
by its route, easing between real reports and never drawing a marker ahead of the
latest one. A chip on the map says how old the data is, and says so plainly when the
connection drops or the feed goes quiet (see [docs/streaming.md](docs/streaming.md)).

## Planned stack

| Area | Choice |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Maven |
| Persistence | PostgreSQL 16 + PostGIS (source of truth, history) |
| Live state | Redis 7 (ephemeral, TTL'd) |
| Realtime transport | Server-Sent Events |
| Frontend | Next.js (App Router), TypeScript, Tailwind CSS |
| Mapping | MapLibre GL JS, OpenFreeMap tiles |
| Testing | JUnit 5, Testcontainers, Vitest, Playwright |
| Infrastructure | Docker Compose locally; AWS ECS/RDS + Vercel in production |

## Data sources

The TTC feeds were inspected directly rather than assumed from documentation, and
several findings shaped the architecture — most importantly that realtime
`trip_id` cannot be joined to static GTFS. See **[docs/data-sources.md](docs/data-sources.md)**.

## Local development

Requires Docker Desktop.

```bash
cp .env.example .env
```

Set `POSTGRES_PASSWORD` in `.env`, then start the infrastructure:

```bash
docker compose up -d
```

Verify PostGIS is available:

```bash
docker compose exec postgres psql -U trackline -d trackline -c "SELECT postgis_version();"
```

Verify Redis is reachable:

```bash
docker compose exec redis redis-cli PING
```

Stop the infrastructure (data is preserved in the `postgres-data` volume):

```bash
docker compose down
```

To discard the database entirely, add `-v`.

### API

The API needs the database, so start Compose first. It reads its connection
settings from the same environment variables, which are not exported into your
shell automatically:

```bash
cd services/api && set -a && . ../../.env && set +a && ./mvnw spring-boot:run
```

In PowerShell:

```powershell
Get-Content ..\..\.env | Where-Object { $_ -match '=' -and $_ -notmatch '^#' } | ForEach-Object { $p = $_.Split('=',2); [Environment]::SetEnvironmentVariable($p[0].Trim(), $p[1].Trim()) }; .\mvnw spring-boot:run
```

It listens on 8080 with the `local` profile by default. Flyway applies any
pending migrations at startup and the schema is never generated from the
entities — Hibernate only validates that the two agree.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/network/status` | Network-wide live status |
| `GET /api/v1/routes` | List routes, filterable by mode and name |
| `GET /api/v1/routes/{id}` | A route and its directions |
| `GET /api/v1/routes/{id}/shape` | Route geometry as GeoJSON, optionally simplified |
| `GET /api/v1/routes/{id}/stops` | Stops in order along the route |
| `GET /api/v1/stops/{id}` | A stop and the routes that call there |
| `GET /api/v1/stops/nearby` | Stops near a point, nearest first |
| `GET /api/v1/search` | Routes, stops and stations by name or number |
| `GET /api/v1/vehicles` | Live vehicles, filterable by route or bounding box |
| `GET /api/v1/routes/{id}/vehicles` | Live vehicles working one route |
| `GET /actuator/health` | Liveness and readiness |
| `GET /api-docs` | OpenAPI 3.1 document |
| `GET /api-docs/ui` | Swagger UI |

Every list endpoint caps its page size, radius and result count, and a request
over a cap is a `400` naming the offending parameter rather than a server error.

Run the tests. Integration tests start their own PostGIS container through
Testcontainers, so Docker must be running but Compose does not have to be:

```bash
cd services/api && ./mvnw verify
```

#### Response shape

Responses carrying feed-derived data are wrapped in an envelope whose `meta`
block states the data's age, staleness and origin, so a client never has to
assume a payload is current:

```json
{
  "data": { "vehiclesTracked": 0, "routesReporting": 0, "activeAlerts": 0 },
  "meta": {
    "generatedAt": "2026-09-27T22:17:42Z",
    "feedTimestamp": null,
    "ageSeconds": null,
    "stale": true,
    "degraded": false,
    "source": "NO_DATA"
  }
}
```

`source: "NO_DATA"` is how a client distinguishes "nothing is running" from
"nothing has been measured yet". Unknown values are null, never zero-filled.

Errors are RFC 7807 problem details (`application/problem+json`) and never
include a stack trace or exception type; unexpected failures carry an `errorId`
that appears in the logs.

### Web

```bash
cd apps/web && corepack pnpm install && corepack pnpm dev
```

It serves on 3000. The home page needs nothing else running; `/map` reads routes
and geometry from the API, so Compose, the API and an imported schedule feed all
need to be up for it to show anything other than its error state.

| Command | Purpose |
| --- | --- |
| `pnpm dev` | Development server |
| `pnpm build` | Production build |
| `pnpm lint` | ESLint, including the jsx-a11y rules |
| `pnpm typecheck` | `tsc --noEmit` |
| `pnpm test` | Vitest component and unit tests |

pnpm is provided by Corepack, which ships with Node, so no global install is
needed — `corepack pnpm <command>` works anywhere in `apps/web`.

Configuration is documented in `apps/web/.env.example`: the API's base URL and
the two basemap style URLs. Every one has a working default, so a `.env.local` is
only needed to change one. All three are `NEXT_PUBLIC_`, so they are compiled into
the browser bundle — nothing secret belongs there.

`pnpm dev` and `pnpm build` first copy MapLibre's web worker into
`public/vendor/`. MapLibre locates that worker from `import.meta.url`, which a
bundler rewrites to a chunk path where the file does not exist; the map then
renders nothing and logs only "Worker failed to load". The copy is regenerated
from whichever version is installed and is not committed, so it cannot drift from
`package.json`. See `apps/web/scripts/sync-maplibre-worker.mjs`.

Design tokens live in `src/app/globals.css`. Surfaces stay near-neutral so the
only saturated colour on screen carries meaning: a route's own GTFS colour, or a
service status. Status is never encoded in colour alone — each state also has a
distinct glyph and a text label.

### Importing the TTC schedule

The schedule feed is loaded on demand, not at startup — it is a monthly operator
task, and importing on boot would re-download 36 MB on every restart and make
startup depend on a third party being reachable.

```bash
cd services/api && set -a && . ../../.env && set +a && ./mvnw spring-boot:run -Dspring-boot.run.arguments=--import-gtfs
```

The archive is located through Toronto's open-data catalogue rather than a pinned
URL, downloaded, hashed, and loaded into an empty network inside one transaction.
If anything fails, the transaction rolls back and the previously active network is
untouched. If the published archive's SHA-256 matches the active version, nothing
is reloaded.

While the API is running, the same import can be triggered with:

```bash
curl -X POST http://localhost:8080/api/v1/admin/gtfs/import
```

That endpoint is registered only when `trackline.gtfs.static.admin-endpoint` is
true. It is off in the production profile, because no authentication exists yet
and re-downloading the feed and rewriting the network is not something an
anonymous caller should be able to do.

The import also derives three things the TTC feed does not publish — route
geometry, stations, and scheduled headways — inside the same transaction, so an
active network always has the geometry needed to place a vehicle on a route. The
rules behind each derivation, and the measurements that motivated them, are in
[docs/data-sources.md](docs/data-sources.md).

Measured timings for a real import are in [docs/performance.md](docs/performance.md).

### Realtime ingestion

The three GTFS-Realtime feeds are polled automatically whenever the API is running
— vehicles every 10 s, trip updates every 20 s, alerts every 60 s. Those intervals
come from measuring how often the TTC actually publishes (about every 20 s), not
from preference; polling faster than a publisher updates buys nothing.

Every cycle is recorded in `ingestion_runs`, including the ones that find nothing
new, so a stalled publisher and a healthy quiet one stay distinguishable. A cycle
that cannot parse its payload keeps the previous snapshot rather than replacing it
with a partial one.

Set `trackline.gtfs.realtime.enabled=false` to stop polling.

### Placing vehicles on their routes

The feed publishes neither a direction nor a distance along the route, so both are
derived. Each cycle projects every route-assigned vehicle onto the primary shape of
its route, in one PostGIS statement for the whole fleet, and decides which of the
route's two directions it is running from its reported heading, falling back to how
far it has moved since its last stored reading. The result is stored beside the
reading. A vehicle that cannot be placed says why — off-route, no route assigned,
no shape, or direction genuinely unknown — instead of carrying a guessed value.

The thresholds are in `application.yml` under `trackline.analytics.progress`, each
set from a measurement. If projection fails, the cycle still stores its readings,
unprojected. The algorithm, the measurements behind it, and what it cannot do are in
[docs/route-progress.md](docs/route-progress.md).

### Live state and degradation

Each cycle publishes the vehicle snapshot to Redis, so a page view reads one cached
document instead of querying Postgres for positions that change every ten seconds.

Reads fall through three tiers, and every response says which one answered:

| `meta.source` | Means |
| --- | --- |
| `LIVE` | the shared Redis cache |
| `CACHED` | this instance's own copy, because Redis was unreachable |
| `DB_FALLBACK` | reconstructed from stored observations — the stalest of the three |
| `NO_DATA` | nothing has been ingested, which is not the same as no vehicles running |

`CACHED` and `DB_FALLBACK` also set `meta.degraded`. Losing Redis costs freshness
and speed, never availability: the API keeps answering, ingestion keeps recording
history, and `/actuator/health` deliberately stays `UP`, because an instance that
can still serve should not be pulled out of a load balancer. Measurements for the
healthy and degraded paths are in [docs/performance.md](docs/performance.md).

The feeds are a third party's output, so there is a contract test that checks they
still match what Trackline was built on — including the fields they do *not*
carry, since the absence of direction, delay and alert periods is what forces
several design decisions. It is excluded from the normal build and run on purpose:

```bash
cd services/api && ./mvnw test -Dgroups=live -DexcludedGroups= -DfailIfNoTests=false
```

### Live streaming

`GET /api/v1/stream/vehicles` sends a snapshot of the fleet when a client connects
and then only what changed each cycle, filtered on the server by route or bounding
box so a page watching one route is not sent the whole network. `/stream/network`
sends the network-wide counts. A heartbeat every 15 s keeps proxies from closing
an idle connection, and a client that reconnects with `Last-Event-ID` is replayed
what it missed only if the replay can be proved complete; otherwise it is sent a
fresh snapshot, never a replay that might have a gap.

```bash
curl -N "http://localhost:8080/api/v1/stream/vehicles?routes=501"
```

Connections are capped (500 in total, 20 per address), and a slow client is
dropped rather than allowed to hold memory or delay the others. The protocol, the
limits and the places it differs from the original plan are in
[docs/streaming.md](docs/streaming.md); measurements with 25 simultaneous clients
are in [docs/performance.md](docs/performance.md).

### Bunching

Vehicles running in a pack are only reported as bunched while a large gap opens elsewhere on the
same direction, because a dense route that is evenly spaced makes nobody wait longer. A bunch is
recorded as one event for its whole life, opened when first seen, updated while it lasts and
resolved when it has been gone for two analyses in a row, with times that are when Trackline saw it
rather than when it formed or broke up. The route panel draws each direction's vehicles along the
route with any pack marked. See [docs/bunching-detection.md](docs/bunching-detection.md).

```bash
curl "http://localhost:8080/api/v1/routes/501/bunching"
```

### Route health

Each vehicle cycle measures how evenly every route's vehicles are spaced along it, per
direction, and compares that with the timetable's spacing for the hour. The result is one
of `EXCELLENT`, `NORMAL`, `UNSTABLE`, `MAJOR_DISRUPTION` or `UNKNOWN`, with a confidence,
reason codes and the figures behind it. It measures spacing, not delay: the TTC publishes
no delay, so nothing here means late or on time.

```bash
curl "http://localhost:8080/api/v1/routes/501/health"
curl "http://localhost:8080/api/v1/health/routes?state=UNSTABLE&state=MAJOR_DISRUPTION"
```

`UNKNOWN` is a stated answer with a reason (too few vehicles, or no positions published, as
on the subway), never a blank. The thresholds are documented starting judgements, not a
calibration, and say so in [docs/route-health.md](docs/route-health.md), which also records
what the first run against the live feed got wrong and how it was corrected.

### Service alerts

The alerts feed is read every minute and each alert is stored as one continuous stretch
of being present in the feed: when Trackline first saw it, last saw it, and the first
cycle that did not contain it. Those are observations, so they bound when an alert began
and ended and are never presented as the moment it did. An alert that disappears and
returns is a new stretch.

Two measured facts shape what is served. Every alert on the live feed states
`UNKNOWN_CAUSE` and `UNKNOWN_EFFECT`, so nothing is grouped or ranked by either, and the
TTC publishes no severity. And the publisher cuts each alert's header off mid-word at about
31 characters, so the interface shows the description. An alert naming a route the imported
schedule does not have is kept and shown as published, not dropped. See
[docs/alerts.md](docs/alerts.md).

```bash
curl "http://localhost:8080/api/v1/alerts?route=501"
curl "http://localhost:8080/api/v1/alerts?active=false"   # alerts that have left the feed
```

### Database

Migrations live in `services/api/src/main/resources/db/migration` and are the only
thing that changes the schema. `spring.jpa.hibernate.ddl-auto` is `validate`, so a
mismatch between an entity and a migration fails the build rather than silently
altering a table.

The containers publish Postgres on **56432** and Redis on **56379**, not on their
default ports, so neither can be shadowed by a service installed natively on the
machine — including one inside a WSL distro, which Windows relays to `localhost`.
That failure mode is quiet and confusing, because connections succeed against the
wrong server. Both ports come from `.env` and feed the published port and the
application's connection settings together.

To inspect the schema:

```bash
docker compose exec postgres psql -U trackline -d trackline -c "\dt"
```

## Licence

TTC data is published under the Open Government Licence – Toronto.
