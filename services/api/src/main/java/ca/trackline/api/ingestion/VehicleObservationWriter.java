package ca.trackline.api.ingestion;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores vehicle positions, skipping readings that say nothing new.
 *
 * <p>Unfiltered, this table would grow by about 1,300 rows every ten seconds —
 * eleven million a day — and most of those rows would record a vehicle sitting at
 * a terminal. Three filters cut that down without losing the shape of the data:
 * a reading whose timestamp has not advanced is the same reading; a vehicle that
 * has not moved far is only recorded periodically so it is still known to be
 * alive; and the unique key catches anything the in-memory state misses after a
 * restart.
 */
@Component
public class VehicleObservationWriter {

    private static final Logger log = LoggerFactory.getLogger(VehicleObservationWriter.class);

    private static final String INSERT = """
            INSERT INTO vehicle_observations (
                vehicle_id, source_timestamp, observed_at, route_id, source_trip_id,
                position, bearing, source_speed, current_status, current_stop_sequence,
                stop_id, occupancy_status, ingestion_run_id)
            VALUES (?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (vehicle_id, source_timestamp) DO NOTHING
            """;

    /**
     * Last stored reading per vehicle. In-process and deliberately not durable:
     * losing it on restart costs one extra row per vehicle, which the unique key
     * then discards anyway.
     */
    private final Map<String, StoredReading> lastStored = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final RealtimeProperties properties;

    public VehicleObservationWriter(JdbcTemplate jdbc, RealtimeProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * @return how many observations were stored
     */
    public int write(List<VehicleSnapshot> vehicles, long ingestionRunId, Instant observedAt) {
        List<VehicleSnapshot> worthStoring = new ArrayList<>();
        for (VehicleSnapshot vehicle : vehicles) {
            if (isWorthStoring(vehicle)) {
                worthStoring.add(vehicle);
            }
        }
        if (worthStoring.isEmpty()) {
            return 0;
        }

        int[] results = jdbc.batchUpdate(INSERT, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                bind(statement, worthStoring.get(index), ingestionRunId, observedAt);
            }

            @Override
            public int getBatchSize() {
                return worthStoring.size();
            }
        });

        int stored = 0;
        for (int index = 0; index < results.length; index++) {
            // A zero means the unique key rejected it, so it is not remembered as
            // stored and the next cycle will try again on its own merits.
            if (results[index] != 0) {
                stored++;
                remember(worthStoring.get(index));
            }
        }
        return stored;
    }

    private boolean isWorthStoring(VehicleSnapshot vehicle) {
        StoredReading previous = lastStored.get(vehicle.vehicleId());
        if (previous == null) {
            return true;
        }
        if (!vehicle.sourceTimestamp().isAfter(previous.sourceTimestamp())) {
            // The vehicle has not reported since last time; this is the same reading
            // arriving again, not a new one.
            return false;
        }

        Duration sinceStored = Duration.between(previous.sourceTimestamp(), vehicle.sourceTimestamp());
        if (sinceStored.compareTo(properties.minObservationGap()) >= 0) {
            return true;
        }
        return metresBetween(previous.latitude(), previous.longitude(), vehicle.latitude(), vehicle.longitude())
                >= properties.minObservationMetres();
    }

    private void remember(VehicleSnapshot vehicle) {
        lastStored.put(
                vehicle.vehicleId(),
                new StoredReading(vehicle.sourceTimestamp(), vehicle.latitude(), vehicle.longitude()));
    }

    private static void bind(PreparedStatement statement, VehicleSnapshot vehicle, long runId, Instant observedAt)
            throws java.sql.SQLException {
        statement.setString(1, vehicle.vehicleId());
        statement.setTimestamp(2, Timestamp.from(vehicle.sourceTimestamp()));
        statement.setTimestamp(3, Timestamp.from(observedAt));
        setNullable(statement, 4, vehicle.routeId());
        setNullable(statement, 5, vehicle.sourceTripId());
        statement.setDouble(6, vehicle.longitude());
        statement.setDouble(7, vehicle.latitude());
        setNullableFloat(statement, 8, vehicle.bearing());
        setNullableFloat(statement, 9, vehicle.speed());
        setNullableShort(statement, 10, vehicle.currentStatus());
        setNullableInt(statement, 11, vehicle.currentStopSequence());
        setNullable(statement, 12, vehicle.stopId());
        setNullableShort(statement, 13, vehicle.occupancyStatus());
        statement.setLong(14, runId);
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableFloat(PreparedStatement statement, int index, Float value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.REAL);
        } else {
            statement.setFloat(index, value);
        }
    }

    private static void setNullableShort(PreparedStatement statement, int index, Short value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.SMALLINT);
        } else {
            statement.setShort(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    /**
     * Equirectangular approximation, which is accurate to well under a metre at the
     * distances this is used for and avoids a database round trip per vehicle.
     */
    static double metresBetween(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {
        double metresPerDegreeLatitude = 111_320;
        double averageLatitude = Math.toRadians((fromLatitude + toLatitude) / 2);
        double northing = (toLatitude - fromLatitude) * metresPerDegreeLatitude;
        double easting = (toLongitude - fromLongitude) * metresPerDegreeLatitude * Math.cos(averageLatitude);
        return Math.hypot(northing, easting);
    }

    /** Clears remembered state. Used by tests that need a writer with no history. */
    void forgetAll() {
        lastStored.clear();
    }

    /**
     * Ensures a partition exists for every day the given instant could land in.
     *
     * <p>Called before writing: an insert into a range-partitioned table with no
     * matching partition fails outright, and that must never be the reason a cycle
     * is lost.
     */
    public void ensurePartitionFor(Instant timestamp) {
        LocalDate day = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate target = day.minusDays(1); !target.isAfter(day.plusDays(1)); target = target.plusDays(1)) {
            createPartition(target);
        }
    }

    private void createPartition(LocalDate day) {
        String name = "vehicle_observations_%s".formatted(day.toString().replace('-', '_'));
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS %s PARTITION OF vehicle_observations
                    FOR VALUES FROM ('%s') TO ('%s')
                    """.formatted(name, day, day.plusDays(1)));
        } catch (RuntimeException failure) {
            // A concurrent creation is fine; anything else is worth knowing about but
            // must not stop a cycle that may still have a usable partition.
            log.warn("Could not ensure partition {}: {}", name, failure.getMessage());
        }
    }

    private record StoredReading(Instant sourceTimestamp, double latitude, double longitude) {
    }
}
