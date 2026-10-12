# Service Alerts

How the TTC's alerts become stored, queryable records, what the lifecycle claims and
does not, and where it differs from the original plan.

Figures here are measured and dated. The per-cycle timings are in
[performance.md](performance.md).

---

## What the feed gives, and what it does not

Measured **2026-10-12** on the live feed: 11 alerts, 97 route references, 11 stop
references.

| Field | State |
| --- | --- |
| `active_period` | absent, and deprecated in the specification itself |
| `severity_level` | absent |
| `cause` | present on 11 of 11, and `UNKNOWN_CAUSE` on 11 of 11 |
| `effect` | present on 11 of 11, and `UNKNOWN_EFFECT` on 11 of 11 |
| `header_text` | cut off by the publisher at about 31 characters, mid-word, on 5 of 11; the longest was 32 |
| `description_text` | the whole sentence |
| informed entities | routes (all 97 resolved against the imported schedule); stops on some, of which 3 of the 11 stop ids are not in the schedule |

Consequences, each one a decision in the code:

- **No lifecycle can be read from the feed.** Trackline observes one (below).
- **Nothing is grouped or ranked by effect, cause or severity** because there is nothing
  to group by. The page groups by effect only when the TTC states more than one, and
  today it states none, so it shows one list. It never works an effect out from the
  wording of the text: a guess shown as a category would be believed.
- **The description is the display text.** The header is a fallback for an alert that
  has none. A recorded-payload test pins the truncation, and the live contract test
  (run on demand, not in the normal build) checks that the live feed still has it, so
  the assumption is revisited when it changes rather than left wrong.
- **Route and stop ids are stored as published and are not foreign keys.** Alerts arrive
  independently of schedule imports, and an id the schedule lacks is a fact about the
  alert, not a reason to lose it. It is returned with `known: false` and shown plainly.

## The lifecycle

Each row of `service_alerts` is one continuous stretch of an alert being in the feed:

| Column | Meaning |
| --- | --- |
| `first_seen_at` | the first cycle that contained it |
| `last_seen_at` | the latest cycle that contained it |
| `last_changed_at` | when its content last differed from what was stored |
| `resolved_at` | the first cycle that did not contain it; null while it is still there |

These are **upper bounds, never the moment itself**. The alert began at or before
`first_seen_at` and ended at or before `resolved_at`. The interface says "first seen" and
"gone from the feed", never "began" or "ended", and a test asserts it.

On each cycle, every alert in the payload is sorted into one outcome, by a pure function
(`AlertReconciler`) that the tests exercise without a database:

| In the payload | Stored | Outcome |
| --- | --- | --- |
| yes | not open | **opened**: a new alert, or one returning after it was resolved |
| yes | open, same content | **confirmed**: `last_seen_at` advances |
| yes | open, different content | **changed** in place, keeping its stretch and `first_seen_at` |
| no | open | **resolved** |

"Same content" is a SHA-256 over the cause, effect, header, description, URL and the
distinct route/stop pairs, so a reordered or repeated entity list, both of which the real
feed does, is not an edit. The publisher's id is left out of the hash: it says which
alert this is, not what it says.

An alert that disappears and comes back is a **new stretch** with a new row, and the old
row is left as it was. Only one open stretch per publisher id can exist, enforced by a
partial unique index, not just by the code.

### When a payload is not applied

Applying a payload resolves everything missing from it, so a payload that is wrong in the
direction of "fewer" would end alerts that are still in force. A cycle is applied only
when it is judged trustworthy:

| Cycle | What happens |
| --- | --- |
| `SUCCESS` | applied |
| `NO_CHANGE` (the publisher's timestamp has not advanced) | `last_seen_at` of the open alerts advances: they were fetched again and are still there |
| `SUSPECT` (too many entities rejected, or the count collapsed) | **not applied**; stored alerts are left as they were |
| `FAILED` (fetch or parse failed) | not applied |

A feed that has genuinely cleared is `SUSPECT` once, because the count fell, and trusted on
the next cycle, which measures against the new count. So clearing is recorded about a
minute later than it happened; nothing is ever ended on a single implausible cycle. An
alert rejected for naming no route or stop counts as absent, so it is resolved.

Applying a payload is one transaction, and the in-memory count of open alerts is updated
only after the commit. A failure halfway leaves the stored alerts exactly as they were; a
test makes the last step fail and checks that the new alert was not kept and the omitted
one was not resolved.

### Restarts and gaps

Alerts that left the feed while Trackline was down are resolved at the first cycle after
it comes back, so `resolved_at` can be later than the real end by the length of the outage.
The same is true of any gap in polling. It is an upper bound, as everywhere else.

### Untrusted text

Alert text comes from a third party. A NUL character cannot be stored in PostgreSQL and
would fail every write, every cycle, for as long as that alert stayed in the feed, so it
is stripped in the parser. Text is also bounded (header 300 characters, description 4,000,
URL 2,048, ids 128), cut on a character boundary so a two-unit character is never split.
The longest real description measured (2026-10-12) was 233 characters, so truncation is a
defence and not a routine event.

## The API

| Endpoint | Notes |
| --- | --- |
| `GET /api/v1/alerts` | `active` (default `true`; `false` means those that have left the feed), `route`, `stop`, `page`, `size` (max 100). Newest first. |
| `GET /api/v1/alerts/{id}` | one stretch, active or resolved; 404 if unknown |

`meta.source` is `STORED`: the database is the record for alerts and there is no cache in
front of it. `meta.feedTimestamp` is the publisher's timestamp for the latest cycle that
was applied or confirmed. `meta.stale` is true after five minutes, not one: the feed is
polled once a minute, so the vehicle threshold would flag healthy alerts as stale much of
the time. An empty list with `source: NO_DATA` means alerts have never been ingested, which
is not the same as there being none.

`cause` and `effect` are returned as the specification names them, so a client sees
`UNKNOWN_EFFECT` and not a missing field. A number the specification does not define is
returned as null rather than guessed.

The network status and the network stream carry `activeAlerts`: the open count, or null
until alerts have been ingested once. Null is not zero. The count is held in memory and
updated after each applied cycle; after a restart it is read back from the table, and a
table that has never held an alert is treated as unknown.

## The interface

- **`/alerts`**: active alerts, with an Ended view fetched only when asked for, a mode
  filter and a route filter. The list is polled every minute, and a failed refresh keeps
  what was shown, flagged, instead of replacing it with an error.
- **Home**: the newest three active alerts, labelled as newest because there is no
  severity to rank by.
- **Map**: the alerts naming the selected route, and the count beside the live status
  chip. "Naming" is the exact claim: an elevator alert names every route that serves the
  station, so a route can carry alerts that do not touch its vehicles.
- Ages are measured against the **server's** clock, so a laptop with the wrong time
  still reports the right ones.

## Where this differs from the plan

| Plan | Built | Why |
| --- | --- | --- |
| `alert_id` is the primary key (the feed's id) | a surrogate `id`, with `source_alert_id` beside it | An alert that returns must be a new stretch with its own start, which a primary key on the publisher's id cannot hold. |
| Alerts grouped by effect | grouped by effect only when more than one is stated | The TTC states none, so a heading would suggest structure that is not there. |
| Alerts on route, stop and home | route, home and the map chip; **not stops** | There is no stop page, and the plan has no milestone that builds one. The API already filters by `stop`. |
| Alert effect as a route-health input (milestone 14) | not available | With every effect `UNKNOWN_EFFECT`, a "no service" alert cannot be distinguished from an elevator outage by anything but the text. Route health will have to say so. |

## Known limitations

- Resolution is detected one cycle late and bounded by the polling interval and outages.
- Stop ids on alerts match the imported schedule less often than route ids (8 of 11 on the
  live feed). Unmatched stops are shown by id.
- There is no retention yet: resolved alerts are kept indefinitely. Milestone 16 owns it.
- The text is the publisher's, shown as published. Some alerts state dates in their text
  ("back in service October 16, 2026"); nothing parses them, so they are not used as an
  end time.
