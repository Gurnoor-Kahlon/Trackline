package ca.trackline.api.persistence.query;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.livestate.LiveVehicles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reconstructs the live picture from stored observations.
 *
 * <p>The last resort in the degradation chain, used only when neither cache can
 * answer. It is a real query over a partitioned table of millions of rows, which is
 * precisely why it is not the normal path — but it is what keeps the map drawing
 * when Redis is down.
 */
@Repository
public class VehicleObservationQueryRepository {

    /**
     * How far back to look for a vehicle's last known position.
     *
     * <p>Beyond this a vehicle has stopped reporting and showing it would place a
     * bus somewhere it left long ago. Chosen to span a gap in ingestion without
     * resurrecting vehicles that have genuinely finished for the day.
     */
    private static final Duration LOOKBACK = Duration.ofMinutes(15);

    /**
     * One row per vehicle, its most recent reading.
     *
     * <p>{@code DISTINCT ON} rather than a window function or a correlated subquery:
     * Postgres can satisfy it straight from the (vehicle_id, source_timestamp DESC)
     * index without sorting the whole candidate set.
     */
    private static final String LATEST_PER_VEHICLE = """
            SELECT DISTINCT ON (vehicle_id)
                   vehicle_id, route_id, source_trip_id,
                   ST_Y(position::geometry) AS latitude,
                   ST_X(position::geometry) AS longitude,
                   bearing, source_speed, current_status, current_stop_sequence,
                   stop_id, occupancy_status, source_timestamp
            FROM vehicle_observations
            WHERE source_timestamp >= ?
            ORDER BY vehicle_id, source_timestamp DESC
            """;

    private final JdbcTemplate jdbc;

    public VehicleObservationQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the most recent reading for every vehicle seen recently, or empty when
     *         nothing has been ingested at all
     */
    public Optional<LiveVehicles> latestVehicles() {
        Instant since = Instant.now().minus(LOOKBACK);
        List<VehicleSnapshot> vehicles =
                jdbc.query(LATEST_PER_VEHICLE, (rows, index) -> toSnapshot(rows), Timestamp.from(since));

        if (vehicles.isEmpty()) {
            return Optional.empty();
        }

        // The newest reading in the set is the closest thing to a feed timestamp the
        // database can offer: there is no record of when the publisher produced it,
        // only of when vehicles reported.
        Instant newest = vehicles.stream()
                .map(VehicleSnapshot::sourceTimestamp)
                .max(Instant::compareTo)
                .orElse(null);

        return Optional.of(LiveVehicles.fromDatabase(vehicles, newest));
    }

    private static VehicleSnapshot toSnapshot(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new VehicleSnapshot(
                rows.getString("vehicle_id"),
                rows.getString("route_id"),
                rows.getString("source_trip_id"),
                rows.getDouble("latitude"),
                rows.getDouble("longitude"),
                nullableFloat(rows, "bearing"),
                nullableFloat(rows, "source_speed"),
                nullableShort(rows, "current_status"),
                nullableInt(rows, "current_stop_sequence"),
                rows.getString("stop_id"),
                nullableShort(rows, "occupancy_status"),
                rows.getTimestamp("source_timestamp").toInstant());
    }

    private static Float nullableFloat(java.sql.ResultSet rows, String column) throws java.sql.SQLException {
        float value = rows.getFloat(column);
        return rows.wasNull() ? null : value;
    }

    private static Short nullableShort(java.sql.ResultSet rows, String column) throws java.sql.SQLException {
        short value = rows.getShort(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rows, String column) throws java.sql.SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}
