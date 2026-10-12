package ca.trackline.api.alerts;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How many alerts are open right now, or that this is not known.
 *
 * <p>Read by the network status and by the network stream on every cycle, so it is
 * held in memory and updated whenever an alert cycle is applied, instead of counted
 * per request.
 *
 * <p>Unknown is a real answer. Before any alert cycle has been applied, an empty
 * table means nothing has been ingested, not that there are no alerts, and reporting
 * zero would be a measurement that was never made. Once alerts have been stored at
 * least once the table is trustworthy across a restart, and the count is read from it.
 */
@Component
public class ActiveAlerts {

    private static final Logger log = LoggerFactory.getLogger(ActiveAlerts.class);

    /**
     * How long an answer of "not known" is trusted before the table is asked again. The
     * count is read on every network message for every connected client, so without this a
     * feed that never delivers alerts would turn each of them into a query.
     */
    private static final long UNKNOWN_RECHECK_NANOS = 10_000_000_000L;

    private final JdbcTemplate jdbc;
    private final AtomicReference<Integer> count = new AtomicReference<>();
    private volatile long nextLookupNanos = System.nanoTime();

    public ActiveAlerts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called when an alert cycle has been applied. */
    void record(int open) {
        count.set(open);
    }

    /** The number of open alerts, or null while that is not known. Never throws. */
    public Integer current() {
        Integer known = count.get();
        if (known != null) {
            return known;
        }
        if (System.nanoTime() - nextLookupNanos < 0) {
            return null;
        }
        nextLookupNanos = System.nanoTime() + UNKNOWN_RECHECK_NANOS;
        try {
            // Total as well as open: a table that has never held an alert says nothing.
            return jdbc.query(
                    "SELECT count(*) FILTER (WHERE resolved_at IS NULL) AS open, count(*) AS total FROM service_alerts",
                    rows -> {
                        rows.next();
                        if (rows.getLong("total") == 0) {
                            return null;
                        }
                        int open = rows.getInt("open");
                        count.compareAndSet(null, open);
                        return open;
                    });
        } catch (DataAccessException failure) {
            // Counting alerts must never be what breaks a status response or a stream cycle.
            log.warn("Could not count open alerts: {}", failure.toString());
            return null;
        }
    }

    /** Forgets what is held, so the next read comes from the database. For tests. */
    void forget() {
        count.set(null);
        nextLookupNanos = System.nanoTime();
    }
}
