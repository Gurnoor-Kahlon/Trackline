-- Raw shape points exactly as published in shapes.txt.
--
-- Kept as staging rather than folded straight into route_shapes: the import owns
-- loading what the feed says, and geometry derivation owns turning it into
-- linestrings. Keeping the raw points means the geometry can be rebuilt without
-- downloading the archive again.
--
-- Coordinates stay as plain doubles here. They become geography and metre-based
-- geometry when the linestrings are built, so the staging table costs nothing to
-- COPY into.

CREATE TABLE shape_points (
    shape_id            TEXT             NOT NULL,
    shape_pt_sequence   INTEGER          NOT NULL,
    feed_version_id     BIGINT           NOT NULL REFERENCES gtfs_feed_versions (id),
    lat                 DOUBLE PRECISION NOT NULL,
    lon                 DOUBLE PRECISION NOT NULL,
    shape_dist_traveled NUMERIC(12, 4),
    PRIMARY KEY (shape_id, shape_pt_sequence),
    -- Toronto, generously bounded. A coordinate outside this is a feed error, not
    -- a stop that moved.
    CONSTRAINT shape_points_lat_check CHECK (lat BETWEEN 42.5 AND 45.5),
    CONSTRAINT shape_points_lon_check CHECK (lon BETWEEN -81.5 AND -77.5)
);

COMMENT ON TABLE shape_points IS
    'Raw shapes.txt rows; aggregated into route_shapes linestrings by geometry derivation.';
