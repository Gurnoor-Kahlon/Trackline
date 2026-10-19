package ca.trackline.api.favourites;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import ca.trackline.api.common.error.InvalidRequestException;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.favourites.FavouriteDtos.FavouriteRoute;
import ca.trackline.api.favourites.FavouriteDtos.FavouriteStop;
import ca.trackline.api.favourites.FavouriteDtos.FavouritesView;
import ca.trackline.api.favourites.FavouritesRepository.Kind;
import ca.trackline.api.favourites.FavouritesRepository.Saved;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.persistence.query.StopQueryRepository;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.stops.StopDtos.StopSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's saved routes and stops.
 *
 * <p>Whose list it is comes from the access token and from nowhere else. No address here takes a user id, and nothing in a
 * request body or query can name one, so there is no way to ask for another person's list: not a check that could be
 * forgotten, but nothing to check. Each repository statement also carries the id.
 *
 * <p>Saving and removing are idempotent. Saving something already saved, or removing something not saved, succeeds the
 * same way, since a client that repeats a request after a lost reply must not be told it failed.
 */
@RestController
@RequestMapping("/api/v1/favourites")
@Tag(name = "Favourites", description = "A signed-in person's saved routes and stops")
public class FavouritesController {

    /**
     * The most of each kind one person may save. A list is bounded so an account cannot be used to store without limit.
     */
    static final int MAX_PER_KIND = 100;

    private final FavouritesRepository favourites;
    private final RouteQueryRepository routes;
    private final StopQueryRepository stops;
    private final Clock clock;

    public FavouritesController(
            FavouritesRepository favourites, RouteQueryRepository routes, StopQueryRepository stops, Clock clock) {
        this.favourites = favourites;
        this.routes = routes;
        this.stops = stops;
        this.clock = clock;
    }

    @GetMapping
    @Operation(
            summary = "Everything the signed-in person has saved",
            description = "Most recent first. A saved id the current schedule no longer lists is kept, with a null "
                    + "route or stop, rather than dropped.")
    public ResponseEntity<FavouritesView> list(@AuthenticationPrincipal Jwt token) {
        UUID user = user(token);

        List<Saved> savedRoutes = favourites.list(user, Kind.ROUTE);
        Map<String, RouteSummary> knownRoutes = routes
                .findRoutesByIds(savedRoutes.stream().map(Saved::id).toList()).stream()
                .collect(Collectors.toMap(RouteSummary::routeId, Function.identity()));

        List<Saved> savedStops = favourites.list(user, Kind.STOP);
        Map<String, StopSummary> knownStops = stops
                .findStopsByIds(savedStops.stream().map(Saved::id).toList()).stream()
                .collect(Collectors.toMap(StopSummary::stopId, Function.identity()));

        FavouritesView view = new FavouritesView(
                savedRoutes.stream()
                        .map(saved -> new FavouriteRoute(saved.id(), saved.savedAt(), knownRoutes.get(saved.id())))
                        .toList(),
                savedStops.stream()
                        .map(saved -> new FavouriteStop(saved.id(), saved.savedAt(), knownStops.get(saved.id())))
                        .toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    @PutMapping("/routes/{routeId}")
    @Operation(summary = "Save a route", description = "Saving a route already saved succeeds. An unknown route is a 404.")
    public ResponseEntity<Void> saveRoute(@AuthenticationPrincipal Jwt token, @PathVariable String routeId) {
        if (!routes.exists(routeId)) {
            throw new ResourceNotFoundException("Route", routeId);
        }
        save(user(token), Kind.ROUTE, routeId, "routes");
        return noContent();
    }

    @DeleteMapping("/routes/{routeId}")
    @Operation(summary = "Remove a saved route", description = "Removing one that is not saved succeeds.")
    public ResponseEntity<Void> removeRoute(@AuthenticationPrincipal Jwt token, @PathVariable String routeId) {
        favourites.remove(user(token), Kind.ROUTE, routeId);
        return noContent();
    }

    @PutMapping("/stops/{stopId}")
    @Operation(summary = "Save a stop", description = "Saving a stop already saved succeeds. An unknown stop is a 404.")
    public ResponseEntity<Void> saveStop(@AuthenticationPrincipal Jwt token, @PathVariable String stopId) {
        if (stops.findStopsByIds(List.of(stopId)).isEmpty()) {
            throw new ResourceNotFoundException("Stop", stopId);
        }
        save(user(token), Kind.STOP, stopId, "stops");
        return noContent();
    }

    @DeleteMapping("/stops/{stopId}")
    @Operation(summary = "Remove a saved stop", description = "Removing one that is not saved succeeds.")
    public ResponseEntity<Void> removeStop(@AuthenticationPrincipal Jwt token, @PathVariable String stopId) {
        favourites.remove(user(token), Kind.STOP, stopId);
        return noContent();
    }

    private void save(UUID user, Kind kind, String id, String noun) {
        // Saving what is already saved must still work at the limit, so only a new one is refused.
        if (!favourites.contains(user, kind, id) && favourites.count(user, kind) >= MAX_PER_KIND) {
            throw new InvalidRequestException("You can save at most %d %s. Remove one first.".formatted(MAX_PER_KIND, noun));
        }
        favourites.add(user, kind, id, Instant.now(clock));
    }

    private static UUID user(Jwt token) {
        return UUID.fromString(token.getSubject());
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
