package ca.trackline.api.favourites;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.stops.StopDtos.StopSummary;

/**
 * Shapes returned by the favourites endpoint.
 */
public final class FavouriteDtos {

    private FavouriteDtos() {
    }

    /**
     * A saved route.
     *
     * @param routeId the id as saved, which survives a schedule import
     * @param savedAt when it was saved
     * @param route   what the schedule says it is, or null if the current schedule no longer lists it. The id is kept and
     *                reported, not dropped, since a route can leave one schedule and return in the next
     */
    public record FavouriteRoute(String routeId, Instant savedAt, RouteSummary route) {
    }

    /** A saved stop; {@code stop} is null if the current schedule no longer lists it. */
    public record FavouriteStop(String stopId, Instant savedAt, StopSummary stop) {
    }

    /** Everything one person has saved, most recent first. */
    public record FavouritesView(List<FavouriteRoute> routes, List<FavouriteStop> stops) {
    }
}
