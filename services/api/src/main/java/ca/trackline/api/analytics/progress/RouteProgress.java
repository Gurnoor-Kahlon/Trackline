package ca.trackline.api.analytics.progress;

/**
 * Where one vehicle is along its route, or the stated reason it could not be placed.
 *
 * <p>Direction, progress and fraction are present together exactly when
 * {@link #status()} is an assigned status — the same rule the database enforces.
 * {@code offsetMetres} is also kept for off-route and unknown vehicles, where it is
 * the evidence for the verdict, and is null only when there was no shape to measure.
 *
 * @param vehicleId        the vehicle
 * @param status           what the projection concluded
 * @param directionId      the direction it is running, or null unless assigned
 * @param progressMetres   true metres from the start of that direction's shape
 * @param progressFraction the same position as a fraction of the shape's length
 * @param offsetMetres     distance to the matched shape, or to the nearest one
 * @param unknownReason    why the direction is unknown, only for UNKNOWN_DIRECTION
 */
public record RouteProgress(
        String vehicleId,
        ProjectionStatus status,
        Short directionId,
        Double progressMetres,
        Double progressFraction,
        Double offsetMetres,
        UnknownReason unknownReason) {

    public static RouteProgress noRoute(String vehicleId) {
        return new RouteProgress(vehicleId, ProjectionStatus.NO_ROUTE, null, null, null, null, null);
    }

    public static RouteProgress noGeometry(String vehicleId) {
        return new RouteProgress(vehicleId, ProjectionStatus.NO_GEOMETRY, null, null, null, null, null);
    }

    public static RouteProgress offRoute(String vehicleId, double nearestOffsetMetres) {
        return new RouteProgress(
                vehicleId, ProjectionStatus.OFF_ROUTE, null, null, null, nearestOffsetMetres, null);
    }

    public static RouteProgress unknownDirection(
            String vehicleId, double nearestOffsetMetres, UnknownReason reason) {
        return new RouteProgress(
                vehicleId, ProjectionStatus.UNKNOWN_DIRECTION, null, null, null, nearestOffsetMetres, reason);
    }

    public static RouteProgress assigned(String vehicleId, ProjectionStatus status, ShapeCandidate chosen) {
        if (!status.isAssigned()) {
            throw new IllegalArgumentException("Not an assigned status: " + status);
        }
        return new RouteProgress(
                vehicleId,
                status,
                chosen.directionId(),
                chosen.progressMetres(),
                chosen.progressFraction(),
                chosen.offsetMetres(),
                null);
    }
}
