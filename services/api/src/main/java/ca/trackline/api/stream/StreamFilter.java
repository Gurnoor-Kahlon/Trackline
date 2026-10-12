package ca.trackline.api.stream;

import java.util.Set;

import ca.trackline.api.common.geo.BoundingBox;

/**
 * Which vehicles one client wants: some routes, some area, both, or everything.
 *
 * <p>Applied when an event is sent, per client, rather than when it is built. One
 * event then serves every client, and a filter is only ever a view over the same
 * truth.
 *
 * @param routes route ids to include; empty means any route. A vehicle with no
 *               route never matches a route filter.
 * @param box    area to include, or null for anywhere
 */
public record StreamFilter(Set<String> routes, BoundingBox box) {

    public StreamFilter {
        routes = Set.copyOf(routes);
    }

    public static StreamFilter none() {
        return new StreamFilter(Set.of(), null);
    }

    public boolean isEmpty() {
        return routes.isEmpty() && box == null;
    }

    public boolean matches(Tracked vehicle) {
        return matches(vehicle.route(), vehicle.lat(), vehicle.lon());
    }

    public boolean matches(Where where) {
        return matches(where.route(), where.lat(), where.lon());
    }

    private boolean matches(String route, double lat, double lon) {
        if (!routes.isEmpty() && (route == null || !routes.contains(route))) {
            return false;
        }
        return box == null || box.contains(lon, lat);
    }
}
