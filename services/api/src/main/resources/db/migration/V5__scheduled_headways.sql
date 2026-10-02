-- Scheduled headways, precomputed per route, direction, day type and hour.
--
-- Route health compares observed vehicle spacing against what the schedule
-- intends. Deriving that from 4.36 million stop_times on every request would be
-- absurd, so it is computed once per import and read as a small lookup.
--
-- A row exists only where the schedule actually produced a gap to measure: an
-- hour with a single departure has no headway, and inventing one would be worse
-- than reporting that the hour is not comparable.

CREATE TABLE route_scheduled_headways (
    route_id               TEXT     NOT NULL REFERENCES routes (route_id) ON DELETE CASCADE,
    direction_id           SMALLINT NOT NULL,
    -- Which kind of service day the figure describes.
    day_type               TEXT     NOT NULL,
    -- Clock hour 0-23. Departures past midnight belong to the hour they happen,
    -- not to hour 24+, so a 01:15 departure on a late-night trip lands in hour 1.
    hour_of_day            SMALLINT NOT NULL,
    median_headway_seconds INTEGER  NOT NULL,
    -- Gaps the median was taken over. One gap is a weak median and callers lower
    -- their confidence accordingly.
    gap_count              INTEGER  NOT NULL,
    departure_count        INTEGER  NOT NULL,
    feed_version_id        BIGINT   NOT NULL REFERENCES gtfs_feed_versions (id),
    PRIMARY KEY (route_id, direction_id, day_type, hour_of_day),
    CONSTRAINT route_scheduled_headways_direction_check CHECK (direction_id IN (0, 1)),
    CONSTRAINT route_scheduled_headways_day_type_check
        CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT route_scheduled_headways_hour_check CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT route_scheduled_headways_headway_check CHECK (median_headway_seconds > 0),
    CONSTRAINT route_scheduled_headways_gap_count_check CHECK (gap_count > 0),
    CONSTRAINT route_scheduled_headways_departure_count_check CHECK (departure_count > 1)
);

COMMENT ON TABLE route_scheduled_headways IS
    'Median scheduled gap between consecutive departures, from one representative service date per day type.';
