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

**Re-importing an unchanged archive: 11,253 ms.** The archive is still downloaded
and hashed, but the SHA-256 matches the active version so nothing is parsed or
loaded. Since the TTC republishes monthly, this is the usual outcome of a run.

### Storage

| Item | Size |
| --- | ---: |
| Database total after import | 697 MB |
| `stop_times` including indexes | 598 MB |

### Notes on the numbers

`stop_times` is 87 % of the import time and 86 % of the storage. Its per-row cost
is dominated by the two foreign keys, which are validated for all 4.36 million
rows inside the import transaction. That was a deliberate choice — correctness
first — and the documented option if it becomes a problem is to drop and recreate
those constraints within the same transaction.

The import is a monthly operator task, not a request path, so three minutes is
acceptable today. It is recorded here so that a future change can be judged
against a real baseline rather than a memory of one.
