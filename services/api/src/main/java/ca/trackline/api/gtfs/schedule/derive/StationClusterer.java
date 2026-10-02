package ca.trackline.api.gtfs.schedule.derive;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Groups subway and light-rail platforms into stations.
 *
 * <p>Derived, because the TTC feed has no station hierarchy: every stop has an
 * empty {@code location_type} and {@code parent_station}. Two independent signals
 * have to agree before platforms are grouped — the station named in the stop's own
 * name, and physical proximity — because either alone gets it wrong. Name alone
 * would merge two different places that share a name; proximity alone would merge
 * a subway platform with the bus stop on the street above it.
 *
 * <p>Deliberately narrow: only stops whose name ends in "Platform" are considered.
 * 637 stop names mention a station, but most are surface stops standing near one,
 * and pulling those in would fabricate a hierarchy the agency never published.
 */
@Component
public class StationClusterer {

    private static final Logger log = LoggerFactory.getLogger(StationClusterer.class);

    /**
     * How far apart platforms of one station may be. Generous enough for a long
     * interchange with entrances on different streets, tight enough that two
     * like-named places do not merge.
     */
    static final int CLUSTER_RADIUS_METRES = 250;

    /** Recorded on every station so the UI can say how the grouping was produced. */
    static final String DERIVATION_METHOD = "PLATFORM_NAME_AND_PROXIMITY";

    private static final String SELECT_PLATFORMS = "SELECT stop_id, name FROM stops WHERE name ILIKE '%%Platform'";

    /**
     * Splits a name group whose members are too far apart to be one place.
     *
     * <p>Clustered in UTM zone 17N rather than Web Mercator: the radius is in real
     * metres, and Mercator would inflate it by about 38% at this latitude.
     */
    private static final String CLUSTER_WITHIN_NAME = """
            SELECT stop_id,
                   ST_ClusterDBSCAN(ST_Transform(location::geometry, 32617), eps := ?, minpoints := 1)
                       OVER () AS cluster_index
            FROM stops
            WHERE stop_id = ANY (?)
            """;

    private static final String INSERT_STATION = """
            INSERT INTO stations (name, centroid, stop_count, derivation_method)
            SELECT ?, ST_Centroid(ST_Collect(location::geometry))::geography, count(*), ?
            FROM stops WHERE stop_id = ANY (?)
            RETURNING id
            """;

    public int cluster(Connection connection) throws SQLException {
        Map<String, List<String>> platformsByStation = readPlatformGroups(connection);
        int stations = 0;

        for (Map.Entry<String, List<String>> group : platformsByStation.entrySet()) {
            for (List<String> stopIds : splitByProximity(connection, group.getValue())) {
                long stationId = insertStation(connection, group.getKey(), stopIds);
                attachStops(connection, stationId, stopIds);
                stations++;
            }
        }

        log.info("Derived {} stations from {} platform name groups", stations, platformsByStation.size());
        return stations;
    }

    /** Platform stops grouped by the station their name refers to. */
    private static Map<String, List<String>> readPlatformGroups(Connection connection) throws SQLException {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT_PLATFORMS)) {
            while (rows.next()) {
                String stopId = rows.getString("stop_id");
                StationNaming.stationNameFor(rows.getString("name"))
                        .ifPresent(station -> groups.computeIfAbsent(station, key -> new ArrayList<>()).add(stopId));
            }
        }
        return groups;
    }

    private static List<List<String>> splitByProximity(Connection connection, List<String> stopIds)
            throws SQLException {
        if (stopIds.size() == 1) {
            return List.of(stopIds);
        }

        Map<Integer, List<String>> clusters = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(CLUSTER_WITHIN_NAME)) {
            statement.setInt(1, CLUSTER_RADIUS_METRES);
            statement.setArray(2, connection.createArrayOf("text", stopIds.toArray()));

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    clusters.computeIfAbsent(rows.getInt("cluster_index"), key -> new ArrayList<>())
                            .add(rows.getString("stop_id"));
                }
            }
        }
        return new ArrayList<>(clusters.values());
    }

    private static long insertStation(Connection connection, String name, List<String> stopIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_STATION)) {
            statement.setString(1, name);
            statement.setString(2, DERIVATION_METHOD);
            statement.setArray(3, connection.createArrayOf("text", stopIds.toArray()));

            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Inserting station '%s' returned no id".formatted(name));
                }
                return rows.getLong(1);
            }
        }
    }

    private static void attachStops(Connection connection, long stationId, List<String> stopIds) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("UPDATE stops SET station_id = ? WHERE stop_id = ANY (?)")) {
            statement.setLong(1, stationId);
            statement.setArray(2, connection.createArrayOf("text", stopIds.toArray()));
            statement.executeUpdate();
        }
    }
}
