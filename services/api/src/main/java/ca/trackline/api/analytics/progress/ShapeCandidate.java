package ca.trackline.api.analytics.progress;

/**
 * One direction's shape, as seen from one vehicle.
 *
 * <p>Everything direction inference needs about a single (vehicle, direction)
 * pair, with no database types, so the inference can be tested by constructing
 * these directly rather than by arranging geometry.
 *
 * @param directionId      GTFS direction, 0 or 1
 * @param offsetMetres     true distance from the vehicle to this shape
 * @param progressFraction where along the shape the vehicle is, 0 to 1
 * @param lengthMetres     true length of the shape
 * @param loop             whether the shape's endpoints meet, so progress wraps
 * @param tangentBearing   compass bearing the shape runs in at that point, or null
 *                         where the shape has no usable direction there
 * @param previousFraction where the vehicle's previous reading projects onto this
 *                         shape, or null when there is no usable previous reading
 */
public record ShapeCandidate(
        short directionId,
        double offsetMetres,
        double progressFraction,
        double lengthMetres,
        boolean loop,
        Double tangentBearing,
        Double previousFraction) {

    /** Distance along the shape from the start, in true metres. */
    public double progressMetres() {
        return progressFraction * lengthMetres;
    }

    /**
     * How far the vehicle has travelled forward along this shape since its previous
     * reading, or null when there is no previous reading.
     *
     * <p>Negative means it moved toward the start, which is what a vehicle running
     * the opposite way looks like. On a loop the difference is wrapped to the
     * shorter way round: a bus crossing the seam from the end back to the start has
     * moved a few metres forward, not nearly a whole lap backward.
     */
    public Double forwardMovementMetres() {
        if (previousFraction == null) {
            return null;
        }
        double delta = (progressFraction - previousFraction) * lengthMetres;
        if (loop) {
            double half = lengthMetres / 2;
            if (delta > half) {
                delta -= lengthMetres;
            } else if (delta < -half) {
                delta += lengthMetres;
            }
        }
        return delta;
    }
}
