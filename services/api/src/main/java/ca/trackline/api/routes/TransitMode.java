package ca.trackline.api.routes;

/**
 * The modes the TTC publishes, mapped from GTFS {@code route_type}.
 *
 * <p>Exposed instead of the raw numeric type so clients do not have to carry a
 * GTFS lookup table, and so an unexpected value becomes an explicit OTHER rather
 * than a number nobody can render.
 */
public enum TransitMode {

    STREETCAR,
    SUBWAY,
    BUS,
    OTHER;

    public static TransitMode fromRouteType(int routeType) {
        return switch (routeType) {
            case 0 -> STREETCAR;
            case 1 -> SUBWAY;
            case 3 -> BUS;
            default -> OTHER;
        };
    }
}
