# Route Health

How Trackline decides whether service on a route is coherent right now, what that decision
rests on, and where it is weaker than it looks.

**It measures how evenly vehicles are spaced. It does not measure delay.** The TTC publishes no
delay for any trip (measured: not one populated `delay` field in a full snapshot), so there is
no on-time figure and nothing in Trackline says a route is "late". What can be said is whether
vehicles are bunched together, strung out, or too few, and how that compares with what the
timetable intends.

Figures below are measured and dated. The per-cycle cost is in [performance.md](performance.md).

---

## The verdict

One of five named states per route, never a single score: a number would need weights nothing
here can justify.

| State | Meaning |
| --- | --- |
| `EXCELLENT` | Vehicles are evenly spaced. |
| `NORMAL` | Spacing is within the usual range. |
| `UNSTABLE` | Spacing is uneven, so waits are likely to be inconsistent. |
| `MAJOR_DISRUPTION` | Spacing is severely uneven, or far fewer vehicles are running than the timetable calls for. |
| `UNKNOWN` | There is no verdict, and a reason says why. It is not an error and not a good or bad state. |

Each verdict carries a **confidence** (`HIGH`, `MEDIUM`, `LOW`, `NONE`), **reason codes**, and the
**figures** it was reached from, so the explanation shown to a rider ("spacing varies by 62%,
and the longest gap is 2.1 times the expected spacing of 3.0 km") is composed from numbers they
can check. The API returns codes and figures, never sentences.

A route is judged **per direction** and takes the worse of its two. The directions that decided
it, and the figures for each, are returned beside it.

## What goes in

Per route and direction, from one ingestion cycle:

1. **Where each vehicle is along the route**, in metres, from the projection onto the route's
   primary shape ([route-progress.md](route-progress.md)). Only vehicles placed in a direction
   count. Vehicles that could not be placed (off-route, direction unknown, no geometry) are
   counted on the route and take part in no spacing.
2. **How recently each vehicle reported.** A vehicle whose own timestamp is more than two
   minutes older than the feed's is stale, and is left out of spacing and counted. Age is
   measured against the feed's own time, not the clock, so a feed running late does not make
   every vehicle look stale.
3. **Whether it is at a terminal.** Within 200 m of either end of a non-loop route, vehicles are
   parked on layovers, and a parked bus is not part of the spacing along the line. They are left
   out of the gaps but still counted as running when comparing with the timetable. Loops have no
   such end.
4. **The timetable's spacing for this hour**, from `route_scheduled_headways`, if the imported
   timetable covers today. Day type (weekday, Saturday, Sunday) and hour are Toronto's, not UTC's.
5. **How fast vehicles are travelling on this route**, measured, not reported (below).

## How spacing becomes a distance

The timetable states spacing in time (a bus every ten minutes) and vehicles are positioned in
distance. Speed converts one to the other, and the speed that matters is how far along the
route buses actually get, stops and signals included. The feed's own `speed` field is frequently
zero and is not used.

`RouteSpeedEstimator` keeps the last 15 minutes of each vehicle's progress along its route and
direction, in memory. A vehicle with at least three readings spanning at least three minutes
gives one figure, its net progress over that time. A route's speed is the **median** across at
least three vehicles, so a minority of vehicles sitting out a layover does not drag it down.
A vehicle that turns round starts a fresh run, since the other direction is a different stretch
of shape. Figures that are negative (a projection flip) or above 25 m/s (a projection error) are
discarded, and a route whose median is below 1 m/s has parked vehicles, not a speed.

Expected spacing is then `scheduled headway x speed`. After a restart there is no history, so
for the first few minutes routes report `SPEED_UNKNOWN` and are judged against their own
average spacing instead, and say so.

## The rules

Spacing is the gaps in metres between consecutive usable vehicles, in order along the route; a
loop includes the gap that wraps past the end. Three figures come from them:

- **variation**: the standard deviation of the gaps over their mean (the coefficient of
  variation of headway, the conventional regularity measure). Only with at least **three gaps**:
  with two it is just their ratio and cannot exceed one, so it says nothing the ratio does not.
- **gap ratio**: the largest gap over the expected spacing, or over the gaps' own average when
  there is no timetable comparison.
- **supply ratio**: vehicles running over vehicles expected, `shape length / expected spacing`
  with a minimum of one. Only with a timetable comparison.

First match wins. Thresholds are in `trackline.analytics.health` and a figure exactly on a
boundary belongs to the calmer band (the tests check both sides of every one).

| Rule | Variation | Gap ratio | Supply ratio |
| --- | --- | --- | --- |
| `MAJOR_DISRUPTION` | above 0.75 | above 2.5 | below 0.5 |
| `UNSTABLE` | above 0.5 | above 1.8 | |
| `NORMAL` | above 0.25 | above 1.3 | |
| `EXCELLENT` | otherwise | | |

**These bands are a starting judgement, not a calibration.** They are round numbers that follow
the conventional reading of headway variation, they are not fitted to anything, and there is no
ground truth of known disruptions to fit them against. The measured outcome is in the last
section, and re-tuning them against stored history is milestone 16's work. They are properties
so doing so changes configuration, not code.

### Confidence

| Confidence | When |
| --- | --- |
| `HIGH` | five or more usable vehicles, compared against the timetable, and not mostly stale |
| `MEDIUM` | three or four vehicles, or five or more without a timetable comparison |
| `LOW` | two vehicles, or more than 30% of the vehicles stale |
| `NONE` | no verdict |

### Safeguards against saying more than the data shows

Found by running the first version against the live feed, each is a rule that exists because the
data produced a bad answer without it:

- **Two vehicles with no timetable are not judged.** A single gap measured against its own
  average is always exactly average, so any state would be an artefact of the method. On the
  first live run dozens of routes read "normal" for this reason. They are `UNKNOWN` instead.
- **A verdict resting on one gap is never the best or the worst.** `LOW` confidence holds a
  verdict to `NORMAL` through `UNSTABLE`: never `EXCELLENT` (one even gap says almost nothing
  about the rest of the route), and never `MAJOR_DISRUPTION` (a wide gap between two buses may be
  a route that simply has two buses at this hour). The reason codes still say what was seen.
- **Variation needs three gaps.** Two gaps saturate it.
- **A route whose median vehicle is parked has no speed.** The first live run measured 0.06 m/s on
  a route sitting out a layover, which turned a timetable spacing into 48 m and a gap ratio of 136.

## What was left out

- **Alert severity.** The original design used the worst alert effect on the route (`NO_SERVICE`
  outranking `DETOUR`). The TTC states `UNKNOWN_EFFECT` on every alert ([alerts.md](alerts.md)),
  so a "no service" alert cannot be told from an elevator outage by anything but its wording, and
  Trackline does not read wording. A real `NO_SERVICE` alert therefore does not move a route's
  state today.
- **Bunching clusters.** They arrive with their own detector in milestone 15 and will become an
  input then. Until then, bunched vehicles show only as high variation or one large gap.

## Reason codes

`HIGH_HEADWAY_VARIATION`, `LARGE_GAP` and `FEWER_VEHICLES_THAN_SCHEDULED` say why a verdict was
reached. `FEW_VEHICLES`, `STALE_VEHICLES`, `NO_SCHEDULE_COVERAGE`, `NO_SCHEDULED_HEADWAY` and
`SPEED_UNKNOWN` say why it is weaker than it could be. `INSUFFICIENT_VEHICLES`, `NO_REALTIME_FEED`
(the subway: the TTC publishes no positions for it) and `NOT_ANALYSED_YET` say why there is none.

## Where it runs and how it is served

It runs inside the vehicle ingestion cycle, after placement and streaming, on the cycle's own
snapshot, so it adds no query for positions. A failure there costs that cycle's verdicts and
nothing else: the readings are already stored and cached, and the failure is counted
(`trackline.analytics.health.failures`). If placement failed there is nothing placed to judge
and nothing is published.

Verdicts for every route with vehicles are written as one document to Redis (with the same TTL
as the vehicle snapshot, so a stopped ingester's last verdicts expire) and kept in this
process's memory. Reads fall through Redis to the in-process copy, and a response says which
answered (`meta.source` `LIVE` or `CACHED`) and is `degraded` when it was the copy. There is no
database tier: health is derived from a snapshot and recomputed by the next cycle.

| Endpoint | Notes |
| --- | --- |
| `GET /api/v1/routes/{id}/health` | the verdict, reasons and per-direction figures. A route with no vehicles is `UNKNOWN` (`INSUFFICIENT_VEHICLES`, or `NO_REALTIME_FEED` for a subway line), not a 404. An unknown route id is a 404. |
| `GET /api/v1/health/routes` | every route that has vehicles, worst first; `state` (repeatable) narrows it |

`meta.feedTimestamp` is the vehicle feed's own timestamp for the data analysed, so the age is the
age of the data and not of the response.

## The interface

- **Map, route panel**: the verdict, its confidence, the explanation, any caveats, and a collapsed
  "how this was measured" with every figure per direction. Absent figures read "Not measured",
  never zero.
- **Home**: how many routes could be judged, how many of those are uneven, and how many could not
  be judged at all, with the worst few listed. The count of routes with too few vehicles is stated
  because a count of flagged routes means little without it.
- Verdicts are asked for again every 30 s, and a failed refresh keeps what was shown, flagged.

## Where this differs from the plan

| Plan | Built | Why |
| --- | --- | --- |
| Alert severity as an input | not used | Every effect is `UNKNOWN_EFFECT`. |
| Bunching clusters as an input | not yet | Milestone 15 builds the detector. |
| Speed "over the last 15 min" | median of per-vehicle net progress, in memory | Needs no history query; rebuilt from live readings, at the cost of a few minutes of `SPEED_UNKNOWN` after a restart. |
| Verdict from two vehicles | `UNKNOWN` without a timetable, held to `NORMAL`-`UNSTABLE` with one | See the safeguards above. |
| Variation from any number of gaps | from three | Two saturate it. |
| A route page with health | the map's route panel and the home page | There is no route page yet; the plan builds none in a milestone. |
| Cache in Redis | Redis plus an in-process copy | The same degradation chain as live vehicles. |

## Known limitations

- **Spatial gaps are a proxy for time gaps.** Where speed varies sharply along a route, equal
  time headways are unequal distances.
- **The timetable spacing is derived from consecutive departures of any trip on a route and
  direction**, so a route with several start points or short turns has a shorter derived spacing
  than a rider waits at any one stop, and its expected spacing is too small. Routes at the
  extreme of the measured spread (below) are the ones to suspect.
- **One representative date per day type.** Statutory holidays and service changes within a
  month are not distinguished, and the hours just after midnight mix the previous service day's
  late trips with the new day's early ones.
- **A route with vehicles in only one direction** has no verdict for the other direction, which
  is itself information the route verdict does not yet use.
- **Terminal handling is a distance rule.** A vehicle merely passing within 200 m of a terminal is
  set aside for that cycle, and a layover further than 200 m from the end is not.
- **A restart costs a few minutes of timetable comparison**, since speed is measured live.
- Thresholds are uncalibrated (above).

## Measured on the live feed

**Monday 2026-10-12, 21:36 Toronto time**, about 13 minutes after the service started (so speeds
had warmed up), against the TTC's real vehicle feed. A single evening, not a distribution: this
is what one snapshot looked like, not a calibration.

| | |
| --- | --- |
| Routes with at least one vehicle | 155 |
| Of those, with a verdict | 49 |
| Of those, with no verdict | 106 (too few vehicles in either direction to judge) |

The 49 verdicts, from two snapshots 15 seconds apart:

| State | First snapshot | Second snapshot |
| --- | ---: | ---: |
| `EXCELLENT` | 4 | 3 |
| `NORMAL` | 18 | 17 |
| `UNSTABLE` | 18 | 19 |
| `MAJOR_DISRUPTION` | 9 | 10 |

By confidence, in the first snapshot: 12 high, 23 medium, 14 low.

**What the timetable comparison showed.** For the 46 route-directions with a timetable
comparison and at least four vehicles, the vehicles' own average gap divided by the spacing the
timetable implies (scheduled headway times measured speed) had a median of **1.09**, a middle
half from 0.93 to 1.31, and a range of 0.48 to 2.04. A median near one is the check that
matters: if the timetable-derived spacing were systematically wrong, or the speed conversion
biased, the median would sit well away from it. The spread is real and mostly one-sided: the
routes at the high end (observed spacing about twice what the timetable implies) include ones
whose timetable headway is 180 s, which is where the multi-pattern limitation below bites.

**What the verdicts say about the thresholds, which is a warning.** More than half of the
judged route-directions came out `UNSTABLE` or `MAJOR_DISRUPTION`, in the evening, on an
ordinary Monday. Service that uneven on that many routes at once is not plausible as genuine
disruption. The likelier reading is that the plan's starting bands are too tight for
gap-in-distance measured from one snapshot, where small numbers of vehicles on long routes
naturally produce high variation and large gaps against a timetable spacing derived from several
trip patterns. This is not tuned away here, because without known disruptions to check against
any retuning would be fitting the answer to a number I chose, so the bands are left as the
documented starting point and flagged. **Treat `UNSTABLE` and `MAJOR_DISRUPTION` as "spacing is
uneven by these measures", not as confirmation of a service problem**, until the bands are
calibrated against stored history in milestone 16. The explanation shown beside every verdict
states the figures, so a reader can judge for themselves.

Not measured: daytime or weekend service, a real known disruption, or how verdicts persist over
minutes and hours.
