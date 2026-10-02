package ca.trackline.api.gtfs.schedule.derive;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which shape represents a route and direction, and which shapes are
 * loops.
 *
 * <p>Pure so the decisions can be tested directly. They are small but load-bearing:
 * vehicles are projected onto the primary shape, so choosing the wrong one moves
 * every vehicle on that route.
 */
public final class ShapeSelection {

    /**
     * How close a shape's endpoints must be for it to count as a loop.
     *
     * <p>Loops matter because spacing between vehicles wraps past the end of the
     * line, and treating a loop as linear puts an enormous false gap between the
     * last vehicle and the first. 200 m is generous enough to absorb a terminal
     * where the recorded start and end sit on opposite sides of a street.
     */
    public static final double LOOP_ENDPOINT_TOLERANCE_METRES = 200;

    private ShapeSelection() {
    }

    public static boolean isLoop(double endpointGapMetres) {
        return endpointGapMetres <= LOOP_ENDPOINT_TOLERANCE_METRES;
    }

    /**
     * One shape variant of a route and direction.
     *
     * @param shapeId   the shape's GTFS id
     * @param tripCount how many trips use it
     */
    public record ShapeCandidate(String shapeId, int tripCount) {
    }

    /**
     * Picks the shape vehicles on a route and direction are projected onto: the
     * variant the most trips use, because it describes the path most service
     * actually takes.
     *
     * <p>Ties are broken by shape id so the choice is stable. Without that, two
     * equally-used variants could swap between imports and every stored progress
     * value would shift for no reason.
     */
    public static Optional<String> choosePrimary(Collection<ShapeCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingInt(ShapeCandidate::tripCount)
                        .thenComparing(Comparator.comparing(ShapeCandidate::shapeId).reversed()))
                .map(ShapeCandidate::shapeId);
    }

    /**
     * Picks a primary for every route and direction present.
     *
     * @param candidatesByRouteDirection shapes grouped by their route and direction
     * @return the shape ids to mark primary
     */
    public static Set<String> choosePrimaries(
            Map<RouteDirection, ? extends Collection<ShapeCandidate>> candidatesByRouteDirection) {
        return candidatesByRouteDirection.values().stream()
                .map(ShapeSelection::choosePrimary)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A route travelling in one direction. */
    public record RouteDirection(String routeId, short directionId) {
    }
}
