package ca.trackline.api.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import ca.trackline.api.alerts.AlertLifecycleService.Outcome;
import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;
import ca.trackline.api.gtfs.realtime.FeedEntityValidator;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.RealtimeFeedParser;
import ca.trackline.api.testsupport.FeedFixtures;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The alert lifecycle against a real database.
 *
 * <p>The reconciler's decisions are tested without one; these check that they are carried
 * out correctly: that times are what the cycle saw, that an edit replaces the entities,
 * that a failure halfway leaves nothing behind, and that the database itself refuses what
 * the application must never do.
 */
class AlertLifecycleIntegrationTest extends PostgisIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private AlertLifecycleService lifecycle;

    @Autowired
    private ActiveAlerts activeAlerts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE service_alerts CASCADE");
        activeAlerts.forget();
    }

    private static AlertSnapshot alert(String id, String description, AffectedEntity... affected) {
        return new AlertSnapshot(id, 1, 8, "header", description, null, List.of(affected));
    }

    private static AffectedEntity route(String id) {
        return new AffectedEntity(id, null);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM service_alerts", Integer.class);
    }

    private int openRows() {
        return jdbc.queryForObject("SELECT count(*) FROM service_alerts WHERE resolved_at IS NULL", Integer.class);
    }

    private Instant instant(String column, String source) {
        Timestamp value = jdbc.queryForObject(
                "SELECT " + column + " FROM service_alerts WHERE source_alert_id = ? ORDER BY id DESC LIMIT 1",
                Timestamp.class, source);
        return value == null ? null : value.toInstant();
    }

    @Test
    void opensEveryAlertOfARecordedPayloadWithWhatItNames() {
        List<AlertSnapshot> recorded = new RealtimeFeedParser()
                .parse(RealtimeFeed.ALERTS, FeedFixtures.bytes("alerts.pb"), new FeedEntityValidator(Set.of()), T0)
                .alerts();

        Outcome outcome = lifecycle.apply(recorded, T0);

        assertThat(outcome.opened()).isEqualTo(recorded.size());
        assertThat(rows()).isEqualTo(recorded.size());
        assertThat(openRows()).isEqualTo(recorded.size());
        // Stored once per distinct pair, though the feed repeats them.
        int distinct = (int) recorded.stream()
                .flatMap(alert -> alert.affected().stream().distinct().map(entity -> alert.alertId() + entity))
                .count();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_alert_entities", Integer.class))
                .isEqualTo(distinct);
        assertThat(instant("first_seen_at", recorded.get(0).alertId())).isEqualTo(T0);
        assertThat(instant("last_seen_at", recorded.get(0).alertId())).isEqualTo(T0);
        assertThat(instant("resolved_at", recorded.get(0).alertId())).isNull();
    }

    @Test
    void aLaterCycleThatSeesTheSameAlertAdvancesOnlyWhenItWasLastSeen() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);

        Outcome outcome = lifecycle.apply(List.of(alert("a", "text", route("501"))), T0.plusSeconds(60));

        assertThat(outcome.confirmed()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(instant("first_seen_at", "a")).isEqualTo(T0);
        assertThat(instant("last_seen_at", "a")).isEqualTo(T0.plusSeconds(60));
        assertThat(instant("last_changed_at", "a")).isEqualTo(T0);
    }

    @Test
    void anEditedAlertKeepsItsStretchAndItsFirstSightingAndReplacesWhatItNames() {
        lifecycle.apply(List.of(alert("a", "old text", route("501"), route("504"))), T0);

        Outcome outcome = lifecycle.apply(List.of(alert("a", "new text", route("505"))), T0.plusSeconds(120));

        assertThat(outcome.changed()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM service_alerts", String.class)).isEqualTo("new text");
        assertThat(instant("first_seen_at", "a")).isEqualTo(T0);
        assertThat(instant("last_changed_at", "a")).isEqualTo(T0.plusSeconds(120));
        assertThat(jdbc.queryForList("SELECT route_id FROM service_alert_entities", String.class))
                .containsExactly("505");
    }

    @Test
    void anAlertThatLeavesTheFeedIsResolvedAtTheCycleThatMissedIt() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);

        Outcome outcome = lifecycle.apply(List.of(), T0.plusSeconds(60));

        assertThat(outcome.resolved()).isEqualTo(1);
        assertThat(instant("resolved_at", "a")).isEqualTo(T0.plusSeconds(60));
        // Last seen is when it was last in the feed, not when it was found missing.
        assertThat(instant("last_seen_at", "a")).isEqualTo(T0);
        assertThat(openRows()).isZero();
    }

    @Test
    void aResolvedAlertIsKeptNotDeleted() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);
        lifecycle.apply(List.of(), T0.plusSeconds(60));
        lifecycle.apply(List.of(), T0.plusSeconds(120));

        assertThat(rows()).isEqualTo(1);
        assertThat(instant("resolved_at", "a")).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void anAlertThatComesBackIsANewStretchAndTheOldOneIsLeftAlone() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);
        lifecycle.apply(List.of(), T0.plusSeconds(60));

        Outcome outcome = lifecycle.apply(List.of(alert("a", "text", route("501"))), T0.plusSeconds(3600));

        assertThat(outcome.opened()).isEqualTo(1);
        assertThat(rows()).isEqualTo(2);
        List<Instant> firstSeen = jdbc.query(
                "SELECT first_seen_at FROM service_alerts WHERE source_alert_id = 'a' ORDER BY id",
                (rows, index) -> rows.getTimestamp(1).toInstant());
        // The first stretch still says when it began and ended; the second begins afresh.
        assertThat(firstSeen).containsExactly(T0, T0.plusSeconds(3600));
        assertThat(openRows()).isEqualTo(1);
    }

    @Test
    void anEntityNamingNeitherARouteNorAStopIsNotStoredButItsAlertIs() {
        lifecycle.apply(List.of(alert("a", "text", new AffectedEntity(null, null))), T0);

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_alert_entities", Integer.class)).isZero();
    }

    @Test
    void aRouteAndStopTheScheduleDoesNotKnowAreKeptExactlyAsPublished() {
        lifecycle.apply(List.of(alert("a", "text", new AffectedEntity("ZZ-999", "no-such-stop"))), T0);

        assertThat(jdbc.queryForObject(
                "SELECT route_id || '/' || stop_id FROM service_alert_entities", String.class))
                .isEqualTo("ZZ-999/no-such-stop");
    }

    @Test
    void aClockThatStepsBackwardsCannotBreakTheStoredOrder() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);

        // A later cycle stamped earlier than the stored one, as after an NTP correction.
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0.minusSeconds(30));
        lifecycle.apply(List.of(), T0.minusSeconds(60));

        assertThat(instant("last_seen_at", "a")).isEqualTo(T0);
        // Resolution never claims to be earlier than the last sighting.
        assertThat(instant("resolved_at", "a")).isEqualTo(T0);
    }

    @Test
    void confirmingAnUnchangedPayloadTouchesOpenAlertsOnly() {
        lifecycle.apply(List.of(alert("open", "x", route("1")), alert("ended", "y", route("2"))), T0);
        lifecycle.apply(List.of(alert("open", "x", route("1"))), T0.plusSeconds(60));

        int confirmed = lifecycle.confirmUnchanged(T0.plusSeconds(120));

        assertThat(confirmed).isEqualTo(1);
        assertThat(instant("last_seen_at", "open")).isEqualTo(T0.plusSeconds(120));
        assertThat(instant("last_seen_at", "ended")).isEqualTo(T0);
    }

    @Test
    void aFailureHalfwayLeavesTheStoredAlertsExactlyAsTheyWere() {
        lifecycle.apply(List.of(alert("keep", "x", route("1"))), T0);

        // The last step of a cycle fails, after the new alert has been inserted and the
        // entities written. Everything before it must go with it.
        JdbcTemplate failing = Mockito.spy(jdbc);
        Mockito.doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(failing)
                .batchUpdate(
                        ArgumentMatchers.startsWith("UPDATE service_alerts SET resolved_at"),
                        ArgumentMatchers.<Long>anyCollection(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any());
        AlertLifecycleService broken = new AlertLifecycleService(failing, transactions, activeAlerts);

        assertThatThrownBy(() -> broken.apply(List.of(alert("new", "y", route("2"))), T0.plusSeconds(60)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // "new" was not kept, and "keep", which the payload omitted, was not resolved.
        assertThat(rows()).isEqualTo(1);
        assertThat(openRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_alert_id FROM service_alerts", String.class)).isEqualTo("keep");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_alert_entities", Integer.class)).isEqualTo(1);
        // And the count held in memory still describes the stored state.
        assertThat(activeAlerts.current()).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesTwoOpenStretchesOfOneAlert() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO service_alerts (source_alert_id, content_hash, first_seen_at, last_seen_at, last_changed_at)
                VALUES ('a', repeat('0', 64), now(), now(), now())
                """))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theDatabaseRefusesAResolutionBeforeTheLastSighting() {
        lifecycle.apply(List.of(alert("a", "text", route("501"))), T0);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE service_alerts SET resolved_at = ? WHERE source_alert_id = 'a'",
                Timestamp.from(T0.minusSeconds(1))))
                .hasMessageContaining("service_alerts_resolved_order_check");
    }

    @Test
    void theCountOfOpenAlertsFollowsEveryCycle() {
        lifecycle.apply(List.of(alert("a", "x", route("1")), alert("b", "y", route("2"))), T0);
        assertThat(activeAlerts.current()).isEqualTo(2);

        lifecycle.apply(List.of(alert("a", "x", route("1"))), T0.plusSeconds(60));
        assertThat(activeAlerts.current()).isEqualTo(1);

        lifecycle.apply(List.of(), T0.plusSeconds(120));
        // Zero is a measurement here: alerts have been ingested and none are open.
        assertThat(activeAlerts.current()).isZero();
    }

    @Test
    void theCountIsUnknownBeforeAnyAlertHasEverBeenStoredAndNotZero() {
        assertThat(activeAlerts.current()).isNull();
    }

    @Test
    void anUnknownCountIsNotRecheckedAgainstTheDatabaseOnEveryRead() {
        assertThat(activeAlerts.current()).isNull();

        // Alerts appear in the table by some route other than a cycle in this process, as in
        // another instance. Reads straight afterwards must not each go back to the database.
        jdbc.update(
                """
                INSERT INTO service_alerts (source_alert_id, content_hash, first_seen_at, last_seen_at, last_changed_at)
                VALUES ('x', repeat('0', 64), now(), now(), now())
                """);

        assertThat(activeAlerts.current()).isNull();

        activeAlerts.forget();
        assertThat(activeAlerts.current()).isEqualTo(1);
    }

    @Test
    void theCountSurvivesARestartBecauseItIsReadBackFromTheStoredAlerts() {
        lifecycle.apply(List.of(alert("a", "x", route("1")), alert("b", "y", route("2"))), T0);
        lifecycle.apply(List.of(alert("a", "x", route("1"))), T0.plusSeconds(60));

        activeAlerts.forget();

        assertThat(activeAlerts.current()).isEqualTo(1);
    }

    @Test
    void aTableWhoseAlertsAreAllResolvedStillCountsAsMeasuredZero() {
        lifecycle.apply(List.of(alert("a", "x", route("1"))), T0);
        lifecycle.apply(List.of(), T0.plusSeconds(60));

        activeAlerts.forget();

        // Not null: alerts were stored once, so an empty open set is a finding, not ignorance.
        assertThat(activeAlerts.current()).isZero();
    }
}
