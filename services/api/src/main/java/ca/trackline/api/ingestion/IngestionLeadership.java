package ca.trackline.api.ingestion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which API instance polls the feeds.
 *
 * <p>The guard inside {@link IngestionCycleService} stops one process running two cycles of a feed at once. It cannot stop two
 * processes each running one, and two would poll the TTC twice, store every reading twice (the unique key on a stored position
 * turns the second write into wasted work, not wrong data) and race each other's analysis. This holds a PostgreSQL session-level
 * advisory lock for as long as the instance is the one polling: whoever holds it ingests, and everyone else stands by and tries
 * again at its next poll.
 *
 * <p>The lock belongs to a database session, so it is released by the one thing that must release it and by nothing else: the
 * holder going away. A crashed instance, a killed container and a lost network all end the session, and the database drops the
 * lock without anyone having to notice. There is no timeout to tune and no lease to renew, and so no window in which a lease has
 * expired under a holder that is still running.
 *
 * <p>The session is one connection taken from the pool and kept for the life of the instance, so the pool is one smaller than its
 * configured size. Each poll asks {@link #tryHold()}: a holder checks that its session still answers (and treats failure as having
 * lost the lock), and a standby tries to take it. A standby therefore takes over within one poll interval of the holder going.
 *
 * <p>What this does not do is make the API scale out. A standby serves reads from Redis, which is shared, but the live stream is fed
 * by the cycle that ingests, so a stream connected to a standby stays quiet until that instance takes over. The lock is what makes
 * it safe to run two instances briefly (a rolling deployment) or to leave a spare ready, not what makes two of them useful at once.
 */
@Component
public class IngestionLeadership {

    private static final Logger log = LoggerFactory.getLogger(IngestionLeadership.class);

    /** "TRKLINE" as bytes, so the key is recognisable in pg_locks and unlikely to be taken by anything else in the database. */
    static final long LOCK_KEY = 0x54524B4C494E45L;

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;
    private final boolean enabled;

    private Connection session;

    public IngestionLeadership(
            DataSource dataSource, @Value("${trackline.ingestion.leader-election.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    /**
     * Whether this instance is the one that should poll now, taking the lock if it is free.
     *
     * <p>Never throws: a database that cannot be reached means this instance cannot hold the lock, and the poll that would have
     * followed would have failed on the same database.
     */
    public synchronized boolean tryHold() {
        if (!enabled) {
            return true;
        }
        try {
            if (session != null) {
                if (stillAnswers()) {
                    return true;
                }
                log.warn("Lost the ingestion lock: its database session no longer answers");
                dropSession();
            }
            return tryTake();
        } catch (SQLException | RuntimeException failure) {
            log.warn("Could not check the ingestion lock: {}", failure.toString());
            dropSession();
            return false;
        }
    }

    /** Whether this instance holds the lock, without trying to take it. For tests and diagnostics. */
    public synchronized boolean holds() {
        return !enabled || session != null;
    }

    private boolean tryTake() throws SQLException {
        Connection candidate = dataSource.getConnection();
        boolean kept = false;
        try (PreparedStatement statement = candidate.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    session = candidate;
                    kept = true;
                    log.info("This instance now holds the ingestion lock and polls the feeds");
                    return true;
                }
            }
            return false;
        } finally {
            if (!kept) {
                closeQuietly(candidate);
            }
        }
    }

    private boolean stillAnswers() {
        try (PreparedStatement statement = session.prepareStatement("select 1")) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            return false;
        }
    }

    /**
     * Gives the session back, unlocking first. The connection comes from a pool, so closing it returns it to the pool and does not
     * end the database session, and a lock left on a pooled session would be held by whichever request borrowed it next. If the
     * session is broken the unlock fails and the session has ended anyway, which releases the lock just as well.
     */
    private void dropSession() {
        if (session != null) {
            try (PreparedStatement statement = session.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            } catch (SQLException ignored) {
                // Broken: the database has already dropped the lock with the session.
            }
            closeQuietly(session);
            session = null;
        }
    }

    /** Called when the application stops, so a standby can take over at its next poll and not after a timeout. */
    @PreDestroy
    synchronized void release() {
        if (session != null) {
            log.info("Releasing the ingestion lock");
            dropSession();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing left to do for a connection that will not close.
        }
    }
}
