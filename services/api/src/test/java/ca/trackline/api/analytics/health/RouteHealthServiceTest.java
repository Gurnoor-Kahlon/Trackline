package ca.trackline.api.analytics.health;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.analytics.bunching.BunchingFixtures;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.progress.ProjectionStatus;
import ca.trackline.api.analytics.progress.RouteProgress;
import ca.trackline.api.analytics.progress.RouteProgressSnapshot;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.gtfs.schedule.derive.ScheduledHeadwayBuilder.DayType;
import ca.trackline.api.persistence.query.HealthReferenceRepository.DirectionKey;
import ca.trackline.api.persistence.query.HealthReferenceRepository.ShapeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The assembly: which vehicles count towards spacing, and what each direction is handed to
 * the calculator. The judging is tested separately; this is the bookkeeping around it.
 */
class RouteHealthServiceTest {

    private static final Instant FEED = Instant.parse("2026-10-12T14:00:00Z");
    private static final DirectionKey QUEEN_EAST = new DirectionKey("501", (short) 0);
    private static final ShapeInfo LINE = new ShapeInfo(20_000, false);

    private RouteHealthService service;

    @BeforeEach
    void newService() {
        // The analysis itself touches no collaborator, so none is supplied.
        service = new RouteHealthService(
                null, null, HealthFixtures.defaults(), BunchingFixtures.defaults(), null, null, null);
    }

    private static VehicleSnapshot vehicle(String id, String route, Instant reportedAt) {
        return new VehicleSnapshot(id, route, null, 43.65, -79.38, 90f, 5f, null, null, null, null, reportedAt);
    }

    private static RouteProgress at(String id, short direction, double progressMetres) {
        return new RouteProgress(
                id, ProjectionStatus.ASSIGNED_BY_BEARING, direction, progressMetres, progressMetres / 20_000, 5.0, null);
    }

    private static RouteProgressSnapshot snapshotOf(RouteProgress... placed) {
        Map<String, RouteProgress> byVehicle = new HashMap<>();
        for (RouteProgress progress : placed) {
            byVehicle.put(progress.vehicleId(), progress);
        }
        return new RouteProgressSnapshot(byVehicle);
    }

    /** The verdicts of an analysis; the spacing beside them is tested in its own class. */
    private HealthSnapshot computeHealth(
            List<VehicleSnapshot> vehicles,
            RouteProgressSnapshot progress,
            Instant feedTimestamp,
            Instant computedAt,
            Map<DirectionKey, ShapeInfo> shapes,
            Map<DirectionKey, Double> headways,
            boolean covered) {
        return service.compute(vehicles, progress, feedTimestamp, computedAt, shapes, headways, covered).health();
    }

    private HealthSnapshot analyse(
            List<VehicleSnapshot> vehicles,
            RouteProgressSnapshot progress,
            Map<DirectionKey, Double> headways,
            boolean covered) {
        return computeHealth(vehicles, progress, FEED, FEED, Map.of(QUEEN_EAST, LINE), headways, covered);
    }

    private static RouteHealth route(HealthSnapshot snapshot, String routeId) {
        return snapshot.routes().stream().filter(candidate -> candidate.routeId().equals(routeId)).findFirst().orElseThrow();
    }

    private static List<VehicleSnapshot> fresh(String... ids) {
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        for (String id : ids) {
            vehicles.add(vehicle(id, "501", FEED.minusSeconds(20)));
        }
        return vehicles;
    }

    @Test
    void measuresTheSpacingBetweenPlacedVehiclesInOrderAlongTheRoute() {
        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c", "d"),
                snapshotOf(at("a", (short) 0, 8000), at("b", (short) 0, 2000), at("c", (short) 0, 14_000), at("d", (short) 0, 5000)),
                Map.of(),
                false);

        DirectionHealth direction = route(snapshot, "501").directions().getFirst();
        // 2,000 -> 5,000 -> 8,000 -> 14,000
        assertThat(direction.metrics().gaps()).isEqualTo(3);
        assertThat(direction.metrics().maxGapMetres()).isEqualTo(6000.0);
        assertThat(direction.metrics().meanGapMetres()).isCloseTo(4000.0, within(1e-9));
    }

    @Test
    void aVehicleWhoseOwnReportIsOldIsExcludedFromSpacingAndCounted() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("a", "b", "c"));
        // 150 s before the feed's timestamp: over the 120 s line.
        vehicles.add(vehicle("old", "501", FEED.minusSeconds(150)));

        HealthSnapshot snapshot = analyse(
                vehicles,
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000), at("old", (short) 0, 4000)),
                Map.of(),
                false);

        DirectionHealth direction = route(snapshot, "501").directions().getFirst();
        assertThat(direction.metrics().vehicles()).isEqualTo(3);
        assertThat(direction.metrics().staleVehicles()).isEqualTo(1);
        // The stale vehicle at 4,000 is not one of the gaps: 3,000 -> 6,000 -> 9,000.
        assertThat(direction.metrics().maxGapMetres()).isEqualTo(3000.0);
    }

    @Test
    void staleIsJudgedAgainstTheFeedsOwnTimeSoAFeedRunningLateDoesNotStaleEveryVehicle() {
        // The feed itself is old, but every vehicle is within two minutes of it.
        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c"),
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000)),
                Map.of(),
                false);

        assertThat(route(snapshot, "501").directions().getFirst().metrics().staleVehicles()).isZero();
    }

    @Test
    void aVehicleExactlyTwoMinutesOldIsNotYetStale() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("a", "b"));
        vehicles.add(vehicle("edge", "501", FEED.minusSeconds(120)));

        HealthSnapshot snapshot = analyse(
                vehicles,
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("edge", (short) 0, 9000)),
                Map.of(),
                false);

        assertThat(route(snapshot, "501").directions().getFirst().metrics().staleVehicles()).isZero();
    }

    @Test
    void vehiclesAtEitherTerminalAreLeftOutOfTheSpacingButCountedAsRunning() {
        HealthSnapshot snapshot = analyse(
                fresh("start", "a", "b", "end"),
                snapshotOf(at("start", (short) 0, 120), at("a", (short) 0, 5000), at("b", (short) 0, 10_000), at("end", (short) 0, 19_900)),
                Map.of(),
                false);

        DirectionHealth direction = route(snapshot, "501").directions().getFirst();
        assertThat(direction.metrics().vehicles()).isEqualTo(2);
        assertThat(direction.metrics().terminalVehicles()).isEqualTo(2);
        assertThat(direction.metrics().maxGapMetres()).isEqualTo(5000.0);
    }

    @Test
    void aVehicleExactlyAtTheEdgeOfTheTerminalZoneIsInTheSpacing() {
        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c"),
                snapshotOf(at("a", (short) 0, 200), at("b", (short) 0, 10_000), at("c", (short) 0, 19_800)),
                Map.of(),
                false);

        // 200 m from the start and 200 m from the end are the boundary, not inside it.
        assertThat(route(snapshot, "501").directions().getFirst().metrics().terminalVehicles()).isZero();
    }

    @Test
    void aLoopHasNoTerminalToParkAt() {
        HealthSnapshot snapshot = computeHealth(
                fresh("a", "b", "c"),
                snapshotOf(at("a", (short) 0, 50), at("b", (short) 0, 9000), at("c", (short) 0, 15_000)),
                FEED, FEED,
                Map.of(QUEEN_EAST, new ShapeInfo(20_000, true)),
                Map.of(), false);

        DirectionHealth direction = route(snapshot, "501").directions().getFirst();
        assertThat(direction.metrics().terminalVehicles()).isZero();
        // Three vehicles on a loop have three gaps, the last wrapping past the end.
        assertThat(direction.metrics().gaps()).isEqualTo(3);
    }

    @Test
    void vehiclesNotPlacedInADirectionAreCountedOnTheRouteAndTakePartInNoSpacing() {
        RouteProgress lost = new RouteProgress("lost", ProjectionStatus.OFF_ROUTE, null, null, null, 900.0, null);
        RouteProgress unsure = new RouteProgress(
                "unsure", ProjectionStatus.UNKNOWN_DIRECTION, null, null, null, 4.0,
                ca.trackline.api.analytics.progress.UnknownReason.NO_PREVIOUS_READING);

        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c", "lost", "unsure"),
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000), lost, unsure),
                Map.of(),
                false);

        RouteHealth health = route(snapshot, "501");
        assertThat(health.unplacedVehicles()).isEqualTo(2);
        assertThat(health.directions().getFirst().metrics().vehicles()).isEqualTo(3);
    }

    @Test
    void aRouteWhoseVehiclesAreAllUnplacedIsUnknownButStillListed() {
        RouteProgress lost = new RouteProgress("lost", ProjectionStatus.OFF_ROUTE, null, null, null, 900.0, null);

        HealthSnapshot snapshot = analyse(fresh("lost"), snapshotOf(lost), Map.of(), false);

        RouteHealth health = route(snapshot, "501");
        assertThat(health.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(health.directions()).isEmpty();
        assertThat(health.unplacedVehicles()).isEqualTo(1);
    }

    @Test
    void vehiclesWithNoRouteAreNotAnyRoutesToJudge() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("a", "b", "c"));
        vehicles.add(vehicle("deadhead", null, FEED.minusSeconds(10)));

        HealthSnapshot snapshot = analyse(
                vehicles,
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000),
                        RouteProgress.noRoute("deadhead")),
                Map.of(),
                false);

        assertThat(snapshot.routes()).extracting(RouteHealth::routeId).containsExactly("501");
    }

    @Test
    void aVehicleWithNoPlacementAtAllIsIgnoredNotInvented() {
        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c", "never-projected"),
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000)),
                Map.of(),
                false);

        assertThat(route(snapshot, "501").unplacedVehicles()).isZero();
        assertThat(route(snapshot, "501").directions().getFirst().metrics().vehicles()).isEqualTo(3);
    }

    @Test
    void aVehicleRepeatedInTheFeedIsOneVehicleTheNewestReading() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("a", "b", "c"));
        vehicles.add(vehicle("c", "501", FEED.minusSeconds(5)));

        HealthSnapshot snapshot = analyse(
                vehicles,
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000)),
                Map.of(),
                false);

        assertThat(route(snapshot, "501").directions().getFirst().metrics().vehicles()).isEqualTo(3);
    }

    @Test
    void anEmptyProjectionYieldsNoVerdictsAtAll() {
        HealthSnapshot snapshot = analyse(fresh("a", "b", "c"), RouteProgressSnapshot.unprojected(), Map.of(), false);

        assertThat(snapshot.routes()).isEmpty();
    }

    @Test
    void theTimetablesSpacingIsHandedOnForTheRoutesDirection() {
        HealthSnapshot snapshot = analyse(
                fresh("a", "b", "c"),
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000)),
                Map.of(QUEEN_EAST, 600.0),
                true);

        DirectionHealth direction = route(snapshot, "501").directions().getFirst();
        assertThat(direction.metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
        // No speed has been measured yet, so the timetable cannot become a distance, and it says so.
        assertThat(direction.reasons()).contains(ReasonCode.SPEED_UNKNOWN);
        assertThat(snapshot.scheduleCovered()).isTrue();
    }

    @Test
    void onceVehiclesHaveBeenWatchedLongEnoughTheTimetableBecomesADistance() {
        // Four vehicles moving at 5 m/s, fed in over four cycles a minute apart. A 20 km route at
        // 3 km spacing needs about seven, so four is comfortably more than half.
        for (int minute = 0; minute <= 3; minute++) {
            Instant now = FEED.plusSeconds(minute * 60L);
            double advance = 5.0 * minute * 60;
            List<VehicleSnapshot> vehicles = List.of(
                    vehicle("a", "501", now.minusSeconds(10)),
                    vehicle("b", "501", now.minusSeconds(10)),
                    vehicle("c", "501", now.minusSeconds(10)),
                    vehicle("d", "501", now.minusSeconds(10)));
            HealthSnapshot snapshot = computeHealth(
                    vehicles,
                    snapshotOf(
                            at("a", (short) 0, 3000 + advance), at("b", (short) 0, 6000 + advance),
                            at("c", (short) 0, 9000 + advance), at("d", (short) 0, 12_000 + advance)),
                    now, now, Map.of(QUEEN_EAST, LINE), Map.of(QUEEN_EAST, 600.0), true);

            DirectionHealth direction = route(snapshot, "501").directions().getFirst();
            if (minute < 3) {
                assertThat(direction.metrics().speedMetresPerSecond()).isNull();
            } else {
                assertThat(direction.metrics().speedMetresPerSecond()).isCloseTo(5.0, within(0.01));
                // 600 s at 5 m/s: three kilometres between vehicles, which is what they have.
                assertThat(direction.metrics().expectedGapMetres()).isCloseTo(3000.0, within(1.0));
                assertThat(direction.state()).isEqualTo(RouteHealthState.EXCELLENT);
            }
        }
    }

    @Test
    void routesAreListedInOrderOfId() {
        List<VehicleSnapshot> vehicles = List.of(
                vehicle("x", "7", FEED), vehicle("y", "501", FEED), vehicle("z", "12", FEED));

        HealthSnapshot placed = computeHealth(
                vehicles,
                snapshotOf(
                        new RouteProgress("x", ProjectionStatus.OFF_ROUTE, null, null, null, 1.0, null),
                        new RouteProgress("y", ProjectionStatus.OFF_ROUTE, null, null, null, 1.0, null),
                        new RouteProgress("z", ProjectionStatus.OFF_ROUTE, null, null, null, 1.0, null)),
                FEED, FEED, Map.of(), Map.of(), false);

        assertThat(placed.routes()).extracting(RouteHealth::routeId).containsExactly("12", "501", "7");
    }

    @Test
    void theSnapshotRecordsWhichFeedItCameFromAndWhetherTheTimetableApplied() {
        HealthSnapshot snapshot = analyse(fresh("a"), snapshotOf(at("a", (short) 0, 3000)), Map.of(), false);

        assertThat(snapshot.feedTimestamp()).isEqualTo(FEED);
        assertThat(snapshot.scheduleCovered()).isFalse();
    }

    @Test
    void weekendsHaveTheirOwnTimetableAndEveryOtherDayUsesTheWeekdayOne() {
        assertThat(RouteHealthService.dayTypeOf(DayOfWeek.SATURDAY)).isEqualTo(DayType.SATURDAY);
        assertThat(RouteHealthService.dayTypeOf(DayOfWeek.SUNDAY)).isEqualTo(DayType.SUNDAY);
        for (DayOfWeek weekday : new DayOfWeek[] {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
            assertThat(RouteHealthService.dayTypeOf(weekday)).isEqualTo(DayType.WEEKDAY);
        }
    }

    // --- bunching and the spacing strip --------------------------------------------------------------------

    private RouteHealthService.AnalysisResult analyseAll(List<VehicleSnapshot> vehicles, RouteProgressSnapshot progress) {
        return service.compute(vehicles, progress, FEED, FEED, Map.of(QUEEN_EAST, LINE), Map.of(), false);
    }

    private static List<ca.trackline.api.analytics.bunching.BunchingModels.PlacedVehicle> stripOf(
            RouteHealthService.AnalysisResult result) {
        return result.spacing().routes().getFirst().directions().getFirst().vehicles();
    }

    @Test
    void aBunchIsFoundAndItsVehiclesAreMarkedBunchedInTheStrip() {
        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("a", "b", "c", "d"),
                snapshotOf(at("a", (short) 0, 2000), at("b", (short) 0, 2300), at("c", (short) 0, 9000), at("d", (short) 0, 12_000)));

        var direction = result.spacing().routes().getFirst().directions().getFirst();
        assertThat(direction.bunches()).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicleIds()).containsExactly("a", "b");
            assertThat(bunch.routeId()).isEqualTo("501");
        });
        assertThat(stripOf(result)).extracting(vehicle -> vehicle.vehicleId() + ":" + vehicle.role())
                .containsExactly("a:BUNCHED", "b:BUNCHED", "c:SPACED", "d:SPACED");
    }

    @Test
    void theHealthVerdictIsToldAboutTheBunchAndNamesItAsAReason() {
        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("a", "b", "c", "d"),
                snapshotOf(at("a", (short) 0, 2000), at("b", (short) 0, 2300), at("c", (short) 0, 9000), at("d", (short) 0, 12_000)));

        DirectionHealth health = result.health().routes().getFirst().directions().getFirst();

        assertThat(health.metrics().bunchingClusters()).isEqualTo(1);
        assertThat(health.reasons()).contains(ReasonCode.BUNCHING);
        assertThat(health.state()).isNotIn(RouteHealthState.EXCELLENT, RouteHealthState.NORMAL);
    }

    @Test
    void bunchingIsJudgedAgainstTheSameSpacingTheVerdictUses() {
        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("a", "b", "c", "d"),
                snapshotOf(at("a", (short) 0, 2000), at("b", (short) 0, 2300), at("c", (short) 0, 9000), at("d", (short) 0, 12_000)));

        var strip = result.spacing().routes().getFirst().directions().getFirst();
        DirectionHealth health = result.health().routes().getFirst().directions().getFirst();

        // No timetable here, so both use the vehicles' own average gap, and they must agree on it.
        assertThat(strip.expectedGapMetres()).isCloseTo(health.metrics().meanGapMetres(), within(1e-9));
        assertThat(strip.basis()).isEqualTo(ca.trackline.api.analytics.spacing.SpacingBasis.OBSERVED);
    }

    @Test
    void twoVehiclesParkedTogetherAtATerminalAreALayoverNotABunch() {
        // Two buses sitting 70 m apart at the start of the line, and three well spaced on the route.
        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("layover-1", "layover-2", "a", "b", "c"),
                snapshotOf(at("layover-1", (short) 0, 50), at("layover-2", (short) 0, 120), at("a", (short) 0, 6000),
                        at("b", (short) 0, 12_000), at("c", (short) 0, 18_000)));

        var direction = result.spacing().routes().getFirst().directions().getFirst();

        assertThat(direction.bunches()).isEmpty();
        assertThat(stripOf(result)).filteredOn(vehicle -> vehicle.vehicleId().startsWith("layover"))
                .extracting(vehicle -> vehicle.role().name()).containsOnly("AT_TERMINAL");
    }

    @Test
    void vehiclesThatHaveStoppedReportingAreNeverPartOfABunch() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("a", "b", "c"));
        vehicles.add(vehicle("stale-1", "501", FEED.minusSeconds(300)));
        vehicles.add(vehicle("stale-2", "501", FEED.minusSeconds(300)));

        RouteHealthService.AnalysisResult result = analyseAll(
                vehicles,
                snapshotOf(at("a", (short) 0, 4000), at("b", (short) 0, 8000), at("c", (short) 0, 12_000),
                        at("stale-1", (short) 0, 15_000), at("stale-2", (short) 0, 15_100)));

        assertThat(result.spacing().bunches()).isEmpty();
        assertThat(stripOf(result)).filteredOn(vehicle -> vehicle.vehicleId().startsWith("stale"))
                .extracting(vehicle -> vehicle.role().name()).containsOnly("STALE");
    }

    @Test
    void vehiclesThatCouldNotBePlacedAreNotInTheStrip() {
        RouteProgress lost = new RouteProgress("lost", ProjectionStatus.OFF_ROUTE, null, null, null, 900.0, null);

        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("a", "b", "c", "lost"),
                snapshotOf(at("a", (short) 0, 3000), at("b", (short) 0, 6000), at("c", (short) 0, 9000), lost));

        assertThat(stripOf(result)).extracting(vehicle -> vehicle.vehicleId()).containsExactly("a", "b", "c");
    }

    @Test
    void theStripListsEveryPlacedVehicleInOrderAlongTheRouteWhateverItsRole() {
        List<VehicleSnapshot> vehicles = new ArrayList<>(fresh("mid", "end", "start"));
        vehicles.add(vehicle("old", "501", FEED.minusSeconds(300)));

        RouteHealthService.AnalysisResult result = analyseAll(
                vehicles,
                snapshotOf(at("mid", (short) 0, 9000), at("end", (short) 0, 19_900), at("start", (short) 0, 60), at("old", (short) 0, 5000)));

        assertThat(stripOf(result)).extracting(vehicle -> vehicle.vehicleId())
                .containsExactly("start", "old", "mid", "end");
    }

    @Test
    void aDirectionWithFewerThanThreeUsableVehiclesHasNoBunchAndNoYardstickToShow() {
        RouteHealthService.AnalysisResult result = analyseAll(
                fresh("a"), snapshotOf(at("a", (short) 0, 3000)));

        var direction = result.spacing().routes().getFirst().directions().getFirst();

        assertThat(direction.bunches()).isEmpty();
        assertThat(direction.expectedGapMetres()).isNull();
        assertThat(direction.vehicles()).hasSize(1);
    }

    @Test
    void theSnapshotGathersTheBunchesOfEveryRouteAndDirection() {
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        Map<String, RouteProgress> placed = new HashMap<>();
        // Route 501 direction 0 and route 504 direction 0 each have a pack and a wide gap.
        for (String route : new String[] {"501", "504"}) {
            double[] at = {2000, 2300, 9000, 12_000};
            for (int index = 0; index < at.length; index++) {
                String id = route + "-" + index;
                vehicles.add(vehicle(id, route, FEED.minusSeconds(20)));
                placed.put(id, at(id, (short) 0, at[index]));
            }
        }

        RouteHealthService.AnalysisResult result = service.compute(
                vehicles, new RouteProgressSnapshot(placed), FEED, FEED,
                Map.of(QUEEN_EAST, LINE, new DirectionKey("504", (short) 0), LINE), Map.of(), false);

        assertThat(result.spacing().bunches()).extracting(bunch -> bunch.routeId()).containsExactlyInAnyOrder("501", "504");
    }

    @Test
    void theSpacingRecordsWhichFeedItCameFrom() {
        RouteHealthService.AnalysisResult result = analyseAll(fresh("a"), snapshotOf(at("a", (short) 0, 3000)));

        assertThat(result.spacing().feedTimestamp()).isEqualTo(FEED);
        assertThat(result.spacing().computedAt()).isEqualTo(FEED);
    }
}
