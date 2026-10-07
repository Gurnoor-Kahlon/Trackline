# Placing vehicles on their routes

How Trackline works out where a vehicle is along its route and which way it is
going, what each threshold rests on, how well it works, and what it cannot do.

Every figure below was measured on real TTC data and says when and how. Where a
number comes from a test, the test is named, so it can be re-run.

## Why this exists

The vehicle feed publishes a position, a heading and a route. It does **not**
publish a direction (`direction_id` is absent from every vehicle), and it
publishes no distance along the route. Both are needed for everything that
follows: spacing between vehicles, bunching, route health. And neither can be read
off the schedule, because the realtime `trip_id` joins to the static schedule for
2 of 1,050 vehicles (see [data-sources.md](data-sources.md)).

So both are derived from geometry: the vehicle's position is projected onto the
route's shape.

## The algorithm

For every vehicle in a cycle that the feed assigned to a route:

1. **Project onto each direction's primary shape.** The primary shape is the
   variant the most trips use, one per route and direction. For each, record the
   geodesic distance from the vehicle to the shape (the *offset*), how far along it
   the nearest point is (the *fraction*), and the compass bearing the shape runs in
   at that point.
2. **Reject off-route vehicles.** A vehicle further than 150 m from *every* shape of
   its route is `OFF_ROUTE`. It is not placed anywhere; the offset is kept as the
   evidence.
3. **Choose the direction from the heading.** Among the shapes within 150 m, the
   direction is the one whose bearing is within 60° of the vehicle's reported
   heading. If exactly one agrees, the vehicle is `ASSIGNED_BY_BEARING`.
4. **Otherwise, choose it from movement.** If the heading agrees with neither
   direction or with both, or is missing, compare the vehicle with its previous
   stored reading. Moving forward by at least 30 m along exactly one shape makes it
   `ASSIGNED_BY_MOVEMENT`.
5. **Otherwise, say so.** The vehicle is `UNKNOWN_DIRECTION`, with a reason. It is
   never guessed. A vehicle placed in the wrong direction is worse than one left
   out, because it would distort the spacing of every vehicle around it.

Progress is the fraction of the chosen shape, scaled by its true length. Progress,
direction and fraction exist together exactly when a vehicle is assigned, and the
database enforces that (migration `V7`).

The vehicles that can't be placed each get their own status. The last column is
what a live cycle actually looked like (measured; see *Against the live feed*):

| Status | Meaning | Live share |
| --- | --- | ---: |
| `ASSIGNED_BY_BEARING` | heading singled out one direction | 75.2% |
| `ASSIGNED_BY_MOVEMENT` | movement singled out one direction | 2.5% |
| `UNKNOWN_DIRECTION` | on the route; nothing decided the direction | 15.2% |
| `OFF_ROUTE` | further than 150 m from every shape | 7.2% |
| `NO_ROUTE` | the feed assigned no route (deadheading, on a break) | 37.8% of all vehicles |
| `NO_GEOMETRY` | the route has no shape (one TTC route: 303) | none at the time |

The first four are shares of the ~937 route-assigned vehicles in a cycle; `NO_ROUTE`
is a share of every vehicle, since by definition those have no route to be a share of.

A `NULL` status in the database means *never projected*: rows written before this
existed, and any cycle where projection itself failed. A projection failure never
costs the readings it was derived from.

### Why heading first, and why two signals

A TTC route's two directions run along the same street, a few metres apart, so a
position alone cannot say which one a vehicle is on. On a two-way street the shapes
run in opposite directions, so exactly one agrees with the heading.

Movement is the independent second signal. Moving forward along a direction's
shape means advancing along it; the other direction's shape, running the other way,
is retreated along. Heading is preferred because it needs only the current reading;
movement is the fallback for the cases it cannot decide.

Having two independent signals is also what makes the method checkable without
ground truth. Where both were available on real data, **they agreed for 750 of 752
vehicles (99.7%)**.

## The thresholds, and what each rests on

All are in `application.yml` under `trackline.analytics.progress`. Each was set from
a measurement of 1,089 route-assigned vehicles in a real snapshot.

| Threshold | Value | Evidence |
| --- | ---: | --- |
| `max-offset-metres` | 150 | The nearest shape was within 5 m for half of vehicles and within 16 m for nine in ten. The rest are far: 1,006 within 50 m, 1,010 within 100 m, 1,013 within 150 m, 1,017 within 250 m, and the remainder hundreds of metres to 10 km away. The value sits in the empty middle, so it is **not sensitive**. |
| `bearing-tolerance-degrees` | 60 | Two directions of one street differ by about 180°, so a wide window still singles out one. Of 1,013 on-route vehicles, exactly one direction agreed for 860 (84.9%), none for 42, both for 111. |
| `min-movement-metres` | 30 | The observation writer only stores a moving vehicle after it has moved 25 m, so travel clears this; GPS jitter on a stationary vehicle does not. Concordance with the heading was measured at this value. |
| `max-previous-age` | 5 min | The feed advances about every 20 s and a stationary vehicle is stored every 60 s. |
| `tangent-window-metres` | 15 | Direction is read across about 30 m of shape: long enough to smooth a noisy segment, short enough to resolve a corner. |

## What was measured

### Progress along the shape

Shapes are stored in Web Mercator, which exaggerates distance by about 38% at
Toronto's latitude. So the planar geometry is used only to find a *fraction* along
a line, and every distance a person reads is measured on the geography.

Scaling that fraction by the true length is an approximation, because the Mercator
scale factor varies slightly along a route. Measured over every primary shape at
5%-95% of its length (470 shapes, 8,930 points): **worst error 15.7 m, 99th
percentile 10.9 m, mean 1.9 m, at most 0.074% of a route's length.** That is well
inside GPS noise, so no per-vehicle correction is applied.

Checked against an independent oracle on real geometry
(`RealGeometryProjectionIntegrationTest`): 1,177 vertices across the 30 real shapes
of the recorded snapshot's 15 routes, each projected back and compared with the
geodesic length of the polyline up to it, summed segment by segment.
**Median error 1.2 m, 99th percentile 12.9 m.** Six vertices exceeded 20 m; see
*Known limitations*, because every one is a place the shape visits twice.

The projection query itself is checked against distances worked out on the ellipsoid
in Java, independently of PostGIS (`ShapeProjectionRepositoryIntegrationTest`).
Measuring the offset in the planar system instead makes 4 of its 13 tests fail,
which was confirmed by introducing that bug on purpose.

### Direction, against geometry whose answer is known

A vehicle placed on a real shape and given a perfect heading should be assigned
that shape's direction. Over 1,112 samples taken along the 30 real shapes
(`RealGeometryProjectionIntegrationTest`):

| Outcome | Samples | Share |
| --- | ---: | ---: |
| Assigned, correct direction | 1,070 | 96.2% |
| Assigned, wrong direction | 1 | 0.09% |
| Unknown | 41 | 3.7% |

This is a **best case**: the heading is perfect, with no sensor noise, so it says
how often inference could be right given clean input, not how often it is right in
service. The one wrong assignment is the same vertex as one of the six progress
misses above, so even that traces to a location the shape visits twice.

### The recorded snapshot

The committed snapshot (`vehicles.pb`, recorded 2026-10-03 02:46 UTC) holds 40 vehicles, 23 of
them assigned a route across 15 routes. Placed against real geometry: **19 assigned
by bearing, 2 unknown, 2 off-route**, and 17 with no route.

### Against the live feed

**Measured 2026-10-06, 21:46-22:11 Toronto time**, with the API running from source
against the live TTC feed and the full imported network. 46,259 placements were
stored for 1,601 distinct vehicles.

**Each cycle.** Over 17 consecutive cycles (the first, which had no recent stored
readings to compare against, is excluded), with about 1,507 vehicles per cycle of
which about 937 had a route:

| | Mean | Range |
| --- | ---: | ---: |
| Assigned a direction (bearing + movement) | 77.7% | 76.4-78.5% |
| Unknown direction | 15.2% | 13.8-16.4% |
| Off-route | 7.2% | 6.7-7.8% |

Of the vehicles actually **on** their route, 83.7% were assigned a direction. The
shares barely move from cycle to cycle, so they describe the method rather than a
moment. Movement adds only 2.5 points, because the vehicles the heading cannot place
are mostly the ones that are not moving.

**Why a direction is unknown.** Taking the latest stored placement of each vehicle
early in the run, 129 were unknown-direction. Of those, **100 (78%) were within 300 m
of the end of their route and 104 (81%) reported a speed of zero.** They are buses laid over at a terminal, where the two directions'
shapes overlap and nothing is moving. Only 25 were more than a kilometre from a
terminal, which is where genuine ambiguity in service lies.
Leaving them unknown is the correct answer there, and it is where a spacing
calculation wants them excluded anyway. By reason, across 1,256 unknown placements:
78% `NO_FORWARD_MOVEMENT`, 12% `NO_PREVIOUS_READING`, 10% `CONFLICTING_MOVEMENT`.

**Self-consistency, without ground truth.** Trackline has no independent record of
which way each vehicle is really going, so correctness cannot be measured directly.
What can be checked is whether the answers contradict each other. A bus reverses
direction only at the end of a trip, so the same vehicle placed in opposite
directions in two readings under two minutes apart is either a terminal turnaround or
an error. Across **25,368** such consecutive pairs:

| Direction changed between readings | Pairs | Share of pairs |
| --- | ---: | ---: |
| Both readings at a shape end (terminal turnaround) | 280 | 1.10% |
| One reading at a shape end | 23 | 0.09% |
| **Mid-route** | **69** | **0.27%** |

Mid-route changes are consistent with short-turn trips and with the revisited-location
ambiguity below, and cannot be told apart from errors without ground truth. They are
not concentrated in stationary vehicles, which was the hypothesis worth ruling out:
0.29% of mid-route pairs for vehicles reporting speed zero, 0.32% at 2 m/s or more
(and 0.50% between, from only 1,201 pairs, which is too few to read). So distrusting
the heading of stationary vehicles would cost coverage without fixing anything, and
was not done.

Of the 24,996 consecutive pairs placed in the *same* direction, 26 (0.10%) implied
progress faster than 30 m/s and 22 (0.09%) moved backwards by more than 100 m.

## Known limitations

**A shape that visits the same place twice reports only one visit.**
`ST_LineLocatePoint` returns the nearest fraction, so where a shape passes the same
spot twice (a crossing, or a stretch driven out and back), the other visit is
invisible. This is more common than expected: 180 of the 470 primary shapes are not
simple lines.

How much it matters was measured two ways. *Exposure*: 87 of 1,013 on-route
vehicles (8.6%) in a real snapshot sat within about 30 m of a second, distant part
of their own shape. *Actual damage*: across 42,408 consecutive same-direction
reading pairs from 30 minutes of real history, **12 (0.03%) implied an impossible
speed (over 30 m/s) between readings, and 45 (0.11%) moved backwards by more than
100 m.** The shipped code, measured live in the evening, gave 0.10% and 0.09%
(above), the same order of magnitude at a different time of day. The nearest-visit choice resolves it correctly almost always, because
vehicles are rarely exactly at the ambiguous spot.

The fix, if it is ever worth making, is to constrain the search to a window around
the vehicle's previous progress. It was not done here because a 0.03% rate does not
justify the complexity.

**Loops are handled but were not exercised on real data.** The network contains
**no primary shape whose endpoints meet**, so the wrap-around logic (movement across
the seam of a loop) is covered by unit tests on constructed shapes only.

**Stationary vehicles are the hard case.** 47% of on-route vehicles in the measured snapshot reported a speed of zero,
and a stationary vehicle's heading is less reliable (78% had exactly one agreeing
direction, against 91% for moving vehicles). It is also where the two shapes
overlap most, at terminals. This is the main source of `UNKNOWN_DIRECTION`.

**One route has no shape.** Route 303 has no primary shape, so its vehicles are
`NO_GEOMETRY` rather than placed.

**Heading wins over movement.** If they ever disagreed the heading would be
believed. On real data they disagreed for 2 of 752, so the precedence rarely
matters, but it is a choice, pinned by a test.

**No memory between cycles.** Each vehicle is placed afresh each cycle, so a vehicle
whose direction was clear a minute ago and is ambiguous now is `UNKNOWN_DIRECTION`
now. Using the previous placement as a prior would resolve many of the stationary
cases; it was left for when a consumer needs it.

**Branches.** A route whose variants leave the primary shape is measured against the
primary only. A vehicle on a branch more than 150 m from it is `OFF_ROUTE`, which is
the known v1 gap named in the plan rather than a misplacement.
