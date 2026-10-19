-- History: what route health looked like over time, and the hourly summary reliability is read from.
--
-- route_health_samples is the raw record: one row per route direction per minute, written from the
-- analysis the ingestion cycle already runs, holding the verdict and the figures behind it so a
-- historical verdict can be explained as a live one can. It is the largest table Trackline adds, so it
-- is partitioned by day and retention drops whole partitions instead of deleting rows.
--
-- route_headway_hourly is the summary: one row per route direction per hour, holding counts and sums
-- rather than averages so any window can be combined exactly. Reliability is read from it, and it
-- outlives the samples it was built from.
--
-- Neither has a foreign key on route_id. This is history, and it must survive a schedule import that
-- replaces the routes table.

CREATE TABLE route_health_samples (
    route_id          TEXT        NOT NULL,
    direction_id      SMALLINT    NOT NULL,
    -- When the analysis was run, which is when the vehicles were seen: an observation, not the feed's own time.
    sampled_at        TIMESTAMPTZ NOT NULL,
    state             TEXT        NOT NULL,
    confidence        TEXT        NOT NULL,
    reason_codes      TEXT[]      NOT NULL,
    -- Vehicles the spacing was measured between.
    vehicles          SMALLINT    NOT NULL,
    -- The figures behind the verdict. Null where they could not be computed, never zero.
    variation         REAL,
    gap_ratio         REAL,
    supply_ratio      REAL,
    bunching_clusters SMALLINT    NOT NULL DEFAULT 0,
    -- Where the expected spacing came from, since a verdict judged against the vehicles' own average
    -- is a weaker statement than one judged against the timetable.
    basis             TEXT,
    -- The key is the route direction and the minute, which also makes a repeated sample a no-op, and it
    -- is the index every read uses. A key on a partitioned table must contain the partition key.
    PRIMARY KEY (route_id, direction_id, sampled_at),
    CONSTRAINT route_health_samples_direction_check CHECK (direction_id IN (0, 1)),
    CONSTRAINT route_health_samples_state_check CHECK (
        state IN ('EXCELLENT', 'NORMAL', 'UNSTABLE', 'MAJOR_DISRUPTION', 'UNKNOWN')),
    CONSTRAINT route_health_samples_confidence_check CHECK (
        confidence IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT route_health_samples_basis_check CHECK (basis IS NULL OR basis IN ('SCHEDULE', 'OBSERVED')),
    CONSTRAINT route_health_samples_counts_check CHECK (vehicles >= 0 AND bunching_clusters >= 0)
) PARTITION BY RANGE (sampled_at);

COMMENT ON TABLE route_health_samples IS
    'Route health verdicts sampled about once a minute, per route direction. Partitioned daily on sampled_at.';

CREATE TABLE route_headway_hourly (
    route_id           TEXT             NOT NULL,
    direction_id       SMALLINT         NOT NULL,
    -- The start of a UTC hour. Toronto is a whole number of hours from UTC, so a UTC hour is also a local one.
    hour_start         TIMESTAMPTZ      NOT NULL,
    samples            INTEGER          NOT NULL,
    excellent          INTEGER          NOT NULL,
    normal             INTEGER          NOT NULL,
    unstable           INTEGER          NOT NULL,
    major_disruption   INTEGER          NOT NULL,
    unknown            INTEGER          NOT NULL,
    -- Sums and the number of samples that had a value, so the mean over any set of hours is the sum of
    -- sums over the sum of counts. An average of hourly averages would weight a quiet hour like a busy one.
    variation_sum      DOUBLE PRECISION NOT NULL,
    variation_samples  INTEGER          NOT NULL,
    gap_ratio_sum      DOUBLE PRECISION NOT NULL,
    gap_ratio_samples  INTEGER          NOT NULL,
    -- Samples in which at least one bunch was found.
    bunching_samples   INTEGER          NOT NULL,
    first_sample_at    TIMESTAMPTZ      NOT NULL,
    last_sample_at     TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (route_id, direction_id, hour_start),
    CONSTRAINT route_headway_hourly_direction_check CHECK (direction_id IN (0, 1)),
    CONSTRAINT route_headway_hourly_states_check CHECK (
        samples > 0 AND samples = excellent + normal + unstable + major_disruption + unknown),
    CONSTRAINT route_headway_hourly_counts_check CHECK (
        variation_samples BETWEEN 0 AND samples
        AND gap_ratio_samples BETWEEN 0 AND samples
        AND bunching_samples BETWEEN 0 AND samples
        AND excellent >= 0 AND normal >= 0 AND unstable >= 0 AND major_disruption >= 0 AND unknown >= 0)
);

-- Retention deletes by age across every route.
CREATE INDEX route_headway_hourly_hour_idx ON route_headway_hourly (hour_start);

COMMENT ON TABLE route_headway_hourly IS
    'Hourly summary of route_health_samples: verdict counts and headway statistics as sums and counts.';
