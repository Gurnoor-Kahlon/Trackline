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
Spring Boot API runs and serves `GET /api/v1/network/status`, which reports
nothing observed with a `NO_DATA` provenance because feed ingestion does not
exist yet. The web application is not scaffolded yet.

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

The API does not use the database yet, so it runs independently of Compose:

```bash
cd services/api && ./mvnw spring-boot:run
```

It listens on 8080 with the `local` profile by default.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/network/status` | Network-wide live status |
| `GET /actuator/health` | Liveness and readiness |
| `GET /api-docs` | OpenAPI 3.1 document |
| `GET /api-docs/ui` | Swagger UI |

Run the tests:

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

## Licence

TTC data is published under the Open Government Licence – Toronto.
