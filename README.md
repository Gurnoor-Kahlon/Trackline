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
API and web applications are not scaffolded yet.

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

## Licence

TTC data is published under the Open Government Licence – Toronto.
