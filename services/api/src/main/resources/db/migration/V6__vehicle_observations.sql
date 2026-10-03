-- Observed vehicle positions, the raw material for every historical figure
-- Trackline publishes.
--
-- Partitioned by day on source_timestamp rather than on arrival time, because
-- deduplication keys on (vehicle_id, source_timestamp) and a unique constraint on
-- a partitioned table must contain the partition key. Keying on our own clock
-- would need observed_at in the constraint, and two cycles seconds apart would
-- then both store the same reading.
--
-- Retention drops whole partitions instead of deleting rows: a DELETE across
-- millions of rows costs a vacuum, a DROP costs nothing.

CREATE TABLE vehicle_observations (
    vehicle_id            TEXT                   NOT NULL,
    -- The time the vehicle reported, as published. Partition key.
    source_timestamp      TIMESTAMPTZ            NOT NULL,
    -- When Trackline received it. The gap between the two is feed latency.
    observed_at           TIMESTAMPTZ            NOT NULL,
    route_id              TEXT,
    -- Stored verbatim and deliberately not a foreign key: realtime trip ids do
    -- not join to the schedule (a measured 2 of 1,050 matched).
    source_trip_id        TEXT,
    position              geography(Point, 4326) NOT NULL,
    bearing               REAL,
    -- As published. The feed reports a speed for every vehicle but many are zero,
    -- so it is kept separate from anything Trackline derives.
    source_speed          REAL,
    current_status        SMALLINT,
    current_stop_sequence INTEGER,
    stop_id               TEXT,
    occupancy_status      SMALLINT,
    ingestion_run_id      BIGINT                 NOT NULL REFERENCES ingestion_runs (id),
    PRIMARY KEY (vehicle_id, source_timestamp),
    CONSTRAINT vehicle_observations_bearing_check
        CHECK (bearing IS NULL OR (bearing >= 0 AND bearing <= 360))
) PARTITION BY RANGE (source_timestamp);

-- Latest positions for a route, which is how live queries will read this.
CREATE INDEX vehicle_observations_route_time_idx
    ON vehicle_observations (route_id, source_timestamp DESC);

-- One vehicle's history, for reconstructing how it moved.
CREATE INDEX vehicle_observations_vehicle_time_idx
    ON vehicle_observations (vehicle_id, source_timestamp DESC);

CREATE INDEX vehicle_observations_position_gix ON vehicle_observations USING gist (position);

COMMENT ON TABLE vehicle_observations IS
    'Deduplicated vehicle positions from GTFS-Realtime. Partitioned daily on source_timestamp.';
