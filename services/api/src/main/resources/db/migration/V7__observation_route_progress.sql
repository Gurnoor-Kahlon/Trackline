-- Where each vehicle is along its route, and which way it is going.
--
-- The realtime feed publishes neither a direction nor a distance along the route,
-- so both are derived by projecting the reported position onto the route's
-- primary shape. They are stored beside the reading they were derived from, and
-- kept apart from the published columns so it stays obvious which values came
-- from the agency and which Trackline computed.
--
-- Every column is nullable, and a null projection_status means "never projected":
-- the rows written before this migration, and any cycle where projection itself
-- failed. That is deliberately different from every other status, each of which is
-- a positive statement about the vehicle.

ALTER TABLE vehicle_observations
    -- What the projection concluded; see the check below for the vocabulary.
    ADD COLUMN projection_status     TEXT,
    -- 0 or 1, the GTFS direction whose primary shape the vehicle was matched to.
    ADD COLUMN inferred_direction_id SMALLINT,
    -- True metres from the start of that shape. Scaled from the planar fraction, which
    -- a measurement over all 470 primary shapes bounds at 15.7 m of error.
    ADD COLUMN progress_m            DOUBLE PRECISION,
    ADD COLUMN progress_fraction     DOUBLE PRECISION,
    -- True (geodesic) distance from the reported position to the matched shape. Kept
    -- when a vehicle is rejected as off-route, where it is the reason for rejection.
    ADD COLUMN projection_offset_m   REAL;

ALTER TABLE vehicle_observations
    ADD CONSTRAINT vehicle_observations_projection_status_check CHECK (
        projection_status IN (
            'ASSIGNED_BY_BEARING',
            'ASSIGNED_BY_MOVEMENT',
            'UNKNOWN_DIRECTION',
            'OFF_ROUTE',
            'NO_ROUTE',
            'NO_GEOMETRY')),
    ADD CONSTRAINT vehicle_observations_inferred_direction_check CHECK (
        inferred_direction_id IN (0, 1)),
    ADD CONSTRAINT vehicle_observations_progress_check CHECK (
        progress_m >= 0 AND progress_fraction BETWEEN 0 AND 1),
    ADD CONSTRAINT vehicle_observations_projection_offset_check CHECK (
        projection_offset_m >= 0),
    -- A direction and a position along the route exist exactly when the vehicle was
    -- assigned. Anything else claiming a progress figure would be a number with no
    -- direction to measure it along, and downstream spacing maths would use it.
    ADD CONSTRAINT vehicle_observations_projection_consistency_check CHECK (
        CASE
            WHEN projection_status IS NULL THEN
                inferred_direction_id IS NULL AND progress_m IS NULL
                AND progress_fraction IS NULL AND projection_offset_m IS NULL
            WHEN projection_status IN ('ASSIGNED_BY_BEARING', 'ASSIGNED_BY_MOVEMENT') THEN
                inferred_direction_id IS NOT NULL AND progress_m IS NOT NULL
                AND progress_fraction IS NOT NULL AND projection_offset_m IS NOT NULL
            ELSE
                inferred_direction_id IS NULL AND progress_m IS NULL
                AND progress_fraction IS NULL
        END);

COMMENT ON COLUMN vehicle_observations.projection_status IS
    'Outcome of projecting the position onto the route shape. NULL means never projected.';
