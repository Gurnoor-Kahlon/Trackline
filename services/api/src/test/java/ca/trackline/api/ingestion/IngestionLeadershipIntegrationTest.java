package ca.trackline.api.ingestion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Who polls the feeds, decided by a real PostgreSQL advisory lock.
 *
 * <p>The cases that matter are the ones a mock cannot show: that the database really refuses a second holder, really lets the
 * next one in when the first goes, and, the one that is easy to get wrong with a connection pool, that giving the lock up
 * leaves nothing behind on a connection that goes back into the pool still locked.
 */
class IngestionLeadershipIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<IngestionLeadership> instances = new ArrayList<>();

    private IngestionLeadership instance() {
        IngestionLeadership leadership = new IngestionLeadership(dataSource, true);
        instances.add(leadership);
        return leadership;
    }

    @AfterEach
    void releaseEverything() {
        instances.forEach(IngestionLeadership::release);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(locksHeld()).isZero());
    }

    /** How many sessions hold the ingestion lock, as the database itself reports it. */
    private long locksHeld() {
        Long held = jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND ((classid::bigint << 32) | objid::bigint) = ?",
                Long.class,
                IngestionLeadership.LOCK_KEY);
        return held == null ? 0 : held;
    }

    @Test
    void theFirstInstanceTakesTheLockAndTheSecondStandsBy() {
        IngestionLeadership first = instance();
        IngestionLeadership second = instance();

        assertThat(first.tryHold()).isTrue();
        assertThat(second.tryHold()).isFalse();

        assertThat(locksHeld()).isEqualTo(1);
        assertThat(first.holds()).isTrue();
        assertThat(second.holds()).isFalse();
    }

    @Test
    void aHolderKeepsItAcrossPollsWithoutTakingAnotherSession() {
        IngestionLeadership first = instance();

        for (int poll = 0; poll < 5; poll++) {
            assertThat(first.tryHold()).isTrue();
        }

        assertThat(locksHeld()).isEqualTo(1);
    }

    @Test
    void aStandbyTakesOverWhenTheHolderReleases() {
        IngestionLeadership first = instance();
        IngestionLeadership second = instance();
        first.tryHold();
        assertThat(second.tryHold()).isFalse();

        first.release();

        assertThat(second.tryHold()).isTrue();
        assertThat(locksHeld()).isEqualTo(1);
    }

    @Test
    void releasingLeavesNoLockOnAConnectionThatGoesBackToThePool() {
        IngestionLeadership first = instance();
        first.tryHold();

        first.release();

        // The connection that held it is alive in the pool. If the lock had been left on it, the database would still report it.
        assertThat(locksHeld()).isZero();
        assertThat(first.holds()).isFalse();
    }

    @Test
    void aStandbyTakesOverWhenTheHoldersDatabaseSessionIsKilled() {
        IngestionLeadership first = instance();
        IngestionLeadership second = instance();
        first.tryHold();
        Integer holderPid = jdbc.queryForObject(
                "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND ((classid::bigint << 32) | objid::bigint) = ?",
                Integer.class,
                IngestionLeadership.LOCK_KEY);

        // A crashed instance, a killed container and a lost network all end the session like this.
        jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, holderPid);

        await().atMost(Duration.ofSeconds(10)).until(second::tryHold);
        assertThat(locksHeld()).isEqualTo(1);
        // And the instance that lost it notices, and does not go on believing it holds it.
        assertThat(first.tryHold()).isFalse();
        assertThat(first.holds()).isFalse();
    }

    @Test
    void whenLeaderElectionIsOffEveryInstancePollsAndNoLockIsTaken() {
        IngestionLeadership off = new IngestionLeadership(dataSource, false);
        IngestionLeadership alsoOff = new IngestionLeadership(dataSource, false);

        assertThat(off.tryHold()).isTrue();
        assertThat(alsoOff.tryHold()).isTrue();

        assertThat(locksHeld()).isZero();
    }
}
