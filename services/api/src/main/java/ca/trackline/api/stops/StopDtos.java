package ca.trackline.api.stops;

import java.util.List;

import ca.trackline.api.routes.RouteDtos.RouteSummary;

/**
 * Shapes returned by the stop endpoints.
 */
public final class StopDtos {

    private StopDtos() {
    }

    /**
     * A stop or platform.
     *
     * @param wheelchairBoarding the GTFS value exactly as published: 0 not stated,
     *                           1 accessible, 2 not accessible. Never inferred, and
     *                           0 is not a claim that the stop is inaccessible.
     * @param stationId          set only for platforms Trackline grouped into a station
     */
    public record StopSummary(
            String stopId,
            String name,
            String code,
            double latitude,
            double longitude,
            short wheelchairBoarding,
            Long stationId,
            String stationName) {
    }

    /**
     * A stop with the routes that call there.
     */
    public record StopDetail(StopSummary stop, List<RouteSummary> routes) {
    }

    /**
     * A stop with how far it is from the point that was searched.
     *
     * @param distanceMetres metres on the spheroid, rounded to one decimal
     */
    public record NearbyStop(StopSummary stop, double distanceMetres) {
    }
}
