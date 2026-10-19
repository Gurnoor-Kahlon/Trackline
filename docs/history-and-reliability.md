# History and Reliability

What Trackline records over time, how long it keeps it, and how "how steady has this route been" is
worked out from it, including when it declines to say.

Figures are measured and dated; costs per cycle are in [performance.md](performance.md).

---

## What is recorded

Three things build up over time. Two were already there; this milestone adds the third and the rules
that keep all of them bounded.

| Record | Table | Written | Holds |
| --- | --- | --- | --- |
| Where vehicles were | `vehicle_observations` | every cycle, for vehicles that moved or went quiet | positions, with the route progress and direction derived from them |
| Bunches | `bunching_events` | opened, updated and resolved by each analysis | one row per bunch over its life |
| **How the route looked** | **`route_health_samples`** | **about once a minute, per route direction** | **the verdict and the figures behind it** |

Only the third is new. It exists so that "how steady has route 501 been this week" can be answered from
what was measured, and so that a historical verdict can be explained the way a live one is.

## Route health samples

One row per route **direction** per sample, written from the analysis the cycle already runs. A sample holds
the state, the confidence, the reason codes, the number of vehicles the spacing was measured between, and the
figures behind the verdict: the headway variation, the largest-gap ratio, the supply ratio, how many bunches
were found, and whether the expected spacing came from the timetable or from the vehicles' own average. A figure
that could not be computed is stored as null, never zero.

- **A direction that could not be judged is recorded too**, as `UNKNOWN`, so the share of time a route was not
  judged is a measured figure and not an absence.
- **Every minute, not every cycle.** The analysis runs about every 20 seconds and a verdict seldom changes between
  two of them. A row per cycle would triple the largest table for no added information.
- **Only for a cycle the ingestion trusted as a whole.** A suspect cycle's verdicts were reached from a feed that
  was not believed, and recording them would put that into every figure derived later. The cost is a gap in the
  history, which the figures count as missing and do not fill.
- **A failure costs the sample and nothing else.** The readings and the verdicts are stored and published before
  this runs. A sample that fails is tried again at the next cycle, not after another minute.
- **The key is the route direction and the minute**, which makes a repeated sample a no-op and is the only index
  the table has. The table is partitioned by day, so removing old history drops a partition.

`sampled_at` is when the analysis ran, which is when the vehicles were seen. It is not the feed's own time.

## The hourly summary

Reliability is read from `route_headway_hourly`: one row per route direction per hour, rebuilt from the samples by
a statement that is safe to run again.

- **Counts and sums, not averages.** Each row holds how many samples reached each verdict and, for the headway
  variation and the gap ratio, a sum and the number of samples that had a value. The mean over any set of hours is
  then the sum of the sums over the sum of the counts. An average of hourly averages would weight an hour with a few
  samples like one with sixty, and the tests include the case where it matters.
- **Only finished hours.** The current hour is incomplete, and a summary of it would be mistaken for a whole one.
- **Idempotent.** An hour is rebuilt from its samples each time, so running twice, or again after an interruption,
  gives the same rows. It starts from the latest hour already summarised and not the one after it, so an hour that
  was summarised just as a cycle was still writing is corrected by the next run.
- **It outlives the samples.** The summary is kept longer than the raw rows it was built from, so dropping a
  partition never loses history that was not summarised first. The configuration refuses a retention that would.

Run every ten minutes, so the figures lag the present by up to an hour and a few minutes. The response says where
it counted through.

## Retention

Everything that grows is trimmed on a schedule, and each rule is a property.

| What | Kept | How | Why this long |
| --- | --- | --- | --- |
| Stored positions (`vehicle_observations`) | 14 days | drop whole daily partitions | the plan's figure; nothing reads them beyond a few minutes |
| Route health samples | 90 days | drop whole daily partitions | the plan's figure; reliability reads the hourly rows |
| Hourly summary | 365 days | delete rows | must outlive the samples; small |
| Resolved bunching events | 180 days | delete rows | the plan's figure; an open event is never removed |
| Resolved service alerts | 180 days | delete rows, and the entities they named by cascade | earlier milestones left retention to this one; an alert still in force is never removed |
| Ingestion runs | 30 days | delete rows | the plan's figure; must outlive the positions that refer to them |

- **A partition is dropped only when everything in it is older than the cutoff.** The decision reads each
  partition's own bounds from the catalogue, not its name: a name says which day it was made for and the bounds say
  what it holds. A partition holding noon to midnight of the cutoff day is kept, even though half of what it covers is
  too old.
- **A drop that cannot get its lock gives up after three seconds.** Dropping needs an exclusive lock, and while it
  waits every later insert into the table queues behind it. Ingestion writes constantly, so a long wait would stall it.
  The partition stays and is removed at the next run.
- **One step failing does not stop the others.** A locked partition or a constraint that objected is logged and counted,
  and the report names it.
- **Positions first, then the runs they refer to.** Every stored position refers to the ingestion run that fetched it,
  so a run can only go once its positions have. The configuration refuses a run retention shorter than the positions'.
  If a step does fail because something still refers to a run, the run stays and the failure is reported.
- **It deletes data.** The first run on a database that has been collecting for longer than a retention will remove
  what is past it. That is the feature; it is on by default and `trackline.history.enabled=false` turns off the
  schedule.

## Reliability

`GET /api/v1/routes/{id}/reliability?window=7d` for a window of `1d`, `7d`, `30d` or `90d`.

### What a figure means

A **sample** is one route direction at one moment, so a route running both ways counts twice a minute. Over the
samples in the window:

- **Steady share**: samples that were `EXCELLENT` or `NORMAL`, over samples that reached a verdict.
- **Headway variation**: the mean coefficient of variation of the gaps between vehicles, over the samples that had one.
- **Running together**: samples with at least one bunch, over samples that reached a verdict.
- **Unjudged**: samples with too few vehicles for a verdict. They are in no share, and the page says how large a
  share of the samples they were, so a route that was mostly unjudged does not look steady on the strength of the rest.

Both directions are pooled. The route's live state is the worse of its two directions, so a route's steady share can
read higher than the share of time its live state was steady.

### When it declines to answer

A percentage over a few hours is an anecdote that looks like a statistic, so a figure is withheld unless enough stands
behind it. The numbers are properties and round starting points, not fitted.

| Gate | Needs | Why |
| --- | --- | --- |
| An hour counts | 20 samples in it, over both directions | an hour the application ran for three minutes says nothing about that hour |
| The route as a whole | 24 such hours | a day, whatever the day was |
| The route as a whole | 240 samples that reached a verdict | a route always seen with one vehicle has been watched, not judged |
| A bar of the hour-of-day chart | 60 judged samples from 3 different days | one Tuesday evening does not show what evenings are like |
| A bar of the day-of-week chart | 60 judged samples from 2 different days | a weekday needs more than a week of recording to appear |

Below the minimum the answer is `INSUFFICIENT_HISTORY` with no figures at all, the reason, what there is, and what
is needed. The reasons are `NO_SAMPLES`, `TOO_FEW_HOURS` and `TOO_FEW_JUDGED_SAMPLES`. `NO_SAMPLES` means no
*summarised* hour: a route first recorded in the current hour is in that state until the hour ends, and the page says so
instead of claiming nothing was recorded. An hour with too few samples is left out of every figure, and the response says
how many were. A bar without enough behind it is absent, not drawn at zero
and not interpolated. A real zero is drawn.

The hours of the day and the weekdays are read on Toronto's clock, since whether 5 pm is a rush hour is a fact about
the city's clock, not UTC's. Nothing in it is smoothed or filled.

### Where it differs from the plan

| Plan | Built | Why |
| --- | --- | --- |
| `INSUFFICIENT_HISTORY` as a 404 with a reason | a 200 with `status: INSUFFICIENT_HISTORY` | The route exists, and "not enough yet" is the true state of it. A 404 makes every client treat it as an error and lose the counts, which are what let a page say "9 of 24 hours". An unknown route is still a 404. |
| `route_health_samples.inputs JSONB` | typed columns | The figures are fixed and read in bulk, and a typed column is smaller and says what it holds. |
| Vehicle observations "aggregated into `route_headway_hourly` and deleted" | positions deleted after 14 days; the hourly summary built from the health samples | Headway statistics from raw positions would mean recomputing the analysis over history. The verdicts already carry the headway figures and are recorded as they were. |
| Observations retention 14 days, a `DROP` | the same, reading the partition bounds | The existing partitions were made with bounds in the database's time zone, so their names alone do not say what they hold. |
| Route health sampled "every 60 s" | the same, from the analysis the cycle already runs | No second scheduler needing a second copy of the vehicles. |

## The interface

The route's panel has a "How steady it has been" section with a window picker (24 hours, 7, 30 or 90 days).

- Below the minimum it shows a card: a headline, a bar of how much is there against how much is needed, and a sentence
  saying why and since when the route has been recorded. No chart is drawn.
- Above it, three figures (steady share, how much the spacing varies, how often vehicles ran together), a sentence
  with the samples, hours and days behind them and the time they were counted through, and the two charts.
- Each chart is a single series in one colour, with a hairline grid, rounded bar tops, and a dot in place of a bar where
  there is not enough behind one. The reading of a bar follows the pointer and the arrow keys, a sentence describes the
  whole chart for a screen reader, and a table lists every slot, so no value is reachable only by hovering.
- It is shown for routes that publish vehicles; nothing is ever recorded for a subway line.

## Known limitations

- **A sample is a minute, not a trip.** A route that is unstable for forty seconds of every minute and one that is
  unstable for forty minutes of every hour look the same in a share.
- **Both directions are pooled.** A route bad in one direction and fine in the other shows the average.
- **Reliability inherits route health's limits**: spatial gaps stand in for time gaps, a route can look regular while
  running late, and the bands are uncalibrated starting points. A share of "steady" is a share of those bands.
- **The summary lags** by up to an hour and a few minutes.
- **A sample that arrives for an hour earlier than the latest summarised one is not picked up.** Samples are written
  as the cycle runs, so this needs a clock that went backwards.
- **A restart forgets when the last sample was**, so two can land less than a minute apart. The key makes an exact
  repeat harmless; two near ones are both kept.
- **Gaps are not filled.** While the application is not running nothing is recorded, and an hour with too few samples
  is left out, so a route is measured only for the time it was watched.
- **The history is of Trackline's own observation.** It is not the agency's record of service.

## Measured on the live feed

**Run:** Sunday 2026-10-18, API from source on the same laptop as the database, from 19:30 to 20:19 Toronto time
(23:30:38 to 00:18:56 UTC), against a database that already held positions from earlier runs. It was the first run after
the migration, so the history started empty. One run, 48 minutes, on a quiet evening: **read every figure below as a
measurement of that run and not as a typical day.** Observation retention was raised to 30 days for the run
(`TRACKLINE_HISTORY_RETENTION_OBSERVATIONS=30d`) so the trim could not delete earlier development positions; the trim
ran (19:32) and removed nothing.

### What was recorded

| | |
| --- | --- |
| Samples taken | **42** moments in 48.3 minutes |
| Rows written | **11,507**, a mean of 274 per sample, over 321 route directions on 161 routes |
| Time between samples | about 71 s on average. The interval is 60 s, and a sample is taken by the first cycle at least that long after the last, so cycles about 30 s apart (the mean in this run) round it up |
| Cost of writing a sample | **mean 37 ms**, 142 ms for the first (it creates the partitions) |
| For comparison, vehicle cycles in the same run | 99 cycles, mean 945 ms |
| Hourly summary | 321 rows for the one finished hour (6,911 samples), written in 14 ms at 20:02 and rebuilt unchanged in 53 ms at 20:12 |

### What it costs to keep

| | Measured | Extrapolated, not measured |
| --- | --- | --- |
| Health samples | 11,507 rows in 2.17 MB: **189 bytes a row** (137 for the row, the rest its key) | at one sample every 71 s and 274 rows, 1,221 samples and about 335,000 rows a day: **about 63 MB a day, about 5.7 GB at the 90-day default** |
| Stored positions | 93,044 rows in 31 MB: **353 bytes a row** | at the 1,903 rows a minute this run stored, about 2.7 million rows and **about 0.97 GB a day, about 13.5 GB at the 14-day default** |
| Hourly summary | 321 rows in 176 kB | about 2.8 million rows a year; small |

The extrapolations multiply a Sunday evening by a day. A weekday has more vehicles and more routes with some, and the
night has fewer, so they are an order of magnitude and not a forecast. **They matter more than the rest of this page.**
The plan's retentions (14 days of positions and 90 of samples) would, run continuously, hold about 19 GB between
them, against a 20 GB database that must also hold the 1.5 GB schedule. **Those defaults do not fit the budget.** I kept
them because they are the plan's and changing them deletes data sooner, but the measurement says to lower them before
this runs continuously on a small database. Seven days of positions (about 6.8 GB) and thirty of samples (about
1.9 GB) would fit with room to spare; both are one property (`trackline.history.retention`). Positions are the larger cost
and are only read for the last few minutes. Samples are the only record of the figures a recalibration would need.

### What the samples say

The first distribution of verdicts Trackline has stored, over all 11,507 samples:

| Verdict | Samples | Share of all | Share of judged |
| --- | ---: | ---: | ---: |
| `UNKNOWN` (too few vehicles to judge) | 7,525 | 65.4% | |
| `NORMAL` | 1,278 | 11.1% | 32.1% |
| `UNSTABLE` | 1,070 | 9.3% | 26.9% |
| `MAJOR_DISRUPTION` | 946 | 8.2% | 23.8% |
| `EXCELLENT` | 688 | 6.0% | 17.3% |

Two thirds of samples were not judged at all, since a quiet evening leaves most route directions with one vehicle or
none, and the shares only describe the judged third. Of those, **half (50.6%) were `UNSTABLE` or `MAJOR_DISRUPTION`** and
22.6% had a bunch in them. That agrees with what the first route health run found, that the bands call a large share of
judged routes uneven, and it is more likely to mean the bands are strict than that half of Toronto's service was
disrupted on a Sunday evening. **Nothing has been recalibrated.** A distribution shows what the bands find, not whether they are
right, and a calibration needs a view of which verdicts a rider would agree with. What this milestone adds is the record
to calibrate from.

### The endpoint

`GET /routes/501/reliability`, 25 requests over `curl` to localhost, a new connection each: **minimum 16.4 ms, median
19.3 ms, maximum 28.0 ms**, a 659-byte response. At the time the route had one hour recorded of the 24 needed, so this is the
`INSUFFICIENT_HISTORY` path: one indexed range read of a handful of rows. Not measured: the available path over a full
window, which reads up to about 4,300 hourly rows for a route running both ways over 90 days, and the Redis-down case (it
does not use Redis).

The page showed "1 of 24 hours recorded", with the progress bar and the sentence about when the first hour started. A
synthetic response patched into the browser session, never stored, was used to check that the two charts lay out
(18 bars and 6 dots on the hour chart, 3 and 4 on the day chart, no horizontal overflow); the chart was not seen as a picture,
since the browser pane would not capture one.
