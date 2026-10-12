package ca.trackline.api.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.alerts.AlertDtos.AffectedRoute;
import ca.trackline.api.alerts.AlertDtos.AlertStatus;
import ca.trackline.api.alerts.AlertDtos.AlertView;
import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;
import ca.trackline.api.persistence.query.AlertQueryRepository;
import ca.trackline.api.routes.TransitMode;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertRoute;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the alert endpoints read, against a real database: the filters, the joins that
 * name routes and stops, and the way ids the schedule does not know are reported.
 */
class AlertQueryRepositoryIntegrationTest extends PostgisIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private AlertLifecycleService lifecycle;

    @Autowired
    private AlertQueryRepository alerts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedTheSchedule() {
        clear(jdbc);
        jdbc.execute("TRUNCATE service_alerts CASCADE");
        long feed = insertFeedVersion(jdbc);
        insertRoute(jdbc, feed, "501", "501", "Queen", 0);
        insertRoute(jdbc, feed, "504", "504", "King", 0);
        insertRoute(jdbc, feed, "7", "7", "Bathurst", 3);
        insertRoute(jdbc, feed, "L1", "Line 1", "Yonge-University", 1);
        jdbc.update("UPDATE routes SET color = 'DA251D', text_color = 'FFFFFF' WHERE route_id = '501'");
        jdbc.update(
                """
                INSERT INTO stops (stop_id, feed_version_id, code, name, location)
                VALUES ('3718', ?, '1', 'Union Station', ST_SetSRID(ST_MakePoint(-79.38, 43.64), 4326)::geography)
                """,
                feed);
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("TRUNCATE service_alerts CASCADE");
        clear(jdbc);
    }

    private static AlertSnapshot alert(String id, String description, AffectedEntity... affected) {
        return new AlertSnapshot(id, 1, 8, "head", description, "https://example.test/" + id, List.of(affected));
    }

    private static AffectedEntity route(String id) {
        return new AffectedEntity(id, null);
    }

    @Test
    void listsActiveAlertsNewestFirstWithTheirObservedTimes() {
        lifecycle.apply(List.of(alert("old", "first", route("501"))), T0);
        lifecycle.apply(List.of(alert("old", "first", route("501")), alert("new", "second", route("504"))),
                T0.plusSeconds(600));

        List<AlertView> found = alerts.find(true, null, null, 0, 10);

        assertThat(found).extracting(AlertView::sourceAlertId).containsExactly("new", "old");
        AlertView newest = found.get(0);
        assertThat(newest.status()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(newest.firstSeenAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(newest.resolvedAt()).isNull();
        assertThat(newest.description()).isEqualTo("second");
        assertThat(newest.url()).isEqualTo("https://example.test/new");
        assertThat(newest.cause()).isEqualTo(AlertCause.UNKNOWN_CAUSE);
        assertThat(newest.effect()).isEqualTo(AlertEffect.UNKNOWN_EFFECT);
    }

    @Test
    void activeAndResolvedAreSeparateViews() {
        lifecycle.apply(List.of(alert("gone", "x", route("501")), alert("here", "y", route("501"))), T0);
        lifecycle.apply(List.of(alert("here", "y", route("501"))), T0.plusSeconds(60));

        assertThat(alerts.find(true, null, null, 0, 10)).extracting(AlertView::sourceAlertId).containsExactly("here");
        List<AlertView> resolved = alerts.find(false, null, null, 0, 10);
        assertThat(resolved).extracting(AlertView::sourceAlertId).containsExactly("gone");
        assertThat(resolved.get(0).status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.get(0).resolvedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(alerts.count(true, null, null)).isEqualTo(1);
        assertThat(alerts.count(false, null, null)).isEqualTo(1);
    }

    @Test
    void filtersByTheRouteOrStopAnAlertNames() {
        lifecycle.apply(
                List.of(
                        alert("queen", "x", route("501")),
                        alert("king", "y", route("504")),
                        alert("both", "z", route("501"), route("504")),
                        alert("union", "w", new AffectedEntity("501", "3718"))),
                T0);

        assertThat(alerts.find(true, "501", null, 0, 10)).extracting(AlertView::sourceAlertId)
                .containsExactlyInAnyOrder("queen", "both", "union");
        assertThat(alerts.count(true, "501", null)).isEqualTo(3);
        assertThat(alerts.find(true, "504", null, 0, 10)).extracting(AlertView::sourceAlertId)
                .containsExactlyInAnyOrder("king", "both");
        assertThat(alerts.find(true, null, "3718", 0, 10)).extracting(AlertView::sourceAlertId)
                .containsExactly("union");
        assertThat(alerts.find(true, "504", "3718", 0, 10)).isEmpty();
        assertThat(alerts.find(true, "no-such-route", null, 0, 10)).isEmpty();
    }

    @Test
    void namesTheRoutesAnAlertAppliesToWithTheirScheduleColours() {
        lifecycle.apply(List.of(alert("a", "x", route("501"))), T0);

        AffectedRoute queen = alerts.find(true, null, null, 0, 10).get(0).routes().get(0);

        assertThat(queen.known()).isTrue();
        assertThat(queen.shortName()).isEqualTo("501");
        assertThat(queen.longName()).isEqualTo("Queen");
        assertThat(queen.mode()).isEqualTo(TransitMode.STREETCAR);
        assertThat(queen.colour()).isEqualTo("DA251D");
        assertThat(queen.textColour()).isEqualTo("FFFFFF");
    }

    @Test
    void anIdTheScheduleDoesNotKnowIsReportedAsUnknownAndNotDropped() {
        lifecycle.apply(List.of(alert("a", "x", route("501"), route("ZZ-999"), new AffectedEntity(null, "no-stop"))), T0);

        AlertView view = alerts.find(true, null, null, 0, 10).get(0);

        assertThat(view.routes()).extracting(AffectedRoute::routeId).containsExactly("501", "ZZ-999");
        AffectedRoute unknown = view.routes().get(1);
        assertThat(unknown.known()).isFalse();
        assertThat(unknown.shortName()).isNull();
        assertThat(unknown.mode()).isNull();
        assertThat(unknown.colour()).isNull();
        assertThat(view.stops()).singleElement().satisfies(stop -> {
            assertThat(stop.stopId()).isEqualTo("no-stop");
            assertThat(stop.known()).isFalse();
            assertThat(stop.name()).isNull();
        });
    }

    @Test
    void namesAStopTheScheduleKnows() {
        lifecycle.apply(List.of(alert("a", "x", new AffectedEntity("501", "3718"))), T0);

        AlertView view = alerts.find(true, null, null, 0, 10).get(0);

        assertThat(view.stops()).singleElement().satisfies(stop -> {
            assertThat(stop.known()).isTrue();
            assertThat(stop.name()).isEqualTo("Union Station");
        });
    }

    @Test
    void listsARouteOnceHoweverManyStopsTheAlertNamesOnIt() {
        lifecycle.apply(List.of(alert("a", "x",
                new AffectedEntity("501", "3718"), new AffectedEntity("501", "9999"), new AffectedEntity("501", null))), T0);

        AlertView view = alerts.find(true, null, null, 0, 10).get(0);

        assertThat(view.routes()).extracting(AffectedRoute::routeId).containsExactly("501");
        assertThat(view.stops()).hasSize(2);
    }

    @Test
    void ordersAnAlertsRoutesNumberedFirstThenByName() {
        lifecycle.apply(List.of(alert("a", "x", route("L1"), route("504"), route("7"), route("501"))), T0);

        AlertView view = alerts.find(true, null, null, 0, 10).get(0);

        // 7 before 501 before 504 by number, not "501" < "504" < "7" by text; then Line 1.
        assertThat(view.routes()).extracting(AffectedRoute::routeId).containsExactly("7", "501", "504", "L1");
    }

    @Test
    void anAlertThatNamesNothingStillHasEmptyLists() {
        lifecycle.apply(List.of(alert("a", "x", new AffectedEntity(null, null))), T0);

        AlertView view = alerts.find(true, null, null, 0, 10).get(0);

        assertThat(view.routes()).isEmpty();
        assertThat(view.stops()).isEmpty();
    }

    @Test
    void pagesThroughAlertsWithoutRepeatingOrSkipping() {
        for (int index = 0; index < 5; index++) {
            lifecycle.apply(
                    java.util.stream.IntStream.rangeClosed(0, index)
                            .mapToObj(i -> alert("a" + i, "x", route("501")))
                            .toList(),
                    T0.plusSeconds(index * 60L));
        }

        List<String> everyPage = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            alerts.find(true, null, null, page, 2).forEach(view -> everyPage.add(view.sourceAlertId()));
        }

        assertThat(everyPage).containsExactly("a4", "a3", "a2", "a1", "a0");
        assertThat(alerts.count(true, null, null)).isEqualTo(5);
    }

    @Test
    void findsOneAlertByIdWhetherActiveOrResolved() {
        lifecycle.apply(List.of(alert("a", "x", route("501"))), T0);
        lifecycle.apply(List.of(), T0.plusSeconds(60));
        long id = alerts.find(false, null, null, 0, 10).get(0).id();

        assertThat(alerts.findById(id)).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(AlertStatus.RESOLVED);
            assertThat(view.routes()).hasSize(1);
        });
        assertThat(alerts.findById(id + 1000)).isEmpty();
    }

    @Test
    void anEmptyTableListsNothingRatherThanFailing() {
        assertThat(alerts.find(true, null, null, 0, 10)).isEmpty();
        assertThat(alerts.count(true, null, null)).isZero();
    }

    @Test
    void reportsWhenTheAlertsFeedWasLastAppliedByThePublishersOwnTimestamp() {
        assertThat(alerts.lastAppliedFeedTimestamp()).isEmpty();
        record(T0, "SUCCESS");
        record(T0.plusSeconds(40), "NO_CHANGE");
        // Suspect and failed cycles changed nothing, so they do not make the data fresher.
        record(T0.plusSeconds(500), "SUSPECT");
        record(T0.plusSeconds(900), "FAILED");

        assertThat(alerts.lastAppliedFeedTimestamp()).contains(T0.plusSeconds(40));
    }

    private void record(Instant headerTimestamp, String status) {
        jdbc.update(
                """
                INSERT INTO ingestion_runs (feed, started_at, duration_ms, status, feed_header_timestamp)
                VALUES ('ALERTS', now(), 1, ?, ?)
                """,
                status, Timestamp.from(headerTimestamp));
    }
}
