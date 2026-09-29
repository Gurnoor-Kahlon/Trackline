-- PostGIS: stop/station points, route linestrings, and the point-to-line
-- projection that vehicle progress along a route depends on.
CREATE EXTENSION IF NOT EXISTS postgis;

-- pg_trgm: forgiving substring search over route and stop names without a
-- third-party search service.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
