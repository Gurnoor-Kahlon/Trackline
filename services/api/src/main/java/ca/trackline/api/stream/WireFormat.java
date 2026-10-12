package ca.trackline.api.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The JSON the streams put on the wire. This is the contract the frontend is built
 * against, so it lives in one place.
 *
 * <p>Vehicles are positional arrays, not objects. A thousand vehicles per event with
 * eight named keys each would spend most of the payload repeating the names; an
 * array with a fixed order, and a snapshot that states the order, is a third of the
 * size and still self-describing. Coordinates are rounded to six decimals, about
 * ten centimetres, which is finer than the GPS they came from.
 *
 * <p>Every event carries {@code serverTime} and a {@code feed} block with the age of
 * the data at the moment of sending. A client computes how old its picture is from
 * those, not from its own clock, so a laptop with the wrong time still reports the
 * right age.
 */
final class WireFormat {

    /** The order of every vehicle array. A snapshot restates it so a mismatch is detectable. */
    static final List<String> VEHICLE_FIELDS =
            List.of("id", "route", "lat", "lon", "bearing", "speed", "occupancy", "timestamp");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireFormat() {
    }

    static String snapshot(Instant now, Instant feedTimestamp, Collection<Tracked> vehicles) {
        Map<String, Object> body = envelope(now, feedTimestamp);
        body.put("fields", VEHICLE_FIELDS);
        body.put("vehicles", tuples(vehicles));
        return write(body);
    }

    static String delta(Instant now, Instant feedTimestamp, List<Tracked> changed, List<String> removed) {
        Map<String, Object> body = envelope(now, feedTimestamp);
        body.put("changed", tuples(changed));
        body.put("removed", removed);
        return write(body);
    }

    static String heartbeat(Instant now, Instant feedTimestamp, long latestEventId) {
        Map<String, Object> body = envelope(now, feedTimestamp);
        body.put("latestEventId", latestEventId);
        return write(body);
    }

    /**
     * Counts are of what is tracked right now. {@code activeAlerts} is null, not
     * zero, until alerts have been ingested at least once: zero would be a
     * measurement Trackline has not made.
     */
    static String network(
            Instant now, Instant feedTimestamp, int vehiclesTracked, int routesReporting, Integer activeAlerts) {
        Map<String, Object> body = envelope(now, feedTimestamp);
        body.put("vehiclesTracked", vehiclesTracked);
        body.put("routesReporting", routesReporting);
        body.put("activeAlerts", activeAlerts);
        return write(body);
    }

    private static Map<String, Object> envelope(Instant now, Instant feedTimestamp) {
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("timestamp", feedTimestamp == null ? null : feedTimestamp.toString());
        feed.put("ageSeconds", feedTimestamp == null ? null : Math.max(0, Duration.between(feedTimestamp, now).toSeconds()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverTime", now.toString());
        body.put("feed", feed);
        return body;
    }

    private static List<Object[]> tuples(Collection<Tracked> vehicles) {
        List<Object[]> rows = new ArrayList<>(vehicles.size());
        for (Tracked vehicle : vehicles) {
            rows.add(tuple(vehicle));
        }
        return rows;
    }

    static Object[] tuple(Tracked vehicle) {
        return new Object[] {
            vehicle.id(),
            vehicle.route(),
            round(vehicle.lat(), 1e6),
            round(vehicle.lon(), 1e6),
            vehicle.bearing() == null ? null : Math.round(vehicle.bearing()),
            vehicle.speed() == null ? null : round(vehicle.speed(), 10),
            vehicle.occupancy(),
            vehicle.timestamp()
        };
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }

    private static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException cause) {
            // Plain maps, lists and numbers cannot fail to serialise; reaching here
            // means a bug, and it must not be mistaken for a client problem.
            throw new IllegalStateException("Could not serialise a stream event", cause);
        }
    }
}
