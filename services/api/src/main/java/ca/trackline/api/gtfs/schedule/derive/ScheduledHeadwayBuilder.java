package ca.trackline.api.gtfs.schedule.derive;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Precomputes the median scheduled gap between departures, per route, direction,
 * day type and hour.
 *
 * <p>Route health compares observed vehicle spacing against what the schedule
 * intends, and that comparison has to be cheap. Rather than reason about every
 * service pattern, one representative date is chosen per day type and the
 * schedule is read as it would run on that date. This avoids the trap of counting
 * a trip once per day it operates, which would inflate frequency several-fold.
 *
 * <p>The TTC's calendar makes this necessary rather than merely convenient: of ten
 * services, seven carry no weekday flags at all and run purely through dated
 * exceptions, including one with 32,379 trips on a single holiday.
 */
@Component
public class ScheduledHeadwayBuilder {

    private static final Logger log = LoggerFactory.getLogger(ScheduledHeadwayBuilder.class);

    /** The kinds of service day Trackline distinguishes. */
    public enum DayType {
        WEEKDAY,
        SATURDAY,
        SUNDAY
    }

    /**
     * Services running on a date: those whose weekly pattern covers it and which
     * are not removed by an exception, plus those added by one.
     */
    private static final String SERVICES_ON_DATE = """
            SELECT service_id FROM calendar_services
            WHERE ? BETWEEN start_date AND end_date
              AND CASE ?
                  WHEN 1 THEN monday WHEN 2 THEN tuesday WHEN 3 THEN wednesday
                  WHEN 4 THEN thursday WHEN 5 THEN friday WHEN 6 THEN saturday
                  ELSE sunday END
              AND NOT EXISTS (
                  SELECT 1 FROM calendar_exceptions e
                  WHERE e.service_id = calendar_services.service_id
                    AND e.exception_date = ? AND e.exception_type = 2)
            UNION
            SELECT service_id FROM calendar_exceptions
            WHERE exception_date = ? AND exception_type = 1
            """;

    /**
     * Every trip's first departure on the chosen services, with the gap to the next
     * departure on the same route and direction.
     *
     * <p>A gap is attributed to the hour the earlier departure falls in, so a gap
     * spanning an hour boundary counts once, against the hour it started.
     */
    private static final String DEPARTURE_GAPS = """
            WITH first_call AS (
                SELECT t.route_id, t.direction_id, st.departure_seconds
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id AND st.stop_sequence = 1
                WHERE t.service_id = ANY (?)
            )
            SELECT route_id, direction_id, departure_seconds,
                   lead(departure_seconds) OVER (
                       PARTITION BY route_id, direction_id
                       ORDER BY departure_seconds) - departure_seconds AS gap_seconds
            FROM first_call
            """;

    private static final String INSERT_HEADWAY = """
            INSERT INTO route_scheduled_headways (
                route_id, direction_id, day_type, hour_of_day,
                median_headway_seconds, gap_count, departure_count, feed_version_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public int build(Connection connection, long feedVersionId, LocalDate feedStart, LocalDate feedEnd)
            throws SQLException {
        if (feedStart == null || feedEnd == null) {
            log.warn("Feed has no service window; scheduled headways cannot be derived");
            return 0;
        }

        int written = 0;
        for (DayType dayType : DayType.values()) {
            Optional<LocalDate> representative = representativeDate(dayType, feedStart, feedEnd);
            if (representative.isEmpty()) {
                log.warn("Feed window {}..{} contains no {} to sample", feedStart, feedEnd, dayType);
                continue;
            }
            written += buildForDayType(connection, feedVersionId, dayType, representative.get());
        }

        log.info("Derived {} scheduled headway rows", written);
        return written;
    }

    /**
     * The date the schedule is read from for a day type.
     *
     * <p>Wednesday represents the weekday: Monday and Friday attract holiday
     * closures and seasonal variation, and a statutory holiday falling on a Monday
     * would otherwise be taken as a typical weekday.
     */
    static Optional<LocalDate> representativeDate(DayType dayType, LocalDate feedStart, LocalDate feedEnd) {
        DayOfWeek wanted = switch (dayType) {
            case WEEKDAY -> DayOfWeek.WEDNESDAY;
            case SATURDAY -> DayOfWeek.SATURDAY;
            case SUNDAY -> DayOfWeek.SUNDAY;
        };

        for (LocalDate date = feedStart; !date.isAfter(feedEnd); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == wanted) {
                return Optional.of(date);
            }
        }
        return Optional.empty();
    }

    private int buildForDayType(Connection connection, long feedVersionId, DayType dayType, LocalDate date)
            throws SQLException {
        List<String> services = servicesOn(connection, date);
        if (services.isEmpty()) {
            log.warn("No services run on {} ({}), so no headways were derived", date, dayType);
            return 0;
        }

        Map<HeadwayKey, List<Integer>> gaps = new LinkedHashMap<>();
        Map<HeadwayKey, Integer> departures = new LinkedHashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(DEPARTURE_GAPS)) {
            statement.setArray(1, connection.createArrayOf("text", services.toArray()));

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    HeadwayKey key = new HeadwayKey(
                            rows.getString("route_id"),
                            rows.getShort("direction_id"),
                            Headways.clockHour(rows.getInt("departure_seconds")));
                    departures.merge(key, 1, Integer::sum);

                    int gap = rows.getInt("gap_seconds");
                    // The last departure of the day has no following one, and two
                    // trips leaving together describe branches rather than a
                    // headway a rider would experience.
                    if (!rows.wasNull() && gap > 0) {
                        gaps.computeIfAbsent(key, ignored -> new ArrayList<>()).add(gap);
                    }
                }
            }
        }

        return insert(connection, feedVersionId, dayType, gaps, departures);
    }

    private static int insert(
            Connection connection,
            long feedVersionId,
            DayType dayType,
            Map<HeadwayKey, List<Integer>> gaps,
            Map<HeadwayKey, Integer> departures) throws SQLException {

        int written = 0;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_HEADWAY)) {
            for (Map.Entry<HeadwayKey, List<Integer>> entry : gaps.entrySet()) {
                HeadwayKey key = entry.getKey();
                int departureCount = departures.getOrDefault(key, 0);
                if (departureCount < 2) {
                    // An hour with one departure has no headway to report. Saying
                    // nothing is more useful than implying a measurement.
                    continue;
                }

                statement.setString(1, key.routeId());
                statement.setShort(2, key.directionId());
                statement.setString(3, dayType.name());
                statement.setShort(4, (short) key.hourOfDay());
                statement.setInt(5, Headways.medianSeconds(entry.getValue()));
                statement.setInt(6, entry.getValue().size());
                statement.setInt(7, departureCount);
                statement.setLong(8, feedVersionId);
                statement.addBatch();
                written++;
            }
            statement.executeBatch();
        }
        return written;
    }

    private static List<String> servicesOn(Connection connection, LocalDate date) throws SQLException {
        List<String> services = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SERVICES_ON_DATE)) {
            Date sqlDate = Date.valueOf(date);
            statement.setDate(1, sqlDate);
            statement.setInt(2, date.getDayOfWeek().getValue());
            statement.setDate(3, sqlDate);
            statement.setDate(4, sqlDate);

            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    services.add(rows.getString(1));
                }
            }
        }
        return services;
    }

    private record HeadwayKey(String routeId, short directionId, int hourOfDay) {
    }
}
