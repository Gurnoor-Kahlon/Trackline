# Deployment

> **Status: not carried out.** Trackline has not been deployed to AWS or Vercel. Doing so means creating cloud resources, entering
> credentials and incurring cost in someone's account, and none of that was done on anyone's behalf. This page is the runbook for
> doing it: what to create, what to set, and how to know it worked. Everything marked **verified** was run on a laptop, in the
> same container images that would be deployed. Everything marked **untested** is a reading of the documentation of the service
> concerned and has not been run, and each such item is a thing to check first rather than assume.

## What was verified without a cloud account

On 2026-10-19, on a laptop (Windows 11, Docker Desktop 29.2.0; hardware in [performance.md](performance.md)), from this repository's working tree with new,
empty volumes:

- Both production images build from their Dockerfiles. **Verified.**
- The whole stack starts from nothing with `docker compose --profile full up`, under the same `prod` profile that production uses, and
  every service reports healthy. **Verified.**
- The real TTC schedule imports inside the container and yields the expected counts, and live vehicles are ingested, analysed and
  served. **Verified.**
- `scripts/smoke.mjs`, the post-deploy test below, passes against that stack (15 of 15 checks), and fails when a check should fail.
  **Verified.**
- The `prod` profile starts, exposes only `/actuator/health`, does not register the manual import endpoint, and allows exactly the
  configured browser origin. **Verified**, by an integration test that runs on every build and by the smoke test against the container.
- The API runs in a container limited to 1 GiB and half a CPU, which is the size proposed below. **Verified**, with the numbers in
  [Sizing](#sizing).

What no machine of mine could verify is anything that depends on AWS or Vercel behaving as documented. The list of those is at the
end.

## Sizing

Measured on 2026-10-19 with the API in a container limited to 0.5 vCPU and 1 GiB, against the live feeds, for about ten minutes after start-up
([performance.md](performance.md#the-planned-task-size-05-vcpu-and-1-gib)):

- **Memory:** a peak of 436 MB of the 1 GiB, with no out-of-memory event. The image caps the heap at 70% of the container's memory, so there is room.
- **CPU:** a vehicle cycle took a median of 393 ms and at most 629 ms, against a poll every 10 s. The CPU was throttled in about one period in six, mostly
  while starting.
- **Reads:** medians the same as without the limit; the 95th percentile of reads that touch live state rose to roughly 50 to 110 ms, for one client at a
  time. **Nothing was measured with more than one client**, so this is evidence that the task is large enough to run and not that it is large enough for traffic.
- **Start-up** is the weak moment: the one failed cycle in the run happened while the JVM was starting under the limit. A task that is slow to start
  is one to give a longer health-check start period, which the image already sets to 60 s.
- **Redis** holds three documents totalling about 730 KB on the real feed, so a managed instance's smallest size, or a sidecar with the 256 MB the compose file sets,
  is far more than it needs.
- **Database:** 751 MB after the schedule import and 40 minutes of live ingestion; the imported schedule alone was 719 MB when measured on 2026-10-02. Stored positions are the part that grows: see
  [history-and-reliability.md](history-and-reliability.md) before choosing the volume.

No price is quoted, because none was measured.

## Target

```
Browser ──► Vercel (apps/web) ──► (server-side reads) ──┐
   │                                                    ▼
   └──────────────────────────────────────► Application Load Balancer (HTTPS, ACM certificate)
                                                        │
                                       ECS Fargate service, exactly one task
                                       (trackline-api image, prod profile)
                                              │                    │
                                 RDS PostgreSQL 16 + PostGIS   Redis: ElastiCache, or a sidecar
                                                               container in the same task
```

| Part | Choice | Why |
| --- | --- | --- |
| Web | Vercel, root directory `apps/web` | Built for Next.js; preview deployments per pull request. |
| API | ECS on Fargate, one task, 0.5 vCPU and 1 GiB | One task because nothing stops two API instances from polling the feeds (see below). |
| Database | RDS PostgreSQL 16, `postgis` and `pg_trgm` available | The schema needs both; Flyway creates them. Size the volume from [performance.md](performance.md#storage): the imported schedule alone is 719 MB. |
| Redis | ElastiCache, or `redis:7-alpine` as a second container in the API's task | The API treats Redis as losable, so a sidecar with no persistence is a legitimate way to save the cost of a managed one, not a compromise of the design. |
| Images | One ECR repository per image, tagged with the commit | A rollback is the previous tag. |

**Exactly one API task.** The API's guard against polling a feed twice is in-process, and the PostgreSQL advisory lock the plan called
for was not built ([architecture.md](architecture.md#where-the-design-is-thinner-than-it-looks)). Set the service's
`minimumHealthyPercent` to 0 and `maximumPercent` to 100, so a deployment stops the old task before it starts the new one. The cost is a
minute or two without an API on each deployment; the alternative is two tasks both ingesting while they overlap.

## Configuration

The API reads everything from the environment. The `prod` profile (`SPRING_PROFILES_ACTIVE=prod`) refuses to start without the database
password, the signing secret and the allowed origins. The other settings fall back to `localhost`, which is almost never right in
production, so set every one of them.

| Variable | Value | Secret |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` | the RDS endpoint and database | |
| `POSTGRES_PASSWORD` | | yes |
| `REDIS_HOST`, `REDIS_PORT` | ElastiCache's endpoint, or `localhost` and `6379` for a sidecar | |
| `TRACKLINE_AUTH_JWT_SECRET` | at least 32 bytes, e.g. `openssl rand -base64 48` | yes |
| `TRACKLINE_CORS_ALLOWED_ORIGINS` | the web application's exact origin, e.g. `https://trackline.example`, comma-separated for more than one | |

Put the secrets in AWS Secrets Manager or SSM Parameter Store and inject them as the task definition's `secrets`, never as plain
`environment` entries. A secret changed later signs everyone out, which is the right way to rotate it.

Two things to expect to set, both **untested** because they depend on how the managed services are configured:

- RDS PostgreSQL 15 and later require TLS by default. The JDBC URL the API builds has no `sslmode`, so set `SPRING_DATASOURCE_URL` to the
  whole URL with `?sslmode=require` (and the CA bundle if you want it verified).
- ElastiCache with encryption in transit needs `SPRING_DATA_REDIS_SSL_ENABLED=true` (and `SPRING_DATA_REDIS_PASSWORD` if AUTH is on). A
  sidecar needs neither.

The web application takes two kinds of setting:

| Variable | Where | Value |
| --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | Vercel, **at build time** | the API's public HTTPS address. Compiled into the page, so changing it needs a new build. |
| `API_INTERNAL_URL` | Vercel, at run time | the same public address: Vercel's servers cannot reach a private one. Optional; without it server-side reads use the public address anyway. |
| `NEXT_PUBLIC_MAP_STYLE_URL`, `NEXT_PUBLIC_MAP_STYLE_DARK_URL` | build time | OpenFreeMap by default; see `apps/web/.env.example`. |

## Steps

Each step names how to know it worked.

1. **Build and push the API image.** From the repository root, with the registry's address in `$REGISTRY`:
   `docker build -t $REGISTRY/trackline-api:$(git rev-parse --short HEAD) services/api`, then `docker push` it. The build downloads Maven
   and the protobuf compiler, so it needs the network. *Works when:* the push completes and the image is under about 610 MB (it was 610 MB on 2026-10-19).
2. **Create the database.** RDS PostgreSQL 16 in private subnets, no public access, automated backups on. Create the `trackline` database.
   Flyway runs `CREATE EXTENSION postgis` and `pg_trgm` at first start, which needs a user allowed to create them; RDS's master user is.
   *Works when:* the API starts and Flyway reports V11 applied.
3. **Create Redis,** either ElastiCache in the same VPC or a second container in the task definition. *Works when:* the API's health stays `UP`
   and `GET /api/v1/network/status` reports `"source":"LIVE"` after the first cycle.
4. **Store the secrets** in Secrets Manager or SSM.
5. **Create the task definition.** One container from the image, 0.5 vCPU and 1 GiB, the environment above, port 8080, and the same health check
   the image carries: `curl --fail --silent http://localhost:8080/actuator/health`, a 60 s start period. The image sets
   `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`, so the heap follows the task's memory and a task that runs out is replaced rather than left thrashing.
   Log to CloudWatch: the `prod` profile writes one JSON object per line.
6. **Create the load balancer.** An ALB with an HTTPS listener and an ACM certificate, a target group on port 8080, and `/actuator/health` as the
   health check. Keep the idle timeout at 60 s or more: the live stream sends a heartbeat every 15 s precisely so that an idle-timeout never
   closes it. *Works when:* `curl https://api.example/actuator/health` returns `{"status":"UP"}`.
7. **Create the ECS service** with a desired count of 1 and the deployment settings above.
8. **Load the schedule once.** Run a one-off task from the same task definition with the command `--import-gtfs` and these two environment
   overrides, which stop the one-off process from polling the feeds beside the running API:
   `TRACKLINE_GTFS_REALTIME_ENABLED=false` and `TRACKLINE_HISTORY_ENABLED=false`. It exits when it is done. On a laptop it took 130 s from an empty
   database ([performance.md](performance.md#gtfs-static-import)). *Works when:* it exits 0 and logs `GTFS import finished status=IMPORTED`. The TTC
   republishes monthly, so run it monthly; when the archive has not changed it logs `status=UNCHANGED` and does no work.
9. **Create the Vercel project** from the repository with root directory `apps/web`, set `NEXT_PUBLIC_API_BASE_URL` for the production environment,
   and deploy. Add the resulting origin to `TRACKLINE_CORS_ALLOWED_ORIGINS` and redeploy the API. A preview deployment has a different origin and
   is refused by the API until it is added, which is intended.
10. **Smoke test.**
    `node scripts/smoke.mjs --api https://api.example --web https://app.example`
    It needs Node 20 or later and nothing installed. It checks, in order: the API is healthy; the schedule is imported; a route reads in depth; the
    live feed is being ingested and is under two minutes old; route health is being computed; alerts are readable; the live stream opens and sends a
    snapshot; a visitor is refused what needs an account; the manual import endpoint is closed; only the health endpoint is exposed; the web
    application's pages answer; and the API allows the web application's origin and nobody else's. Exit status 1 means a check failed and says which.

## Operating it

- **Alarms to create:** the ALB's 5xx count; the ECS service's running count below 1; and the task's restarts. For ingestion health, alert on
  the API going stale: `GET /api/v1/network/status` returns `ageSeconds`, and `scripts/smoke.mjs` already treats more than 120 s as a failure.
  The `prod` profile deliberately exposes only `/actuator/health`, so there are no Micrometer metrics to scrape; the per-cycle record is the
  `ingestion_runs` table and the logs.
- **Rate limiting is not in the application.** Apart from the stream's connection caps (500 in total, 20 per address), the public API limits no
  client. The per-address cap needs the visitor's real address, and the `prod` profile takes it from `X-Forwarded-For` as the load balancer sets it
  ([streaming.md](streaming.md#limits)); without that, a balancer would make the whole site one address. If the API is to be public, also put a
  rate-based rule in front of it (AWS WAF on the load balancer, for example) before announcing it.
- **Rollback:** point the service at the previous task definition revision (the previous image tag). Migrations only add, so the previous image
  stays compatible with the newer schema; a destructive change must be split across two releases. On Vercel, promote the previous deployment.
- **Monthly import:** schedule the one-off task, or run it by hand when the TTC publishes. While it runs, the API's own cycles stall (two cycles
  took 117 s during one on 2026-10-19), and it keeps answering from the last snapshot.
- **Storage:** history is trimmed on a schedule (positions after 14 days, health samples after 90). The measured growth is larger than the plan
  assumed and is the thing most likely to outgrow a small volume; read [history-and-reliability.md](history-and-reliability.md) before choosing the
  volume size, and watch the free space.

## What was not verified

Each of these is a reason the first deployment may need an adjustment, and none is a reason to doubt the images themselves:

- Creating the extensions on RDS, and TLS between the API and RDS or ElastiCache (the settings above).
- That an ALB's address is within the private ranges Tomcat trusts for `X-Forwarded-For`, and that it appends to the header as documented. The
  behaviour was tested over real HTTP with the loopback address standing in for the proxy; if the real balancer is not trusted, every visitor
  is counted as the balancer again and the stream's per-address limit would cut the whole site off after 20 connections. Check this first with
  `node scripts/streams-per-address-check.mjs --api https://api.example`, which opens 25 streams each claiming a different visitor and reports
  how many were admitted. Against the container on 2026-10-19, with a proxy it trusted, it was 25 of 25.
- Whether the ALB's behaviour with the event stream matches its documentation (the heartbeat is designed for it; the stream has been exercised
  directly and in a browser, not through an ALB).
- That a Vercel build of `apps/web` behaves as the local production build does. The repository sets `output: "standalone"` for the Docker image;
  Vercel builds with its own output and is expected to ignore it.
- ECS service replacement timing with `minimumHealthyPercent` 0, and the real length of the gap between tasks.
- Cost. No price is quoted, because none was measured and published prices change; check the calculator for the region chosen.
- Anything about the continuous-integration workflows running on GitHub. They are statically checked with actionlint and every command in them
  was run locally, but they have not been run by GitHub.
