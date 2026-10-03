package ca.trackline.api.routes;

import java.util.List;

/**
 * Shapes returned by the route endpoints.
 *
 * <p>Grouped in one file because they describe a single resource and are only
 * meaningful together; splitting eight short records across eight files would
 * make the contract harder to read, not easier.
 */
public final class RouteDtos {

    private RouteDtos() {
    }

    /**
     * A route in a list.
     *
     * @param routeId          GTFS id, the only identifier that joins to realtime data
     * @param shortName        route number as riders know it
     * @param longName         descriptive name
     * @param mode             bus, streetcar or subway
     * @param colour           six hex digits from the feed, or null
     * @param textColour       the feed's chosen text colour, or null
     * @param supportsRealtime false for subway routes, which have no vehicle feed
     */
    public record RouteSummary(
            String routeId,
            String shortName,
            String longName,
            TransitMode mode,
            String colour,
            String textColour,
            boolean supportsRealtime) {
    }

    /**
     * A route with the per-direction detail a route page needs.
     *
     * @param directions one entry per direction the schedule actually serves
     */
    public record RouteDetail(
            String routeId,
            String shortName,
            String longName,
            TransitMode mode,
            String colour,
            String textColour,
            boolean supportsRealtime,
            List<RouteDirection> directions) {
    }

    /**
     * One direction of a route.
     *
     * @param directionId     GTFS direction, 0 or 1
     * @param headsign        the destination most trips in this direction show
     * @param tripCount       scheduled trips in this direction
     * @param stopCount       stops on the longest pattern, or null if none is known
     * @param lengthMetres    length of the shape vehicles are projected onto, true metres
     * @param loop            whether the shape's endpoints meet
     */
    public record RouteDirection(
            short directionId,
            String headsign,
            int tripCount,
            Integer stopCount,
            Double lengthMetres,
            boolean loop) {
    }

    /**
     * A route's geometry as GeoJSON.
     *
     * @param routeId         the route
     * @param directionId     which direction this geometry describes
     * @param shapeId         the shape it came from
     * @param lengthMetres    true length, measured on the geography
     * @param simplifiedToMetres tolerance applied, or null when returned unsimplified
     * @param geometry        a GeoJSON LineString
     */
    public record RouteShape(
            String routeId,
            short directionId,
            String shapeId,
            double lengthMetres,
            Integer simplifiedToMetres,
            Object geometry) {
    }

    /**
     * A stop on a route, in the order the route serves it.
     *
     * @param sequence           position along the representative pattern
     * @param distanceAlongRouteMetres distance from the start where the feed states it
     * @param wheelchairBoarding GTFS value as published: 0 unknown, 1 yes, 2 no
     */
    public record RouteStop(
            String stopId,
            String name,
            String code,
            double latitude,
            double longitude,
            int sequence,
            Double distanceAlongRouteMetres,
            short wheelchairBoarding,
            Long stationId,
            String stationName) {
    }
}
