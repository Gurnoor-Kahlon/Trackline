package ca.trackline.api.gtfs.schedule.derive;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which stops are subway or light-rail platforms, and what station they
 * belong to.
 *
 * <p>The TTC feed supplies no station hierarchy at all — {@code location_type} and
 * {@code parent_station} are empty for every one of its 9,409 stops — so stations
 * have to be derived. The rule here is deliberately narrow.
 *
 * <p>637 stop names mention a station, but most of those are surface stops
 * standing near one: "Aukland Rd at Kipling GO Station" is a bus stop, not a
 * platform. Grouping on the word "Station" would invent a hierarchy the feed never
 * described and would put bus stops inside subway stations. Only a name that ends
 * in "… Platform" is treated as a platform, and the station is the part of the
 * name up to and including the word "Station".
 */
public final class StationNaming {

    /**
     * A platform stop: the name ends with "Platform" and names a station before it.
     *
     * <p>Group one is the station name. The reluctant quantifier stops at the first
     * "Station", so "Vaughan Metropolitan Centre Station - Subway Platform" yields
     * "Vaughan Metropolitan Centre Station".
     */
    private static final Pattern PLATFORM =
            Pattern.compile("^(.*?\\bStation)\\b.*\\bPlatform$", Pattern.CASE_INSENSITIVE);

    private StationNaming() {
    }

    /**
     * @return the station a platform stop belongs to, or empty when the stop is not
     *         a platform Trackline can attribute to one
     */
    public static Optional<String> stationNameFor(String stopName) {
        if (stopName == null || stopName.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = PLATFORM.matcher(stopName.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String station = matcher.group(1).trim();
        return station.isEmpty() ? Optional.empty() : Optional.of(normalise(station));
    }

    /**
     * Collapses runs of whitespace so names that differ only in spacing group
     * together. Case and punctuation are left alone: they are part of how the name
     * is displayed, and the feed is consistent about them.
     */
    private static String normalise(String name) {
        return name.replaceAll("\\s+", " ");
    }
}
