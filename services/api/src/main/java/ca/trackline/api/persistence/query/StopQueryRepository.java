package ca.trackline.api.persistence.query;

import java.util.List;
import java.util.Optional;

import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.routes.TransitMode;
import ca.trackline.api.stops.StopDtos.NearbyStop;
import ca.trackline.api.stops.StopDtos.StopSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read queries for stops, including the spatial ones.
 */
@Repository
public class StopQueryRepository {

    private final JdbcTemplate jdbc;

    public StopQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StopSummary> findStop(String stopId) {
        return jdbc
                .query(
                        """
                        SELECT s.stop_id, s.name, s.code,
                               ST_Y(s.location::geometry) AS lat,
                               ST_X(s.location::geometry) AS lon,
                               s.wheelchair_boarding, s.station_id, station.name AS station_name
                        FROM stops s
                        LEFT JOIN stations station ON station.id = s.station_id
                        WHERE s.stop_id = ?
                        """,
                        (rows, index) -> new StopSummary(
                                rows.getString("stop_id"),
                                rows.getString("name"),
                                rows.getString("code"),
                                rows.getDouble("lat"),
                                rows.getDouble("lon"),
                                rows.getShort("wheelchair_boarding"),
                                rows.getObject("station_id", Long.class),
                                rows.getString("station_name")),
                        stopId)
                .stream()
                .findFirst();
    }

    /** The stops with these ids. Unknown ids are absent. */
    public List<StopSummary> findStopsByIds(java.util.Collection<String> stopIds) {
        if (stopIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", stopIds.stream().map(id -> "?").toList());
        return jdbc.query(
                """
                SELECT s.stop_id, s.name, s.code,
                       ST_Y(s.location::geometry) AS lat,
                       ST_X(s.location::geometry) AS lon,
                       s.wheelchair_boarding, s.station_id, station.name AS station_name
                FROM stops s
                LEFT JOIN stations station ON station.id = s.station_id
                WHERE s.stop_id IN (%s)
                """.formatted(placeholders),
                (rows, index) -> new StopSummary(
                        rows.getString("stop_id"),
                        rows.getString("name"),
                        rows.getString("code"),
                        rows.getDouble("lat"),
                        rows.getDouble("lon"),
                        rows.getShort("wheelchair_boarding"),
                        rows.getObject("station_id", Long.class),
                        rows.getString("station_name")),
                stopIds.toArray());
    }

    /** The routes whose scheduled trips call at a stop. */
    public List<RouteSummary> findRoutesServing(String stopId) {
        return jdbc.query(
                """
                SELECT r.route_id, r.short_name, r.long_name, r.route_type,
                       r.color, r.text_color, r.supports_realtime
                FROM routes r
                WHERE EXISTS (
                    SELECT 1 FROM stop_times st
                    JOIN trips t ON t.trip_id = st.trip_id
                    WHERE st.stop_id = ? AND t.route_id = r.route_id)
                ORDER BY CASE WHEN r.short_name ~ '^[0-9]+$' THEN 0 ELSE 1 END,
                         CASE WHEN r.short_name ~ '^[0-9]+$' THEN r.short_name::int END,
                         r.short_name
                """,
                (rows, index) -> new RouteSummary(
                        rows.getString("route_id"),
                        rows.getString("short_name"),
                        rows.getString("long_name"),
                        TransitMode.fromRouteType(rows.getInt("route_type")),
                        rows.getString("color"),
                        rows.getString("text_color"),
                        rows.getBoolean("supports_realtime")),
                stopId);
    }

    /**
     * Stops within a radius, nearest first.
     *
     * <p>{@code ST_DWithin} on the geography column uses the GiST index to reject
     * everything outside the radius before any distance is computed, and the
     * distance that comes back is metres on the spheroid rather than degrees. The
     * {@code <->} ordering lets the same index drive the sort.
     *
     * @param radiusMetres search radius; the caller is responsible for capping it
     */
    public List<NearbyStop> findNearby(double latitude, double longitude, int radiusMetres, int limit) {
        return jdbc.query(
                """
                WITH origin AS (SELECT ST_MakePoint(?, ?)::geography AS point),
                within_radius AS (
                    SELECT s.stop_id, s.name, s.code,
                           ST_Y(s.location::geometry) AS lat,
                           ST_X(s.location::geometry) AS lon,
                           s.wheelchair_boarding, s.station_id,
                           ST_Distance(s.location, origin.point) AS distance_m
                    FROM stops s
                    CROSS JOIN origin
                    -- ST_DWithin uses the GiST index to discard everything outside
                    -- the radius before any distance is computed.
                    WHERE ST_DWithin(s.location, origin.point, ?)
                )
                SELECT within_radius.*, station.name AS station_name
                FROM within_radius
                LEFT JOIN stations station ON station.id = within_radius.station_id
                -- Ordered by the same distance the response reports. The KNN <->
                -- operator measures on a sphere while ST_Distance measures on the
                -- spheroid, so sorting by one and reporting the other produces a
                -- list that is visibly out of order.
                ORDER BY distance_m
                LIMIT ?
                """,
                (rows, index) -> new NearbyStop(
                        new StopSummary(
                                rows.getString("stop_id"),
                                rows.getString("name"),
                                rows.getString("code"),
                                rows.getDouble("lat"),
                                rows.getDouble("lon"),
                                rows.getShort("wheelchair_boarding"),
                                rows.getObject("station_id", Long.class),
                                rows.getString("station_name")),
                        Math.round(rows.getDouble("distance_m") * 10) / 10.0),
                longitude, latitude, radiusMetres, limit);
    }
}
