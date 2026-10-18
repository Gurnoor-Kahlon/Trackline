package ca.trackline.api.analytics.bunching;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingDtos.BunchingEventView;
import ca.trackline.api.analytics.bunching.BunchingDtos.EventStatus;
import ca.trackline.api.analytics.bunching.BunchingEventService.Outcome;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.persistence.query.BunchingQueryRepository;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle of bunching events against a real database: one row for the life of a bunch, times that
 * are what the analyses saw, and a failure halfway that leaves nothing behind.
 */
class BunchingEventIntegrationTest extends PostgisIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-13T14:00:00Z");

    /** Records a pack at once, so each test can set one up in a single call. */
    private BunchingEventService events;

    /** Records a pack only once seen three times running, as the application is configured. */
    private BunchingEventService confirming;

    @Autowired
    private BunchingQueryRepository queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private BunchingProperties properties;

    private BunchingProperties immediate;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void freshServices() {
        immediate = new BunchingProperties(
                properties.closeRatio(), properties.serviceGapRatio(), properties.severeGapRatio(),
                properties.severeClusterSize(), properties.minVehicles(), properties.minVehiclesWithoutTimetable(),
                properties.minSharedVehicles(), properties.cyclesToResolve(), 1);
        events = new BunchingEventService(jdbc, transactions, immediate, meters);
        confirming = new BunchingEventService(jdbc, transactions, properties, meters);
        jdbc.execute("TRUNCATE bunching_events");
    }

    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE bunching_events");
    }

    private static BunchingCandidate bunch(String route, int direction, BunchingSeverity severity, String... ids) {
        return new BunchingCandidate(
                route, (short) direction, List.of(ids), 400.0, 3000.0, 6500.0, severity, SpacingBasis.SCHEDULE);
    }

    private static BunchingCandidate bunch(String route, int direction, String... ids) {
        return bunch(route, direction, BunchingSeverity.MODERATE, ids);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM bunching_events", Integer.class);
    }

    private int openRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bunching_events WHERE resolved_at IS NULL", Integer.class);
    }

    private Instant instant(String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM bunching_events ORDER BY id LIMIT 1", Timestamp.class);
        return value == null ? null : value.toInstant();
    }

    @Test
    void aNewBunchIsOpenedOnceWithWhatWasSeen() {
        Outcome outcome = events.apply(List.of(bunch("501", 0, "a", "b")), T0);

        assertThat(outcome.opened()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        BunchingEventView view = queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10).getFirst();
        assertThat(view.status()).isEqualTo(EventStatus.ACTIVE);
        assertThat(view.vehicleIds()).containsExactly("a", "b");
        assertThat(view.peakVehicles()).isEqualTo(2);
        assertThat(view.spanMetres()).isEqualTo(400.0);
        assertThat(view.expectedGapMetres()).isEqualTo(3000.0);
        assertThat(view.serviceGapMetres()).isEqualTo(6500.0);
        assertThat(view.basis()).isEqualTo(SpacingBasis.SCHEDULE);
        assertThat(view.severity()).isEqualTo(BunchingSeverity.MODERATE);
        assertThat(view.sightings()).isEqualTo(1);
        assertThat(view.firstSeenAt()).isEqualTo(T0);
        assertThat(view.lastSeenAt()).isEqualTo(T0);
        assertThat(view.resolvedAt()).isNull();
    }

    @Test
    void aBunchThatLastsIsOneRowUpdatedNotARowPerCycle() {
        for (int cycle = 0; cycle < 30; cycle++) {
            events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20L * cycle));
        }

        assertThat(rows()).isEqualTo(1);
        BunchingEventView view = queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10).getFirst();
        assertThat(view.sightings()).isEqualTo(30);
        assertThat(view.firstSeenAt()).isEqualTo(T0);
        assertThat(view.lastSeenAt()).isEqualTo(T0.plusSeconds(20L * 29));
    }

    @Test
    void aBunchThatGrowsKeepsItsEventAndRemembersItsPeak() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(bunch("501", 0, BunchingSeverity.SEVERE, "a", "b", "c")), T0.plusSeconds(20));
        events.apply(List.of(bunch("501", 0, "b", "c")), T0.plusSeconds(40));

        assertThat(rows()).isEqualTo(1);
        BunchingEventView view = queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10).getFirst();
        // Where it is now, and the most it ever was.
        assertThat(view.vehicleIds()).containsExactly("b", "c");
        assertThat(view.peakVehicles()).isEqualTo(3);
        assertThat(view.severity()).isEqualTo(BunchingSeverity.MODERATE);
        assertThat(view.peakSeverity()).isEqualTo(BunchingSeverity.SEVERE);
    }

    @Test
    void aBunchThatIsOnlyEverModerateNeverReportsASeverePeak() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20));

        assertThat(queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10).getFirst().peakSeverity())
                .isEqualTo(BunchingSeverity.MODERATE);
    }

    @Test
    void oneMissedAnalysisDoesNotResolveABunch() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);

        Outcome outcome = events.apply(List.of(), T0.plusSeconds(20));

        assertThat(outcome.missed()).isEqualTo(1);
        assertThat(openRows()).isEqualTo(1);
    }

    @Test
    void twoMissedAnalysesInARowResolveItAtTheSecondAndKeepItsLastSighting() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(), T0.plusSeconds(20));

        Outcome outcome = events.apply(List.of(), T0.plusSeconds(40));

        assertThat(outcome.resolved()).isEqualTo(1);
        assertThat(openRows()).isZero();
        assertThat(instant("resolved_at")).isEqualTo(T0.plusSeconds(40));
        // Last seen is when it was last there, not when it was found to be gone.
        assertThat(instant("last_seen_at")).isEqualTo(T0);
    }

    @Test
    void aBunchThatFlickersAcrossAThresholdForOneAnalysisIsStillOneEvent() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(), T0.plusSeconds(20));
        events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(40));
        events.apply(List.of(), T0.plusSeconds(60));
        events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(80));

        assertThat(rows()).isEqualTo(1);
        assertThat(openRows()).isEqualTo(1);
        // The run of misses starts over each time it is seen, so a bunch that keeps coming back never reaches two.
        assertThat(jdbc.queryForObject("SELECT missed_cycles FROM bunching_events", Integer.class)).isZero();
    }

    @Test
    void aResolvedBunchIsKeptAndANewOneOnTheSameVehiclesIsANewEvent() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(), T0.plusSeconds(20));
        events.apply(List.of(), T0.plusSeconds(40));

        events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(3600));

        assertThat(rows()).isEqualTo(2);
        assertThat(openRows()).isEqualTo(1);
    }

    @Test
    void routesAndDirectionsHaveTheirOwnEvents() {
        events.apply(List.of(bunch("501", 0, "a", "b"), bunch("501", 1, "a", "b"), bunch("504", 0, "a", "b")), T0);

        assertThat(rows()).isEqualTo(3);
        // Only one of them is seen again; the others are missed, not continued.
        Outcome outcome = events.apply(List.of(bunch("501", 1, "a", "b")), T0.plusSeconds(20));
        assertThat(outcome.continued()).isEqualTo(1);
        assertThat(outcome.missed()).isEqualTo(2);
    }

    @Test
    void aClockThatStepsBackwardsCannotBreakTheStoredOrder() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);

        events.apply(List.of(bunch("501", 0, "a", "b")), T0.minusSeconds(30));
        events.apply(List.of(), T0.minusSeconds(40));
        events.apply(List.of(), T0.minusSeconds(60));

        assertThat(instant("last_seen_at")).isEqualTo(T0);
        // A resolution never claims to be earlier than the last sighting.
        assertThat(instant("resolved_at")).isEqualTo(T0);
    }

    @Test
    void aFailureHalfwayLeavesTheStoredEventsExactlyAsTheyWere() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);

        // The last step, resolving, fails after a new bunch has been inserted.
        JdbcTemplate failing = Mockito.spy(jdbc);
        Mockito.doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(failing)
                .update(
                        ArgumentMatchers.startsWith("UPDATE bunching_events SET resolved_at"),
                        ArgumentMatchers.<Object>any(),
                        ArgumentMatchers.<Object>any());
        BunchingEventService broken = new BunchingEventService(failing, transactions, immediate, meters);
        events.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20));
        // Two misses are needed to resolve, so make the open bunch overdue to be resolved on the next apply.
        jdbc.update("UPDATE bunching_events SET missed_cycles = 1");

        assertThatThrownBy(() -> broken.apply(List.of(bunch("504", 0, "x", "y")), T0.plusSeconds(40)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // The new bunch was not kept and the overdue one was not resolved.
        assertThat(rows()).isEqualTo(1);
        assertThat(openRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT route_id FROM bunching_events", String.class)).isEqualTo("501");
    }

    @Test
    void theDatabaseRefusesWhatTheApplicationMustNeverStore() {
        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO bunching_events (route_id, direction_id, vehicle_ids, peak_vehicles, span_m,
                    expected_gap_m, service_gap_m, basis, severity, peak_severity, first_seen_at, last_seen_at)
                VALUES ('501', 0, ARRAY['only-one'], 1, 1, 1, 1, 'SCHEDULE', 'MODERATE', 'MODERATE', now(), now())
                """))
                .hasMessageContaining("bunching_events_vehicles_check");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO bunching_events (route_id, direction_id, vehicle_ids, peak_vehicles, span_m,
                    expected_gap_m, service_gap_m, basis, severity, peak_severity, first_seen_at, last_seen_at)
                VALUES ('501', 0, ARRAY['a', 'b'], 2, 1, 1, 1, 'GUESSED', 'MODERATE', 'MODERATE', now(), now())
                """))
                .hasMessageContaining("bunching_events_basis_check");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO bunching_events (route_id, direction_id, vehicle_ids, peak_vehicles, span_m,
                    expected_gap_m, service_gap_m, basis, severity, peak_severity, first_seen_at, last_seen_at, resolved_at)
                VALUES ('501', 0, ARRAY['a', 'b'], 2, 1, 1, 1, 'SCHEDULE', 'MODERATE', 'MODERATE', now(), now(),
                        now() - interval '1 hour')
                """))
                .hasMessageContaining("bunching_events_resolved_order_check");
    }

    // --- reading them back -----------------------------------------------------------------------------------------

    @Test
    void aRoutesEventsListOpenOnesAndRecentlyResolvedOnesNewestFirst() {
        events.apply(List.of(bunch("501", 0, "a", "b")), T0);
        events.apply(List.of(), T0.plusSeconds(20));
        events.apply(List.of(), T0.plusSeconds(40));
        events.apply(List.of(bunch("501", 0, "c", "d")), T0.plusSeconds(3600));

        List<BunchingEventView> found = queries.findForRoute("501", T0.minus(Duration.ofHours(1)), 10);

        assertThat(found).extracting(BunchingEventView::vehicleIds).containsExactly(List.of("c", "d"), List.of("a", "b"));
        assertThat(found).extracting(BunchingEventView::status).containsExactly(EventStatus.ACTIVE, EventStatus.RESOLVED);
    }

    @Test
    void aResolvedBunchOlderThanTheWindowIsLeftOutButAnOpenOneIsNeverCutByAge() {
        Instant longAgo = T0.minus(Duration.ofDays(3));
        events.apply(List.of(bunch("501", 0, "old", "gone")), longAgo);
        events.apply(List.of(bunch("501", 0, "long", "lived")), longAgo.plusSeconds(10));
        events.apply(List.of(bunch("501", 0, "long", "lived")), longAgo.plusSeconds(20));

        List<BunchingEventView> found = queries.findForRoute("501", T0.minus(Duration.ofHours(24)), 10);

        // The first bunch resolved three days ago, outside the window. The second has been open as
        // long and is still open, so age does not cut it off.
        assertThat(found).extracting(BunchingEventView::vehicleIds).containsExactly(List.of("long", "lived"));
    }

    @Test
    void anotherRoutesEventsAreNotReturned() {
        events.apply(List.of(bunch("501", 0, "a", "b"), bunch("504", 0, "c", "d")), T0);

        assertThat(queries.findForRoute("504", T0.minus(Duration.ofDays(1)), 10))
                .singleElement()
                .satisfies(view -> assertThat(view.vehicleIds()).containsExactly("c", "d"));
    }

    @Test
    void theListIsBoundedByTheLimit() {
        for (int index = 0; index < 5; index++) {
            events.apply(List.of(bunch("501", 0, "a" + index, "b" + index)), T0.plusSeconds(index));
        }

        assertThat(queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 3)).hasSize(3);
    }

    @Test
    void aRouteWithNoEventsHasAnEmptyListNotAnError() {
        assertThat(queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10)).isEmpty();
    }

    // --- confirmation, as the application is configured -------------------------------------------------------

    @Test
    void aPackIsNotRecordedUntilItHasBeenSeenOnThreeAnalysesInARow() {
        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0);
        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20));

        assertThat(rows()).isZero();

        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(40));

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aConfirmedEventIsDatedFromTheFirstSightingAndCountsEverySighting() {
        for (int cycle = 0; cycle < 3; cycle++) {
            confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20L * cycle));
        }

        BunchingEventView view = queries.findForRoute("501", T0.minus(Duration.ofDays(1)), 10).getFirst();

        assertThat(view.firstSeenAt()).isEqualTo(T0);
        assertThat(view.lastSeenAt()).isEqualTo(T0.plusSeconds(40));
        assertThat(view.sightings()).isEqualTo(3);
    }

    @Test
    void aPackThatOnlyPassesNeverMakesAnEvent() {
        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0);
        confirming.apply(List.of(), T0.plusSeconds(20));
        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(40));
        confirming.apply(List.of(), T0.plusSeconds(60));

        assertThat(rows()).isZero();
    }

    @Test
    void anEventAlreadyOpenSurvivesAPackThatReturnsAfterOneMissWithoutWaitingAgain() {
        for (int cycle = 0; cycle < 3; cycle++) {
            confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(20L * cycle));
        }
        confirming.apply(List.of(), T0.plusSeconds(60));

        confirming.apply(List.of(bunch("501", 0, "a", "b")), T0.plusSeconds(80));

        assertThat(rows()).isEqualTo(1);
        assertThat(openRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT missed_cycles FROM bunching_events", Integer.class)).isZero();
    }
}
