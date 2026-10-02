package ca.trackline.api.gtfs.schedule.derive;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ca.trackline.api.gtfs.schedule.derive.ScheduledHeadwayBuilder.DayType;
import ca.trackline.api.gtfs.schedule.derive.ShapeSelection.RouteDirection;
import ca.trackline.api.gtfs.schedule.derive.ShapeSelection.ShapeCandidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decisions behind the derived network. Each is small, and each moves data
 * that later milestones depend on, so they are tested apart from the SQL.
 */
class DerivationRulesTest {

    // --- Station naming -----------------------------------------------------

    @Test
    void platformNamesResolveToTheirStation() {
        assertThat(StationNaming.stationNameFor("Kennedy Station - Eastbound Platform"))
                .contains("Kennedy Station");
        assertThat(StationNaming.stationNameFor("Avenue Station Eastbound Platform"))
                .contains("Avenue Station");
        assertThat(StationNaming.stationNameFor("Kennedy Station LRT Platform"))
                .contains("Kennedy Station");
        assertThat(StationNaming.stationNameFor("Kennedy Station Drop-off Platform"))
                .contains("Kennedy Station");
    }

    @Test
    void aMultiWordStationNameIsKeptWhole() {
        assertThat(StationNaming.stationNameFor("Vaughan Metropolitan Centre Station - Subway Platform"))
                .contains("Vaughan Metropolitan Centre Station");
        assertThat(StationNaming.stationNameFor("Aga Khan Park & Museum Station Westbound Platform"))
                .contains("Aga Khan Park & Museum Station");
    }

    @Test
    void surfaceStopsNearAStationAreNotPlatforms() {
        // The feed has 637 stop names mentioning a station; most are bus stops
        // standing near one. Grouping those would fabricate a hierarchy the agency
        // never published and would put bus stops inside subway stations.
        assertThat(StationNaming.stationNameFor("Aukland Rd at Kipling GO Station")).isEmpty();
        assertThat(StationNaming.stationNameFor("Aberfoyle Cres at Islington Ave - Islington Station")).isEmpty();
        assertThat(StationNaming.stationNameFor("1 Front St West - Union Station")).isEmpty();
        assertThat(StationNaming.stationNameFor("Bay St at Albert St")).isEmpty();
    }

    @Test
    void aPlatformWithoutAStationNameIsLeftUngrouped() {
        // Better unattached than attached to a station Trackline invented.
        assertThat(StationNaming.stationNameFor("Dundas St West at Dundas West Outer Platform")).isEmpty();
        assertThat(StationNaming.stationNameFor("")).isEmpty();
        assertThat(StationNaming.stationNameFor(null)).isEmpty();
    }

    @Test
    void namesDifferingOnlyInSpacingGroupTogether() {
        assertThat(StationNaming.stationNameFor("Bay  Station  -  Eastbound Platform"))
                .contains("Bay Station");
    }

    // --- Shape selection ----------------------------------------------------

    @Test
    void theMostUsedShapeRepresentsItsRouteAndDirection() {
        Optional<String> primary = ShapeSelection.choosePrimary(List.of(
                new ShapeCandidate("shape-a", 12),
                new ShapeCandidate("shape-b", 340),
                new ShapeCandidate("shape-c", 7)));

        assertThat(primary).contains("shape-b");
    }

    @Test
    void tiesResolveTheSameWayEveryTime() {
        // Two equally-used variants must not swap between imports: every stored
        // progress value along the route would shift for no reason.
        List<ShapeCandidate> candidates = List.of(
                new ShapeCandidate("shape-b", 50), new ShapeCandidate("shape-a", 50));

        assertThat(ShapeSelection.choosePrimary(candidates)).contains("shape-a");
        assertThat(ShapeSelection.choosePrimary(candidates.reversed())).contains("shape-a");
    }

    @Test
    void everyRouteAndDirectionGetsExactlyOnePrimary() {
        Map<RouteDirection, List<ShapeCandidate>> grouped = Map.of(
                new RouteDirection("501", (short) 0),
                List.of(new ShapeCandidate("a", 3), new ShapeCandidate("b", 9)),
                new RouteDirection("501", (short) 1),
                List.of(new ShapeCandidate("c", 4)));

        assertThat(ShapeSelection.choosePrimaries(grouped)).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void noCandidatesMeansNoPrimary() {
        assertThat(ShapeSelection.choosePrimary(List.of())).isEmpty();
    }

    @Test
    void shapesWhoseEndpointsMeetAreLoops() {
        // Spacing on a loop wraps past the end. Treating one as linear puts a huge
        // false gap between the last vehicle and the first.
        assertThat(ShapeSelection.isLoop(0)).isTrue();
        assertThat(ShapeSelection.isLoop(ShapeSelection.LOOP_ENDPOINT_TOLERANCE_METRES)).isTrue();
        assertThat(ShapeSelection.isLoop(ShapeSelection.LOOP_ENDPOINT_TOLERANCE_METRES + 0.1)).isFalse();
        assertThat(ShapeSelection.isLoop(14_000)).isFalse();
    }

    // --- Headways -----------------------------------------------------------

    @Test
    void medianIsTakenOverTheMiddleOfTheGaps() {
        assertThat(Headways.medianSeconds(List.of(300))).isEqualTo(300);
        assertThat(Headways.medianSeconds(List.of(600, 300, 900))).isEqualTo(600);
        assertThat(Headways.medianSeconds(List.of(240, 300, 360, 420))).isEqualTo(330);
    }

    @Test
    void medianResistsASingleLongGap() {
        // The reason it is not a mean: one 40-minute gap at the top of the service
        // hour would otherwise report a frequent route as sparse.
        List<Integer> gaps = List.of(300, 300, 300, 300, 2400);

        assertThat(Headways.medianSeconds(gaps)).isEqualTo(300);
    }

    @Test
    void anEvenNumberOfGapsRoundsRatherThanTruncating() {
        assertThat(Headways.medianSeconds(List.of(300, 301))).isEqualTo(301);
    }

    @Test
    void takingAMedianOfNothingIsRejected() {
        assertThatThrownBy(() -> Headways.medianSeconds(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void departuresPastMidnightFallInTheirClockHour() {
        assertThat(Headways.clockHour(0)).isZero();
        assertThat(Headways.clockHour(18 * 3600 + 900)).isEqualTo(18);
        // 25:15:00 is a 01:15 departure belonging to the previous service day.
        assertThat(Headways.clockHour(25 * 3600 + 900)).isEqualTo(1);
        assertThat(Headways.clockHour(28 * 3600)).isEqualTo(4);
    }

    // --- Representative dates ----------------------------------------------

    @Test
    void weekdayScheduleIsSampledFromAWednesday() {
        // Mondays attract statutory holidays and Fridays attract seasonal
        // variation; a holiday Monday read as a typical weekday would understate
        // frequency across the whole network.
        Optional<LocalDate> date = ScheduledHeadwayBuilder.representativeDate(
                DayType.WEEKDAY, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31));

        assertThat(date).contains(LocalDate.of(2026, 9, 30));
        assertThat(date.orElseThrow().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
    }

    @Test
    void weekendSchedulesAreSampledFromTheirOwnDays() {
        LocalDate start = LocalDate.of(2026, 9, 30);
        LocalDate end = LocalDate.of(2026, 10, 31);

        assertThat(ScheduledHeadwayBuilder.representativeDate(DayType.SATURDAY, start, end))
                .contains(LocalDate.of(2026, 10, 3));
        assertThat(ScheduledHeadwayBuilder.representativeDate(DayType.SUNDAY, start, end))
                .contains(LocalDate.of(2026, 10, 4));
    }

    @Test
    void aWindowWithoutThatDayYieldsNothingRatherThanASubstitute() {
        // A three-day feed may contain no Sunday. Reporting no headways is correct;
        // substituting a Saturday would describe service that does not run.
        assertThat(ScheduledHeadwayBuilder.representativeDate(
                        DayType.SUNDAY, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2)))
                .isEmpty();
    }
}
