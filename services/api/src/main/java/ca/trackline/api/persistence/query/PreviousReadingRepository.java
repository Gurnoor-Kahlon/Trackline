package ca.trackline.api.persistence.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Finds each vehicle's most recent stored reading before the one being placed.
 *
 * <p>Movement between two readings is the second way to tell which direction a
 * vehicle is running, so the earlier reading is needed for exactly the vehicles
 * whose heading did not decide. It is fetched for all vehicles at once, in one
 * query: asking per vehicle after the fact would be a round trip for each of a
 * thousand vehicles every ten seconds, for the sake of the minority that need it.
 *
 * <p>Read from the database rather than held in memory, so a restart loses nothing
 * and the readings used are the ones the history will actually contain.
 */
@Repository
public class PreviousReadingRepository {

    /**
     * The timestamp window is spelled out as plain bounds as well as being joined,
     * because only a constant range lets the planner skip whole daily partitions;
     * the join condition alone would probe every partition for every vehicle.
     */
    private static final String LATEST_BEFORE = """
            SELECT DISTINCT ON (o.vehicle_id)
                   o.vehicle_id, o.route_id, o.source_timestamp,
                   ST_Y(o.position::geometry) AS latitude,
                   ST_X(o.position::geometry) AS longitude
            FROM vehicle_observations o
            JOIN unnest(?::text[], ?::timestamptz[]) AS c(vehicle_id, reading_timestamp)
                 ON o.vehicle_id = c.vehicle_id
            WHERE o.source_timestamp >= ?
              AND o.source_timestamp <  ?
              AND o.source_timestamp <  c.reading_timestamp
              AND o.source_timestamp >= c.reading_timestamp - (? * interval '1 second')
            ORDER BY o.vehicle_id, o.source_timestamp DESC
            """;

    private final JdbcTemplate jdbc;

    public PreviousReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param vehicles the readings about to be placed
     * @param maxAge   how far back an earlier reading may be and still count
     * @return the earlier reading per vehicle id; vehicles with none are absent
     */
    public Map<String, PreviousReading> latestBefore(List<VehicleSnapshot> vehicles, Duration maxAge) {
        if (vehicles.isEmpty()) {
            return Map.of();
        }

        Instant earliest = vehicles.stream().map(VehicleSnapshot::sourceTimestamp)
                .min(Instant::compareTo).orElseThrow();
        Instant latest = vehicles.stream().map(VehicleSnapshot::sourceTimestamp)
                .max(Instant::compareTo).orElseThrow();

        Map<String, PreviousReading> found = new HashMap<>();
        jdbc.query(
                connection -> prepare(connection, vehicles, earliest.minus(maxAge), latest, maxAge),
                rows -> {
                    found.put(
                            rows.getString("vehicle_id"),
                            new PreviousReading(
                                    rows.getString("route_id"),
                                    rows.getDouble("latitude"),
                                    rows.getDouble("longitude"),
                                    rows.getTimestamp("source_timestamp").toInstant()));
                });
        return found;
    }

    private static PreparedStatement prepare(
            Connection connection, List<VehicleSnapshot> vehicles, Instant from, Instant to, Duration maxAge)
            throws SQLException {
        String[] ids = new String[vehicles.size()];
        Timestamp[] timestamps = new Timestamp[vehicles.size()];
        for (int index = 0; index < vehicles.size(); index++) {
            ids[index] = vehicles.get(index).vehicleId();
            timestamps[index] = Timestamp.from(vehicles.get(index).sourceTimestamp());
        }

        PreparedStatement statement = connection.prepareStatement(LATEST_BEFORE);
        statement.setArray(1, connection.createArrayOf("text", ids));
        statement.setArray(2, connection.createArrayOf("timestamptz", timestamps));
        statement.setTimestamp(3, Timestamp.from(from));
        statement.setTimestamp(4, Timestamp.from(to));
        statement.setDouble(5, maxAge.toMillis() / 1000.0);
        return statement;
    }

    /**
     * @param routeId   the route it was working then, which may differ from now
     * @param latitude  where it was
     * @param longitude where it was
     * @param timestamp when it reported
     */
    public record PreviousReading(String routeId, double latitude, double longitude, Instant timestamp) {
    }
}
