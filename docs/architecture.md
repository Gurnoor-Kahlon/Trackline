# Architecture

![Trackline architecture: the TTC feeds and the open-data schedule are read by a Spring Boot API, which keeps the schedule, geometry and history in PostgreSQL with PostGIS and live state in Redis, and serves a Next.js application and its browser over REST and Server-Sent Events](diagrams/architecture.svg)

Trackline is two applications and two stores. The API (`services/api`) reads the TTC's public data, analyses it and serves it.
The web application (`apps/web`) shows it. PostgreSQL with PostGIS holds everything that must survive a restart; Redis holds
the live picture, which is rebuilt every ten seconds anyway. Nothing else is part of the system: there is no queue, no search
engine and no second service. This page says what each part does and why the lines between them are where they are. The
reasons for the individual algorithms are in their own documents, linked below.

## The parts

### API

One Spring Boot application, organised by what each package is for rather than by layer.

| Package | Responsibility | Document |
| --- | --- | --- |
| `gtfs` | Resolves, downloads and imports the static schedule in one transaction, then derives route shapes, stations and scheduled headways. Fetches and parses the three realtime feeds. | [data-sources.md](data-sources.md) |
| `ingestion` | Polls each feed on its own interval, runs one cycle at a time per feed, and records every cycle (including the ones that find nothing new) in `ingestion_runs`. | [data-sources.md](data-sources.md) |
| `analytics` | `progress` places each vehicle on its route and infers its direction. `health` judges how evenly a route's vehicles are spaced. `bunching` finds packs that have a gap behind them. `history` samples health, summarises it by the hour and trims what has aged out. | [route-progress.md](route-progress.md), [route-health.md](route-health.md), [bunching-detection.md](bunching-detection.md), [history-and-reliability.md](history-and-reliability.md) |
| `livestate` | Publishes each cycle's snapshot to Redis and reads it back through three tiers (Redis, this instance's own copy, the database), saying which answered. | [performance.md](performance.md) |
| `stream` | Server-Sent Events: a snapshot on connect, then only what changed, a bounded replay ring for `Last-Event-ID`, and connection caps. | [streaming.md](streaming.md) |
| `alerts` | Gives each service alert a lifecycle Trackline observes itself, because the feed publishes no start or end. | [alerts.md](alerts.md) |
| `auth`, `favourites` | Accounts, BCrypt, 15-minute access tokens, single-use refresh tokens, and the saved routes and stops that belong to one token's owner. | [authentication.md](authentication.md) |
| `routes`, `stops`, `search`, `vehicles`, `network` | Thin controllers over the above. | OpenAPI at `/api-docs` |

Every response that reflects the feed is wrapped in an envelope stating its age, whether it is stale, whether it is degraded and
where it came from (`LIVE`, `CACHED`, `DB_FALLBACK`, `STORED`, `STATIC` or `NO_DATA`). A client never has to assume a payload is
current, and the web application shows that age rather than hiding it.

### Web

Next.js with the App Router and strict TypeScript. Pages are server components where nothing is interactive and client
components where something is: the map, the filters, the live stream. All calls to the API go through one typed client
(`src/lib/api`), so no component builds a URL, and the live connection is one hook (`src/lib/live`), so no component opens one.
Sign-in is handled by route handlers under `/api/auth/*` that keep the long-lived refresh token in an `HttpOnly` cookie the
page's scripts cannot read; the short-lived access token lives only in memory (see [authentication.md](authentication.md)).

The browser calls the API directly for data and the live stream, at the address compiled in as `NEXT_PUBLIC_API_BASE_URL`. The
web server's own calls, made while rendering, use `API_INTERNAL_URL` when it is set, because inside a private network the API is
not at the address a browser uses.

### Stores

**PostgreSQL 16 with PostGIS** is the source of truth for the schedule, the route geometry, history, alerts and accounts.
PostGIS is load-bearing: placing a vehicle on its route is `ST_LineLocatePoint` against a stored linestring, nearby stops are an
`ST_DWithin` against a spatial index, and neither has an in-memory equivalent worth the name. The schema is changed only by
Flyway migrations (V1 to V11), and Hibernate is set to validate it, never to create it.

**Redis 7** holds three documents: the current vehicle snapshot, and the current health verdicts and spacing for every route (on the
real feed, about 440 KB, 160 KB and 120 KB). Each has a time to live and is rewritten every cycle. It is a cache of the last
cycle: losing it loses nothing durable, and the API says so on every answer rather than failing. The stream's replay ring is not
in Redis but in the API's own memory.

## One vehicle cycle

Every ten seconds the vehicles feed is fetched. In order:

1. **Fetch** with a short connect and read timeout. A failure is recorded as `FAILED`, the previous snapshot stays in place, and its
   age keeps climbing where everyone can see it.
2. **Parse and validate.** A payload that will not parse is rejected whole, because half of a full-dataset feed is not trustworthy.
   An individual vehicle with impossible coordinates, a future timestamp or an unknown route is rejected with a counted reason and
   the cycle continues.
3. **Skip if nothing is new.** If the feed's header timestamp has not moved, the cycle is recorded as `NO_CHANGE` and stops. The feed
   publishes about every twenty seconds, so about half of ten-second polls end here, and that is the interval working.
4. **Place** every route-assigned vehicle on its route's primary shape, in one PostGIS statement for the whole fleet, and decide
   which way it is going. A vehicle that cannot be placed says why instead of carrying a guessed position.
5. **Store** the readings that are worth keeping (a vehicle that has not moved or re-reported is not stored twice).
6. **Analyse**: route health and bunching, from the placements the cycle already holds.
7. **Publish** to Redis, then to every connected stream, as a delta of only what changed.

The trip-updates and alerts feeds run the same way on their own intervals, so a slow 480 KB trip-updates fetch never delays the
vehicles.

## Decisions that shape everything else

These are explained where they are measured; here they are in one place, because each is the reason a part is the way it is.

- **`route_id` is the only reliable join key.** On the real feeds, a realtime `trip_id` matches a static one for almost no vehicles
  and a realtime `stop_id` for little more than half, while every `route_id` matches. The whole model is keyed on route and
  geometry, and `trip_id` is stored as an opaque string and never joined. ([data-sources.md](data-sources.md))
- **Position along a route is geometric.** Because trips cannot be walked, a vehicle is projected onto its route's shape and its
  distance along it is metres, not a stop number.
- **Direction is inferred.** The feed carries none. It is decided from the vehicle's heading against the shape's, falling back to
  how far the vehicle has moved since it was last stored, and it can honestly be `UNKNOWN`. ([route-progress.md](route-progress.md))
- **Health measures spacing, not lateness.** The feed publishes no delay, so nothing here says "late" or "on time". The verdict is
  an ordinal state with the figures behind it, never a made-up score. ([route-health.md](route-health.md))
- **Bunching needs a gap.** A pack of vehicles is reported only while a large gap has opened elsewhere on the same direction; a
  dense but evenly spaced route makes nobody wait longer. ([bunching-detection.md](bunching-detection.md))
- **Alerts get a lifecycle we own.** With no active period in the feed, Trackline records when it first and last saw each alert and
  presents those as observations, never as the moment an alert began or ended. ([alerts.md](alerts.md))
- **Server-Sent Events, not WebSockets.** Nothing in the product sends data from the browser to the server over a live connection,
  and `EventSource` reconnects by itself. ([streaming.md](streaming.md))
- **Redis can be lost.** The live path is built to degrade: it answers from the instance's own copy, then from the database, and
  marks the answer. Measured behaviour is in [performance.md](performance.md).
- **Missing is missing.** An absent field is `null` and is shown as "not available". It is never zero and never inferred.

## What happens when something fails

| Failure | What the system does | Evidence |
| --- | --- | --- |
| A feed is unreachable or slow | The cycle is recorded `FAILED`; the last good snapshot is served with a growing age; the next cycle tries again. | `ingestion_runs`; [performance.md](performance.md) records two failed cycles in ten |
| A feed returns something unparseable | The whole cycle is rejected and the previous state kept. | Parser tests against real captured feeds |
| An individual entity is invalid | It is rejected with a counted reason; the cycle succeeds. | `rejections` in the cycle log |
| Redis is down | The API answers from its own copy, `degraded: true`, still `200`; ingestion keeps recording history; it recovers on the next cycle. It is about ten times slower while down, because each request waits out Redis's 500 ms timeout first. | Measured and recorded in [performance.md](performance.md); a circuit breaker is not built |
| The static import fails | The transaction rolls back and the previous schedule stays active. | Integration test; [data-sources.md](data-sources.md) |
| A stream client disconnects | Its emitter is released; on reconnect it is replayed what it missed only if that can be proved complete, otherwise sent a fresh snapshot. | [streaming.md](streaming.md) |
| The browser cannot reach the stream | The page stops saying it is live, says it is reconnecting, and recovers by itself when the stream can be opened again, without a reload. | End-to-end test `live.spec.ts` |

Not listed because it has not been exercised: a PostgreSQL outage under load.

## Where the design is thinner than it looks

These are deliberate limits, and the deployment plan has to respect each of them.

- **One API instance.** The in-process single-flight guard stops one instance polling a feed twice, but nothing stops two
  instances both polling. The plan called for a PostgreSQL advisory lock for this and it was not built, so the API must run as
  exactly one task. Scaling it out would double-ingest until that lock exists (observations are deduplicated by a unique key, so
  the cost is wasted work and doubled load rather than wrong data, but that is a reading of the schema and not something measured).
- **The one-off import also polls.** `--import-gtfs` starts the same application, scheduler included. Run it with
  `TRACKLINE_GTFS_REALTIME_ENABLED=false` and `TRACKLINE_HISTORY_ENABLED=false`, as the compose file's notes and
  [deployment.md](deployment.md) do.
- **An import stalls the running API's cycles.** On 2026-10-19 two cycles that began while a full import was loading took 117 s each
  (they finished when the import did). The API kept answering from the last snapshot. A monthly import makes this a few minutes of
  stale data a month, which is why it is documented rather than engineered away.
- **No circuit breaker on Redis,** as above.
- **Reads are served from a whole-snapshot document.** The cost of `/vehicles` is dominated by deserialising the snapshot, so a
  filtered request is barely cheaper than an unfiltered one. At the measured sizes that is fine ([performance.md](performance.md));
  it is the first thing to change if the fleet or the traffic grows a lot.

## Running it

| Where | How |
| --- | --- |
| On a laptop, for development | `docker compose up -d` for the stores; the API and web run natively. See the [README](../README.md). |
| Everything in containers | `docker compose --profile full up -d --build`, then load the schedule once. Both images are built by the Dockerfiles beside each application. |
| In production | The API on AWS ECS Fargate with RDS and a Redis, the web application on Vercel. [deployment.md](deployment.md) is the runbook, and says plainly that it has not been carried out. |
