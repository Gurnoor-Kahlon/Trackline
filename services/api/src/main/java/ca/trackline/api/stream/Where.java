package ca.trackline.api.stream;

/**
 * Where a vehicle was and what it was working: just enough to decide whether it
 * was inside a client's filter.
 *
 * <p>Needed because a filter has two edges. A vehicle that enters a client's route
 * or area is sent; one that leaves it must be removed from the client, and the
 * server can only know the client has it by remembering where it was.
 */
public record Where(String route, double lat, double lon) {
}
