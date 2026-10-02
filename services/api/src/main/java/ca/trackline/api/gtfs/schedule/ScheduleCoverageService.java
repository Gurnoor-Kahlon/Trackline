package ca.trackline.api.gtfs.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Whether the imported schedule actually describes a given day.
 *
 * <p>This is not a formality. The TTC publishes the next service period, so a feed
 * downloaded on 27 September described 30 September to 31 October and covered
 * nothing about that day. Anything derived from the schedule — expected headways,
 * and through them route health — is meaningless outside the window, so callers
 * check coverage and lower their confidence rather than quietly comparing live
 * service against a schedule that does not apply.
 */
@Service
public class ScheduleCoverageService {

    /** Service days are Toronto days, not UTC days. */
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private static final String ACTIVE_WINDOW = """
            SELECT id, feed_start_date, feed_end_date
            FROM gtfs_feed_versions
            WHERE status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ScheduleCoverageService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ScheduleCoverage coverageToday() {
        return coverageOn(LocalDate.now(clock.withZone(TORONTO)));
    }

    public ScheduleCoverage coverageOn(LocalDate date) {
        List<ScheduleCoverage> found = jdbc.query(ACTIVE_WINDOW, (rows, index) -> {
            java.sql.Date start = rows.getDate("feed_start_date");
            java.sql.Date end = rows.getDate("feed_end_date");
            LocalDate from = start == null ? null : start.toLocalDate();
            LocalDate to = end == null ? null : end.toLocalDate();
            boolean covered = from != null && to != null && !date.isBefore(from) && !date.isAfter(to);
            return new ScheduleCoverage(covered, rows.getLong("id"), from, to, date);
        });

        return found.isEmpty() ? ScheduleCoverage.noSchedule(date) : found.getFirst();
    }

    /**
     * @param covered       whether the active feed describes {@code date}
     * @param feedVersionId the active feed version, null when nothing is imported
     * @param feedStartDate first day the active feed describes, null when unknown
     * @param feedEndDate   last day it describes, null when unknown
     * @param date          the day asked about
     */
    public record ScheduleCoverage(
            boolean covered,
            Long feedVersionId,
            LocalDate feedStartDate,
            LocalDate feedEndDate,
            LocalDate date) {

        static ScheduleCoverage noSchedule(LocalDate date) {
            return new ScheduleCoverage(false, null, null, null, date);
        }

        /** True when no schedule has been imported at all, as opposed to one that does not reach this day. */
        public boolean scheduleMissing() {
            return feedVersionId == null;
        }
    }
}
