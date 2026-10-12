package ca.trackline.api.common.geo;

import ca.trackline.api.common.error.InvalidRequestException;

/**
 * A rectangle in degrees, as callers pass it: {@code minLon,minLat,maxLon,maxLat}.
 *
 * <p>Shared by every endpoint that filters by area, so the live-vehicle list and the
 * live-vehicle stream cannot drift into parsing it differently.
 *
 * @param minLongitude west edge
 * @param minLatitude  south edge
 * @param maxLongitude east edge
 * @param maxLatitude  north edge
 */
public record BoundingBox(double minLongitude, double minLatitude, double maxLongitude, double maxLatitude) {

    /** The textual shape, for a {@code @Pattern}: four comma-separated decimals. */
    public static final String FORMAT = "^-?\\d+(\\.\\d+)?(,-?\\d+(\\.\\d+)?){3}$";

    /**
     * Largest span a caller may ask for, in degrees.
     *
     * <p>Toronto spans about 0.4 degrees of latitude, so a degree in each direction
     * covers the service area generously. A request larger than this is asking for
     * the whole fleet by another name and should say so by sending no box at all.
     */
    public static final double MAX_SPAN_DEGREES = 1.0;

    /**
     * @throws InvalidRequestException if the corners are the wrong way round or the
     *                                 box is larger than {@link #MAX_SPAN_DEGREES}
     */
    public static BoundingBox parse(String text) {
        String[] parts = text.split(",");
        BoundingBox box = new BoundingBox(
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]));

        if (box.maxLongitude < box.minLongitude || box.maxLatitude < box.minLatitude) {
            throw new InvalidRequestException(
                    "bbox corners are in the wrong order: expected minLon,minLat,maxLon,maxLat");
        }
        if (box.maxLongitude - box.minLongitude > MAX_SPAN_DEGREES
                || box.maxLatitude - box.minLatitude > MAX_SPAN_DEGREES) {
            throw new InvalidRequestException("bbox may not span more than %s degrees".formatted(MAX_SPAN_DEGREES));
        }
        return box;
    }

    /** Edges are inclusive: a vehicle exactly on the boundary is inside. */
    public boolean contains(double longitude, double latitude) {
        return longitude >= minLongitude
                && longitude <= maxLongitude
                && latitude >= minLatitude
                && latitude <= maxLatitude;
    }
}
