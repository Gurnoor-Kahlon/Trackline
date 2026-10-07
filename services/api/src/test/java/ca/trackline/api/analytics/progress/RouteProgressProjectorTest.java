package ca.trackline.api.analytics.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.persistence.query.PreviousReadingRepository;
import ca.trackline.api.persistence.query.PreviousReadingRepository.PreviousReading;
import ca.trackline.api.persistence.query.ShapeProjectionRepository;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ProjectionPoint;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ShapeProjection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bookkeeping around projection, with the database replaced.
 *
 * <p>What is under test is not geometry — that belongs to the database tests — but
 * what the projector does with what it is told: how many queries it makes, which
 * vehicles it sends, and that every vehicle in comes out with exactly one stated
 * outcome.
 */
class RouteProgressProjectorTest {

    private static final ProgressProperties THRESHOLDS = new ProgressProperties(150, 60, 30, Duration.ofMinutes(5), 15);
    private static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    private ShapeProjectionRepository shapes;
    private PreviousReadingRepository previousReadings;
    private SimpleMeterRegistry registry;
    private RouteProgressProjector projector;

    @BeforeEach
    void setUp() {
        shapes = mock(ShapeProjectionRepository.class);
        previousReadings = mock(PreviousReadingRepository.class);
        registry = new SimpleMeterRegistry();
        projector = new RouteProgressProjector(shapes, previousReadings, THRESHOLDS, new ProgressMetrics(registry));

        when(previousReadings.latestBefore(any(), any())).thenReturn(Map.of());
        when(shapes.project(any(), anyDouble())).thenReturn(List.of());
    }

    private static VehicleSnapshot vehicle(String id, String route, Float bearing, Instant at) {
        return new VehicleSnapshot(id, route, null, 43.65, -79.38, bearing, 5f, null, null, null, null, at);
    }

    private static VehicleSnapshot vehicle(String id, String route, Float bearing) {
        return vehicle(id, route, bearing, NOW);
    }

    /** A real-looking east-west pair of shapes for one vehicle, direction 0 running east. */
    private static List<ShapeProjection> eastWest(String vehicleId) {
        return List.of(
                new ShapeProjection(vehicleId, (short) 0, 10_000, false, 6, 0.25, null, 90.0),
                new ShapeProjection(vehicleId, (short) 1, 10_000, false, 9, 0.75, null, 270.0));
    }

    @Test
    void oneQueryPlacesEveryVehicleAcrossManyRoutes() {
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            vehicles.add(vehicle("v" + index, "route-" + (index % 60), 90f));
        }

        projector.project(vehicles);

        // 300 vehicles on 60 routes: one statement for the shapes and one for the
        // earlier readings, not one per route and certainly not one per vehicle.
        verify(shapes, times(1)).project(any(), anyDouble());
        verify(previousReadings, times(1)).latestBefore(any(), any());
    }

    @Test
    void everyVehicleInComesOutWithExactlyOneOutcome() {
        when(shapes.project(any(), anyDouble())).thenReturn(eastWest("on-route"));
        List<VehicleSnapshot> vehicles = List.of(
                vehicle("on-route", "501", 90f),
                vehicle("no-route", null, 90f),
                vehicle("no-shape", "303", 90f));

        RouteProgressSnapshot result = projector.project(vehicles);

        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get("on-route").orElseThrow().status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.get("no-route").orElseThrow().status()).isEqualTo(ProjectionStatus.NO_ROUTE);
        assertThat(result.get("no-shape").orElseThrow().status()).isEqualTo(ProjectionStatus.NO_GEOMETRY);
    }

    @Test
    void vehiclesWithNoRouteAreNeverSentToTheDatabase() {
        projector.project(List.of(vehicle("a", null, 90f), vehicle("b", "501", 90f)));

        ArgumentCaptor<List<ProjectionPoint>> points = pointsCaptor();
        verify(shapes).project(points.capture(), anyDouble());
        assertThat(points.getValue()).extracting(ProjectionPoint::vehicleId).containsExactly("b");
    }

    @Test
    void aRouteWithoutAShapeIsNoGeometryRatherThanOffRoute() {
        // The two mean different things: one has nothing to measure against, the other
        // measured and found the vehicle far away.
        RouteProgress result = projector.project(List.of(vehicle("v", "303", 90f))).get("v").orElseThrow();

        assertThat(result.status()).isEqualTo(ProjectionStatus.NO_GEOMETRY);
        assertThat(result.offsetMetres()).isNull();
    }

    @Test
    void aRepeatedVehicleIdKeepsOnlyTheNewestReading() {
        Instant older = NOW.minusSeconds(20);
        when(shapes.project(any(), anyDouble())).thenReturn(eastWest("dup"));

        RouteProgressSnapshot result = projector.project(List.of(
                vehicle("dup", "501", 90f, NOW),
                vehicle("dup", "501", 270f, older)));

        // Without this the one vehicle would contribute four candidate shapes.
        ArgumentCaptor<List<ProjectionPoint>> points = pointsCaptor();
        verify(shapes).project(points.capture(), anyDouble());
        assertThat(points.getValue()).hasSize(1);
        assertThat(result.size()).isEqualTo(1);
        // The newer reading heads 90, so direction 0 — not the older reading's 270.
        assertThat(result.get("dup").orElseThrow().directionId()).isEqualTo((short) 0);
    }

    @Test
    void anEarlierReadingOnTheSameRouteIsPassedToTheQuery() {
        when(previousReadings.latestBefore(any(), any()))
                .thenReturn(Map.of("v", new PreviousReading("501", 43.64, -79.40, NOW.minusSeconds(30))));

        projector.project(List.of(vehicle("v", "501", 90f)));

        ArgumentCaptor<List<ProjectionPoint>> points = pointsCaptor();
        verify(shapes).project(points.capture(), anyDouble());
        assertThat(points.getValue().get(0).previousLatitude()).isEqualTo(43.64);
        assertThat(points.getValue().get(0).previousLongitude()).isEqualTo(-79.40);
    }

    @Test
    void anEarlierReadingOnAnotherRouteIsDiscarded() {
        // The vehicle changed assignment. Projecting its old position onto the new
        // route's shapes would manufacture a jump and a false direction with it.
        when(previousReadings.latestBefore(any(), any()))
                .thenReturn(Map.of("v", new PreviousReading("29", 43.64, -79.40, NOW.minusSeconds(30))));

        projector.project(List.of(vehicle("v", "501", 90f)));

        ArgumentCaptor<List<ProjectionPoint>> points = pointsCaptor();
        verify(shapes).project(points.capture(), anyDouble());
        assertThat(points.getValue().get(0).previousLatitude()).isNull();
        assertThat(points.getValue().get(0).previousLongitude()).isNull();
    }

    @Test
    void theConfiguredWindowsAreWhatTheQueriesReceive() {
        projector.project(List.of(vehicle("v", "501", 90f)));

        verify(shapes).project(any(), eq(15.0));
        verify(previousReadings).latestBefore(any(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void anEmptySnapshotProducesAnEmptyResult() {
        RouteProgressSnapshot result = projector.project(List.of());

        assertThat(result.size()).isZero();
    }

    @Test
    void outcomesAreCountedByStatusAndUnknownReason() {
        // Neither shape agrees with a heading of 0 (due north against east-west
        // shapes), and there is no earlier reading, so the direction is unknown.
        when(shapes.project(any(), anyDouble())).thenReturn(eastWest("unknown"));

        projector.project(List.of(vehicle("unknown", "501", 0f), vehicle("loose", null, 0f)));

        assertThat(registry.counter("trackline.analytics.projection.vehicles", "status", "UNKNOWN_DIRECTION").count())
                .isEqualTo(1);
        assertThat(registry.counter("trackline.analytics.projection.vehicles", "status", "NO_ROUTE").count())
                .isEqualTo(1);
        assertThat(registry.counter("trackline.analytics.projection.unknown", "reason", "NO_PREVIOUS_READING").count())
                .isEqualTo(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<ProjectionPoint>> pointsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
