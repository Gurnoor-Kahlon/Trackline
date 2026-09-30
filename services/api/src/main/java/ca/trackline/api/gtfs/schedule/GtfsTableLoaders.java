package ca.trackline.api.gtfs.schedule;

import java.util.List;

import org.apache.commons.csv.CSVRecord;

/**
 * The loaders, in the order they must run.
 *
 * <p>Order is a foreign-key constraint, not a preference: trips reference routes
 * and services, and stop_times reference trips and stops.
 */
final class GtfsTableLoaders {

    private GtfsTableLoaders() {
    }

    static List<GtfsTableLoader> inDependencyOrder() {
        return List.of(
                new RouteLoader(),
                new StopLoader(),
                new CalendarServiceLoader(),
                new CalendarExceptionLoader(),
                new TripLoader(),
                new ShapePointLoader(),
                new StopTimeLoader());
    }

    static final class RouteLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "routes.txt";
        }

        @Override
        String table() {
            return "routes";
        }

        @Override
        List<String> columns() {
            return List.of(
                    "route_id", "feed_version_id", "short_name", "long_name",
                    "route_type", "color", "text_color", "supports_realtime");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            int routeType = GtfsValues.parseInt(record.get("route_type"), "route_type");
            out.writeRow(
                    GtfsValues.requireText(record.get("route_id"), "route_id"),
                    feedVersionId,
                    GtfsValues.requireText(record.get("route_short_name"), "route_short_name"),
                    GtfsValues.requireText(record.get("route_long_name"), "route_long_name"),
                    routeType,
                    GtfsValues.optionalHexColour(record.get("route_color")),
                    GtfsValues.optionalHexColour(record.get("route_text_color")),
                    GtfsValues.supportsRealtime(routeType));
        }
    }

    static final class StopLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "stops.txt";
        }

        @Override
        String table() {
            return "stops";
        }

        @Override
        List<String> columns() {
            return List.of("stop_id", "feed_version_id", "code", "name", "location", "wheelchair_boarding");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            double lat = GtfsValues.parseDouble(record.get("stop_lat"), "stop_lat");
            double lon = GtfsValues.parseDouble(record.get("stop_lon"), "stop_lon");

            out.writeRow(
                    GtfsValues.requireText(record.get("stop_id"), "stop_id"),
                    feedVersionId,
                    GtfsValues.optionalText(record.get("stop_code")),
                    GtfsValues.requireText(record.get("stop_name"), "stop_name"),
                    // COPY into a geography column accepts EWKT, so the point is
                    // built here rather than in a second pass over the table.
                    "SRID=4326;POINT(%s %s)".formatted(lon, lat),
                    // Absent means "not stated" in GTFS, which is 0. Never guessed.
                    GtfsValues.parseIntOrDefault(record.get("wheelchair_boarding"), 0, "wheelchair_boarding"));
        }
    }

    static final class CalendarServiceLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "calendar.txt";
        }

        @Override
        String table() {
            return "calendar_services";
        }

        @Override
        List<String> columns() {
            return List.of(
                    "service_id", "feed_version_id", "monday", "tuesday", "wednesday",
                    "thursday", "friday", "saturday", "sunday", "start_date", "end_date");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            out.writeRow(
                    GtfsValues.requireText(record.get("service_id"), "service_id"),
                    feedVersionId,
                    GtfsValues.parseBoolean(record.get("monday"), "monday"),
                    GtfsValues.parseBoolean(record.get("tuesday"), "tuesday"),
                    GtfsValues.parseBoolean(record.get("wednesday"), "wednesday"),
                    GtfsValues.parseBoolean(record.get("thursday"), "thursday"),
                    GtfsValues.parseBoolean(record.get("friday"), "friday"),
                    GtfsValues.parseBoolean(record.get("saturday"), "saturday"),
                    GtfsValues.parseBoolean(record.get("sunday"), "sunday"),
                    GtfsValues.parseDate(record.get("start_date"), "start_date"),
                    GtfsValues.parseDate(record.get("end_date"), "end_date"));
        }
    }

    static final class CalendarExceptionLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "calendar_dates.txt";
        }

        @Override
        String table() {
            return "calendar_exceptions";
        }

        @Override
        List<String> columns() {
            return List.of("service_id", "exception_date", "exception_type");
        }

        @Override
        boolean required() {
            return false;
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            out.writeRow(
                    GtfsValues.requireText(record.get("service_id"), "service_id"),
                    GtfsValues.parseDate(record.get("date"), "date"),
                    GtfsValues.parseInt(record.get("exception_type"), "exception_type"));
        }
    }

    static final class TripLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "trips.txt";
        }

        @Override
        String table() {
            return "trips";
        }

        @Override
        List<String> columns() {
            return List.of(
                    "trip_id", "feed_version_id", "route_id", "service_id",
                    "direction_id", "headsign", "shape_id", "block_id");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            out.writeRow(
                    GtfsValues.requireText(record.get("trip_id"), "trip_id"),
                    feedVersionId,
                    GtfsValues.requireText(record.get("route_id"), "route_id"),
                    GtfsValues.requireText(record.get("service_id"), "service_id"),
                    GtfsValues.parseInt(record.get("direction_id"), "direction_id"),
                    GtfsValues.optionalText(record.get("trip_headsign")),
                    GtfsValues.optionalText(record.get("shape_id")),
                    GtfsValues.optionalText(record.get("block_id")));
        }
    }

    static final class ShapePointLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "shapes.txt";
        }

        @Override
        String table() {
            return "shape_points";
        }

        @Override
        List<String> columns() {
            return List.of("shape_id", "shape_pt_sequence", "feed_version_id", "lat", "lon", "shape_dist_traveled");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            out.writeRow(
                    GtfsValues.requireText(record.get("shape_id"), "shape_id"),
                    GtfsValues.parseInt(record.get("shape_pt_sequence"), "shape_pt_sequence"),
                    feedVersionId,
                    GtfsValues.parseDouble(record.get("shape_pt_lat"), "shape_pt_lat"),
                    GtfsValues.parseDouble(record.get("shape_pt_lon"), "shape_pt_lon"),
                    GtfsValues.optionalDecimal(record.get("shape_dist_traveled"), "shape_dist_traveled"));
        }
    }

    static final class StopTimeLoader extends GtfsTableLoader {

        @Override
        String file() {
            return "stop_times.txt";
        }

        @Override
        String table() {
            return "stop_times";
        }

        @Override
        List<String> columns() {
            return List.of(
                    "trip_id", "stop_sequence", "stop_id",
                    "arrival_seconds", "departure_seconds", "shape_dist_traveled");
        }

        @Override
        void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out) {
            out.writeRow(
                    GtfsValues.requireText(record.get("trip_id"), "trip_id"),
                    GtfsValues.parseInt(record.get("stop_sequence"), "stop_sequence"),
                    GtfsValues.requireText(record.get("stop_id"), "stop_id"),
                    // Seconds after the service day's midnight, because GTFS times
                    // run past 24:00:00 for trips continuing into the next day.
                    GtfsValues.parseTimeToSeconds(record.get("arrival_time"), "arrival_time"),
                    GtfsValues.parseTimeToSeconds(record.get("departure_time"), "departure_time"),
                    GtfsValues.optionalDecimal(record.get("shape_dist_traveled"), "shape_dist_traveled"));
        }
    }
}
