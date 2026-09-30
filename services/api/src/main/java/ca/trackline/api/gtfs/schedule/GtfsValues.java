package ca.trackline.api.gtfs.schedule;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Coercion of raw GTFS text fields into the values the schema stores.
 *
 * <p>Pure and static on purpose: these are the rules most likely to be wrong in
 * subtle ways, so they are unit-tested directly rather than only through an
 * import.
 */
public final class GtfsValues {

    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private GtfsValues() {
    }

    /** A GTFS field is absent when it is missing, empty, or only whitespace. */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new GtfsFormatException("%s is required but was empty".formatted(field));
        }
        return value.trim();
    }

    /** Optional text, normalised so an absent value is null rather than "". */
    public static String optionalText(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * Converts a GTFS time to seconds after the service day's midnight.
     *
     * <p>GTFS times legitimately exceed 24:00:00 — a trip leaving at 25:15:00 is
     * the 01:15 departure belonging to the previous service day — so this cannot
     * be parsed as a time of day. Hours are unbounded; minutes and seconds are
     * not.
     */
    public static int parseTimeToSeconds(String value, String field) {
        String text = requireText(value, field);

        int first = text.indexOf(':');
        int second = first < 0 ? -1 : text.indexOf(':', first + 1);
        if (first < 0 || second < 0) {
            throw new GtfsFormatException("%s '%s' is not HH:MM:SS".formatted(field, text));
        }

        int hours = parseNonNegative(text.substring(0, first), field, text);
        int minutes = parseNonNegative(text.substring(first + 1, second), field, text);
        int seconds = parseNonNegative(text.substring(second + 1), field, text);

        if (minutes > 59 || seconds > 59) {
            throw new GtfsFormatException("%s '%s' has minutes or seconds out of range".formatted(field, text));
        }
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static int parseNonNegative(String part, String field, String whole) {
        try {
            int parsed = Integer.parseInt(part.trim());
            if (parsed < 0) {
                throw new GtfsFormatException("%s '%s' is negative".formatted(field, whole));
            }
            return parsed;
        } catch (NumberFormatException cause) {
            throw new GtfsFormatException("%s '%s' is not numeric".formatted(field, whole));
        }
    }

    public static LocalDate parseDate(String value, String field) {
        String text = requireText(value, field);
        try {
            return LocalDate.parse(text, GTFS_DATE);
        } catch (RuntimeException cause) {
            throw new GtfsFormatException("%s '%s' is not a yyyyMMdd date".formatted(field, text));
        }
    }

    public static int parseInt(String value, String field) {
        String text = requireText(value, field);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException cause) {
            throw new GtfsFormatException("%s '%s' is not an integer".formatted(field, text));
        }
    }

    /** An optional integer, such as wheelchair_boarding, which defaults when absent. */
    public static int parseIntOrDefault(String value, int fallback, String field) {
        return isBlank(value) ? fallback : parseInt(value, field);
    }

    public static double parseDouble(String value, String field) {
        String text = requireText(value, field);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException cause) {
            throw new GtfsFormatException("%s '%s' is not a number".formatted(field, text));
        }
    }

    /**
     * An optional decimal such as shape_dist_traveled, which the TTC leaves empty
     * on the first stop of many trips. Returned as text so it can be written
     * straight into COPY without a rounding step.
     */
    public static String optionalDecimal(String value, String field) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            Double.parseDouble(text);
        } catch (NumberFormatException cause) {
            throw new GtfsFormatException("%s '%s' is not a number".formatted(field, text));
        }
        return text;
    }

    /** GTFS booleans are the characters '0' and '1'. */
    public static boolean parseBoolean(String value, String field) {
        String text = requireText(value, field);
        return switch (text) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new GtfsFormatException("%s '%s' is not 0 or 1".formatted(field, text));
        };
    }

    /**
     * A six-digit hex colour, or null when absent or malformed.
     *
     * <p>A bad colour must not fail an import: it costs a badge its brand colour,
     * and the schema's format check would otherwise reject the whole transaction.
     */
    public static String optionalHexColour(String value) {
        String text = optionalText(value);
        if (text == null || text.length() != 6) {
            return null;
        }
        for (int index = 0; index < 6; index++) {
            if (Character.digit(text.charAt(index), 16) < 0) {
                return null;
            }
        }
        return text;
    }

    /**
     * Whether Trackline can show live vehicles for a route.
     *
     * <p>Derived rather than read: the TTC publishes realtime for surface routes
     * only, so subway routes (route_type 1) have schedule and alerts but never
     * vehicle positions.
     */
    public static boolean supportsRealtime(int routeType) {
        return routeType != 1;
    }
}
