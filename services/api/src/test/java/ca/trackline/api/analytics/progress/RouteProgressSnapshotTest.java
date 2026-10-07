package ca.trackline.api.analytics.progress;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteProgressSnapshotTest {

    private static final ShapeCandidate CHOSEN = new ShapeCandidate((short) 1, 7.5, 0.4, 5_000, false, 270.0, null);

    @Test
    void anAssignedVehicleCarriesDirectionAndProgressTogether() {
        RouteProgress progress = RouteProgress.assigned("v1", ProjectionStatus.ASSIGNED_BY_BEARING, CHOSEN);

        assertThat(progress.directionId()).isEqualTo((short) 1);
        assertThat(progress.progressMetres()).isEqualTo(2_000.0);
        assertThat(progress.progressFraction()).isEqualTo(0.4);
        assertThat(progress.offsetMetres()).isEqualTo(7.5);
    }

    @Test
    void onlyAnAssignedStatusMayCarryAPosition() {
        // The database enforces the same rule; this keeps the mistake from being
        // made in the first place rather than discovered as a failed insert.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RouteProgress.assigned("v1", ProjectionStatus.OFF_ROUTE, CHOSEN));
    }

    @Test
    void everyUnassignedOutcomeCarriesNoPosition() {
        for (RouteProgress progress : new RouteProgress[] {
                RouteProgress.noRoute("a"),
                RouteProgress.noGeometry("b"),
                RouteProgress.offRoute("c", 900),
                RouteProgress.unknownDirection("d", 12, UnknownReason.NO_PREVIOUS_READING)}) {
            assertThat(progress.directionId()).as(progress.status().name()).isNull();
            assertThat(progress.progressMetres()).as(progress.status().name()).isNull();
            assertThat(progress.progressFraction()).as(progress.status().name()).isNull();
        }
    }

    @Test
    void onlyTheTwoAssignedStatusesCountAsAssigned() {
        assertThat(ProjectionStatus.values())
                .filteredOn(ProjectionStatus::isAssigned)
                .containsExactlyInAnyOrder(ProjectionStatus.ASSIGNED_BY_BEARING, ProjectionStatus.ASSIGNED_BY_MOVEMENT);
    }

    @Test
    void countsAreGroupedByStatusAndUnknownReason() {
        RouteProgressSnapshot snapshot = new RouteProgressSnapshot(Map.of(
                "a", RouteProgress.assigned("a", ProjectionStatus.ASSIGNED_BY_BEARING, CHOSEN),
                "b", RouteProgress.assigned("b", ProjectionStatus.ASSIGNED_BY_BEARING, CHOSEN),
                "c", RouteProgress.noRoute("c"),
                "d", RouteProgress.unknownDirection("d", 3, UnknownReason.NO_PREVIOUS_READING),
                "e", RouteProgress.unknownDirection("e", 3, UnknownReason.CONFLICTING_MOVEMENT)));

        assertThat(snapshot.countsByStatus())
                .containsEntry(ProjectionStatus.ASSIGNED_BY_BEARING, 2L)
                .containsEntry(ProjectionStatus.NO_ROUTE, 1L)
                .containsEntry(ProjectionStatus.UNKNOWN_DIRECTION, 2L)
                .doesNotContainKey(ProjectionStatus.OFF_ROUTE);
        assertThat(snapshot.unknownReasons())
                .containsEntry(UnknownReason.NO_PREVIOUS_READING, 1L)
                .containsEntry(UnknownReason.CONFLICTING_MOVEMENT, 1L);
    }

    @Test
    void anUnprojectedSnapshotIsEmptyAndHasNoEntryForAnyVehicle() {
        RouteProgressSnapshot snapshot = RouteProgressSnapshot.unprojected();

        assertThat(snapshot.size()).isZero();
        assertThat(snapshot.get("anything")).isEmpty();
        assertThat(snapshot.countsByStatus()).isEmpty();
    }

    @Test
    void theSnapshotCannotBeChangedAfterItIsBuilt() {
        RouteProgressSnapshot snapshot = RouteProgressSnapshot.unprojected();

        assertThatThrownBy(() -> snapshot.byVehicle().put("x", RouteProgress.noRoute("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
