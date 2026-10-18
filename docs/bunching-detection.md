# Bunching Detection

How Trackline decides that vehicles on a route are running in a pack, what a recorded bunch
does and does not claim, and where the detector is weaker than it looks.

Figures are measured and dated; the cost per cycle is in [performance.md](performance.md).

---

## What counts as a bunch

**A pack alone is not bunching.** A dense, evenly spaced route is a pack everywhere, and nobody
waits longer for it. Bunching is vehicles running close together **while a large gap opens
elsewhere on the same direction**, which is what a rider actually experiences: a long wait, then
several buses at once. Both halves are required, and the negative case (close vehicles, no gap
anywhere) is the one the tests are built around.

For one direction of one route, measured in metres along the route's own shape:

1. The usable vehicles are sorted by position and the gaps between neighbours taken (on a loop
   including the gap that wraps past the end).
2. Spacing is judged against the **expected spacing**, the same yardstick route health uses
   ([route-health.md](route-health.md)): the timetable's headway times the route's measured speed
   when the timetable covers today, otherwise the vehicles' own average gap. It is decided in one
   place (`SpacingYardstick`) so health and bunching cannot disagree about the same gap.
3. Two neighbours are **close** when the gap between them is below **0.35** of the expected
   spacing. A run of close neighbours is a pack.
4. A pack is reported only if some gap not inside a pack is above **1.8** times the expected
   spacing. That gap is the **service gap**, the largest such gap on the direction, and is
   returned with the bunch.
5. Severity: `SEVERE` if the pack holds **three or more** vehicles or the service gap is above
   **2.5** times the expected spacing, otherwise `MODERATE`.

Fewer than three vehicles are never judged: with fewer, no gap elsewhere can be established. **Without a
timetable the fewest is four**, and the reason is arithmetic: judged against their own average, three
vehicles with one close pair leave the other gap at about twice that average, so a bunch would be found
whenever a pair was close, whatever the road looked like. Four vehicles are the fewest at which a pack beside
a wide gap says something the average did not already guarantee. This was found on the first live run, which
recorded dozens of such tautological bunches in its first minute, before the timetable comparison had warmed up.

**These cut-points are a starting judgement, not a calibration.** They are round numbers with no
ground truth of known bunching to fit them against, held in `trackline.analytics.bunching`, and
the measured outcome is at the end. A figure exactly on a boundary belongs to the calmer side,
and the tests check both sides of every one.

### What is left out before the detector sees it

- **Vehicles with no direction** (off-route, direction unknown, no geometry): they cannot be placed
  on a stretch of route.
- **Vehicles that have stopped reporting**: a position more than two minutes older than the feed's
  is not where the vehicle is.
- **Vehicles at a terminal**: within 200 m of either end of a non-loop route, vehicles sit out layovers
  side by side. A layover is not a bunch, and removing them before detection means it never can be
  mistaken for one. The plan described a separate "terminus layover" suppression; leaving them out of
  the input is the same rule without a second code path.

On a loop the pack and the gap can straddle the point where the shape begins and ends, so vehicles are
treated as a ring and a pack across the seam is one pack.

## Following a bunch over time

A bunch lasts as long as its vehicles stay together, which can be many minutes. Writing a row per
cycle would turn one bunch into hundreds, so each is an **event**: opened when first seen, updated while
it persists, resolved when it has gone.

- **Confirmed first.** A new pack is recorded only after being seen on **three consecutive analyses**, about a
  minute, and is then dated from its first sighting, so the wait costs no time. The first version recorded
  every pack at once, and on the live feed most lasted one or two analyses (measured below): vehicles
  overtaking at a stop, a position jittering across a projection boundary. The history was mostly noise. The
  wait is held in memory (`BunchingConfirmer`), so a restart costs a minute of events and nothing else. A pack
  that continues an event already open skips the wait, since a brief absence must not be able to end an event.
  The strip and the route's health still show a pack the moment it is found: they describe the road now, and
  the wait applies only to what is written down.
- **Same bunch.** A new sighting continues an open one when they are on the same route and direction and
  **share at least two vehicles**. The plan matched on the exact vehicle set, which would make a bunch that
  picks up a third bus a new event; sharing two is the same bunch with a vehicle joining or leaving.
  `peak_vehicles` and `peak_severity` keep the most it held and the worst it reached.
- **Gone.** An open bunch not seen for **two consecutive analyses** is resolved, so a bunch flickering across
  a threshold is one event. A bunch seen again after one miss simply continues and its miss count starts over.
- **Overlaps.** If one open bunch overlaps two new ones the larger overlap continues it and the other is new; if
  two open bunches merge into one the larger overlap continues and the weaker is missed and later resolved. These
  are decided by a pure function (`BunchingTracker`) tested without a database.

The times are what Trackline saw, as for alerts, and each bounds the moment it describes:

| Column | Meaning |
| --- | --- |
| `first_seen_at` | the first analysis that saw it: the pack formed at or before this |
| `last_seen_at` | the latest that did |
| `resolved_at` | the analysis that found it gone for the second time running: it broke up at or before this, and not before `last_seen_at` |

So `resolved_at` is later than the real break-up by up to two analyses, about a minute, plus any gap in
polling. The interface says "first seen" and "apart since", never "began" or "ended".

### When an analysis is not applied

Applying an analysis resolves every open bunch it does not contain, so one built from a fleet that suddenly
shrank would end real bunches. It is applied only for a vehicle cycle the ingestion trusted as a whole
(`SUCCESS`). A `SUSPECT` cycle, one that rejected too many entities or whose vehicle count collapsed, leaves
the stored events exactly as they were: the cost is a delay, never a wrongly resolved bunch. Applying is one
transaction, so a failure halfway leaves nothing half-done, and a failure costs the events for that cycle and
nothing else (the readings and the verdicts are already stored and published).

## What route health does with it

A direction with a bunch gets the reason `BUNCHING` and a count of groups in its figures, and is at least
`UNSTABLE`. Because a bunch is only reported when a gap above 1.8 times the expected spacing exists, and route
health already calls any such gap `UNSTABLE`, the floor changes no state today: it names the cause. It is stated
as its own rule so health stays correct if the two ever use different yardsticks.

## The endpoint and the interface

`GET /api/v1/routes/{id}/bunching` returns, for each direction that has vehicles, every placed vehicle in order
along the route with its role (`SPACED`, `BUNCHED`, `AT_TERMINAL`, `STALE`), the packs found, what spacing they were
measured against and where that came from; and the route's events: those open now and those resolved in the last 24
hours, newest first, at most 25. Positions come from the last analysis (a cache read, `LIVE` or `CACHED` as for
health); events come from the database. A route with no vehicles right now still lists its recent events, and before
any analysis the answer is `NO_DATA` and empty, not a healthy route. An unknown route is a 404.

The route panel's **spacing strip** draws each direction's vehicles along a line with a bracket round any pack.
Shape as well as colour distinguishes roles (a filled circle for a vehicle in the spacing, a diamond for one in a
pack, a hollow circle for one left out), a severe pack is drawn differently from a moderate one, and a pack across the
start of a loop is drawn as two brackets meeting the edges. The same information is a sentence for a screen reader and a
list of positions under the picture. Beneath it are the recent events.

## Where this differs from the plan

| Plan | Built | Why |
| --- | --- | --- |
| Expected spacing as `length / (n - 1)`, reconciled with the timetable | the timetable-implied spacing, else the vehicles' own average gap | The length over `n - 1` counts empty stretch at the ends as spacing; the average gap does not. And it is the yardstick health uses, so the two agree. |
| Terminus layover suppression as a rule inside the detector | terminal vehicles removed from the input | The same effect with no second code path. |
| Events matched by exact vehicle set | matched by at least two shared vehicles | An exact set makes a bunch that gains a bus a new event. |
| A bunch is an event as soon as it is found | recorded after three consecutive analyses, dated from the first | Most packs recorded at once were gone within two analyses. |
| Three vehicles are enough | three with a timetable, four without | See above: with three and no timetable the result is arithmetic. |
| `evidence JSONB`, `cluster_gap_m`, `expected_gap_m`, `trailing_gap_m` | typed columns, with `service_gap_m` for the gap elsewhere | The figures are fixed and queried; JSON would hide them. "Trailing gap" read as adjacent to the pack, which is not what the rule measures, so it is named for what it is. |
| Fixtures from real trimmed snapshot for an end-to-end sanity assertion | a synthetic east-west route through the real cycle, plus the recorded fleet for the health pass | A real snapshot gives no known answer to assert against, so the end-to-end test builds a feed whose positions are known and checks the whole path from feed to stored event. |

## Known limitations

- **Spatial gaps stand in for time gaps.** Where speed varies sharply along a route, equal time headways are
  unequal distances, and a pack in distance is not always a pack in time.
- **The expected spacing carries every limitation route health's does**, including a timetable spacing that is
  too small on routes with several start points, which lowers the gap a pack must sit beside to be reported.
- **A bunch that straddles a terminal zone is cut.** A vehicle within 200 m of an end is left out, so a pack that
  is half in the zone is seen as the vehicles outside it.
- **Two vehicles are never judged on a direction that has only two**, even if they are side by side.
- **A direction with no direction-assigned vehicles shows nothing**: vehicles that could not be placed are not in the
  strip, and are counted in the route's health.
- **Resolution is detected a minute late**, and after a restart or gap in polling it is bounded by that gap.
- **Events are kept indefinitely** until the retention work in milestone 16.
- The cut-points are uncalibrated.

## Measured on the live feed

Both windows ran against the real TTC feed on a freshly emptied `bunching_events` table, on a laptop, in the
Tuesday evening rush (Toronto time, 2026-10-13). They are two observations, not a benchmark, and neither is
ground truth: nothing says which of these packs a rider would have called bunching.

| | First version (record every pack at once) | Current (confirmed after three analyses) |
| --- | --- | --- |
| Window | 17:40 to 18:03, about 22 minutes | 18:11 to 18:32, about 21 minutes |
| Events recorded | 552 | 306 |
| Resolved by the end | 435 | 249 |
| Resolved after four sightings or fewer | 303 (70%) | 94 (38%) |
| Resolved after one or two sightings | 189 (43%) | none, by construction |
| Resolved after ten sightings or more | 54 (12%) | 65 (26%) |
| Median sightings of a resolved event | 3 | 6 |
| Routes with an event | 86 | 68 |

The confirmation step did what it was added for: nothing short-lived is recorded and the median event now spans
six analyses, about two minutes. It did not make the detector selective. Sampled at 18:27, **65 packs were open at
once across 45 route-directions**, 66% of events reached `SEVERE` (201 of 306), and 262 of 306 were judged against a
timetable spacing. A network where two thirds of the packs seen in a rush hour are severe is more likely to have
cut-points that are too sensitive at rush hour than to be that unreliable, so **read the counts as what these
thresholds find, not as how often TTC service bunches**. The first thing to do with a longer history is tune
`close-ratio` and `service-gap-ratio` against it, which is the work of milestone 16's samples.

Not measured: how many of these a rider would agree with, and how the count varies across the day. Both windows were
in the evening peak.
