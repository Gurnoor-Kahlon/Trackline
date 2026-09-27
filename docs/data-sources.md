# Data Sources

Trackline is built on two public TTC data sources. Everything recorded here was
verified by fetching and decoding the real feeds, not read from documentation.
Facts are tagged **[verified]**, **[assumption]**, or **[to verify]**.

**Last verified: 2026-09-27.** Re-verify when the live contract test
(`-Dgroups=live`, added in Milestone 8) fails.

---

## 1. GTFS Static — Toronto Open Data, "TTC Routes and Schedules"

The download URL is resolved at runtime through the CKAN package API rather than
hard-coded, so a resource replacement does not break ingestion:

```
https://ckan0.cf.opendata.inter.prod-toronto.ca/api/3/action/package_show?id=ttc-routes-and-schedules
```

- Single ZIP resource, **36.4 MB**, refresh rate `Monthly`. **[verified]**
- Licence: Open Government Licence – Toronto.

### Contents [verified by reading the ZIP]

| File | Rows | Notes |
| --- | --- | --- |
| `agency.txt` | 1 | TTC, timezone `America/Toronto` |
| `routes.txt` | 236 | 213 bus (`route_type=3`), 20 streetcar (`0`), 3 subway (`1`). `route_color` populated for **all 236**. |
| `stops.txt` | 9,409 | `wheelchair_boarding`: 8,096 = `1`, 1,313 = `2`. |
| `trips.txt` | 138,036 | `direction_id` 0/1 near-even; 1,571 distinct `shape_id`. |
| `stop_times.txt` | 4,357,307 | 207 MB. Has `shape_dist_traveled`. |
| `shapes.txt` | 438,263 | Has `shape_dist_traveled`. |
| `calendar.txt` | 10 services | Window `20260930`–`20261031` |
| `calendar_dates.txt` | 104 | 103 additions, 1 removal |

### Consequences

1. **There is no station hierarchy.** `location_type` is empty for every one of
   the 9,409 stops and `parent_station` is empty for every one. Subway platforms
   are plain stops. Trackline therefore *derives* stations (name normalisation +
   PostGIS `ST_ClusterDBSCAN`) and records `derivation_method` so the UI can say
   these are grouped stops rather than implying feed-provided stations.
2. **The published feed can describe a future service period.** On 2026-09-27 the
   calendar covered 2026-09-30 to 2026-10-31 — it did not cover "today". Every
   import records `feed_start_date`/`feed_end_date` and exposes a schedule
   coverage flag; schedule-derived features degrade explicitly instead of
   presenting a schedule that does not apply.
3. `trips.wheelchair_accessible` is `1` for all 138,036 rows and carries no
   information. It is ignored. Stop-level `wheelchair_boarding` is real and is
   the only accessibility data Trackline displays.

---

## 2. GTFS-Realtime — `bustime.ttc.ca`

All three endpoints returned HTTP 200 with `application/x-google-protobuf`,
GTFS-Realtime **version 2.0**, `incrementality = FULL_DATASET`. **[verified]**

| Endpoint | Size | Entities in one snapshot |
| --- | --- | --- |
| `https://bustime.ttc.ca/gtfsrt/vehicles` | 94 KB | 1,363 `VehiclePosition` |
| `https://bustime.ttc.ca/gtfsrt/trips` | 587 KB | 1,677 `TripUpdate` / 25,776 `StopTimeUpdate` |
| `https://bustime.ttc.ca/gtfsrt/alerts` | 4 KB | 13 `Alert` |

Realtime coverage is **bus and streetcar only** — there is no subway realtime.
Lines 1, 2 and 4 get schedule and alerts, and the UI states that live vehicles
are unavailable for them rather than rendering an empty map.

### `vehicles` field population, measured over n = 1,363 [verified]

| Field | Present | Implication |
| --- | --- | --- |
| `position.latitude` / `longitude` | 1,363 (100 %) | always usable |
| `position.bearing` | 1,363 (100 %) | used for direction inference |
| `position.speed` | 1,363 (100 %) | many zeros; low trust, labelled as source-reported **[to verify distribution]** |
| `timestamp` | 1,363 (100 %) | per-vehicle freshness |
| `vehicle.id` | 1,363 (100 %) | stable vehicle key |
| `occupancy_status` | 1,346 (98.8 %) | real data, surfaced |
| `trip.route_id` | 1,050 (77 %) | 313 vehicles have no trip (deadheading/unassigned) — handled explicitly, never dropped silently |
| `current_stop_sequence`, `stop_id` | ~1,049 | trip-assigned vehicles only |
| `trip.direction_id` | **0 (absent entirely)** | direction must be **derived** |
| `trip.trip_id` | 1,050, of which **2** exist in static `trips.txt` | **unjoinable** |

### Identifier join rates [verified]

| Join | Result |
| --- | --- |
| realtime `route_id` → static `routes.txt` | **1,050 / 1,050 = 100 %** |
| realtime `stop_id` → static `stops.txt` | 600 / 1,049 = **57 %** |
| realtime `trip_id` → static `trips.txt` | **2 / 1,050** |

Static trip ids look like `50790576`; realtime ids look like `21510010` or
`-916406688`. 312 duplicate `trip_id` values appeared across distinct vehicles in
a single snapshot.

### `TripUpdate` findings [verified]

Every entity carries `trip`, `stop_time_update`s and `timestamp`. 24,570 of
25,776 `StopTimeUpdate`s carry `arrival.time`; only 745 carry `departure`.
**No `delay` field is populated anywhere** — not `TripUpdate.delay`, not
`StopTimeEvent.delay`. Many trips use synthetic negative ids with
`schedule_relationship = 8`.

### `Alert` findings [verified]

`cause`, `effect`, `header_text`, `description_text` and `informed_entity`
(`route_id`, sometimes `stop_id`) are present on all 13 alerts.
**`active_period` is absent and `severity_level` is absent.**

---

## 3. Design conclusions drawn from the above

1. **`route_id` is the only reliable join key.** The data model is keyed on
   `route_id` plus geometry. Realtime `trip_id` is stored as an opaque
   `source_trip_id` string with **no foreign key**.
2. **Route progress is geometric, not schedule-based.** Because `trip_id` does
   not join and only 57 % of `stop_id`s do, a vehicle cannot be walked along
   `stop_times`. Position along a route comes from PostGIS
   `ST_LineLocatePoint` against the route's primary shape. This is why PostGIS is
   load-bearing here rather than decorative.
3. **Direction is inferred** from shape projection, bearing agreement with the
   shape tangent, and progress delta between observations — with an explicit
   `UNKNOWN` state when inference is ambiguous.
4. **Route health is headway-regularity based, not delay based**, because no
   delay data exists. No on-time-performance figure is published.
5. **Trackline owns alert lifecycle** (`first_seen_at` / `last_seen_at` /
   `resolved_at`), since the feed provides no active period.

## 4. Still to verify

- Actual feed refresh cadence, i.e. how often `header.timestamp` advances. Poll
  intervals are set from this measurement in Milestone 8, not guessed.
- `position.speed` trustworthiness (Milestone 8).
- Whether the realtime→static `stop_id` match rate improves once the 2026-09-30
  service period begins (Milestone 6).
