package ca.trackline.api.persistence.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ca.trackline.api.alerts.AlertCause;
import ca.trackline.api.alerts.AlertDtos.AffectedRoute;
import ca.trackline.api.alerts.AlertDtos.AffectedStop;
import ca.trackline.api.alerts.AlertDtos.AlertStatus;
import ca.trackline.api.alerts.AlertDtos.AlertView;
import ca.trackline.api.alerts.AlertEffect;
import ca.trackline.api.routes.TransitMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read queries over the stored alerts.
 *
 * <p>An alert is read in two steps, the alerts of a page and then everything they
 * name, so a page of alerts naming dozens of routes each costs two queries and not
 * one per alert.
 */
@Repository
public class AlertQueryRepository {

    /** Orders a route list the way the rest of the API does: numbered routes by number, then the rest. */
    private static final String ROUTE_ORDER = """
            CASE WHEN r.short_name ~ '^[0-9]+$' THEN 0 ELSE 1 END,
            CASE WHEN r.short_name ~ '^[0-9]+$' THEN r.short_name::int END,
            r.short_name, e.route_id
            """;

    private final JdbcTemplate jdbc;

    public AlertQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Alerts matching the filters, newest first.
     *
     * @param active true for those still in the feed, false for those that have left it
     * @param route  only alerts naming this route, or null
     * @param stop   only alerts naming this stop, or null
     */
    public List<AlertView> find(boolean active, String route, String stop, int page, int size) {
        Where where = where(active, route, stop);
        List<Object> arguments = new ArrayList<>(where.arguments());
        arguments.add(size);
        arguments.add((long) page * size);

        List<Row> rows = jdbc.query(
                """
                SELECT a.id, a.source_alert_id, a.cause, a.effect, a.header, a.description, a.url,
                       a.first_seen_at, a.last_seen_at, a.last_changed_at, a.resolved_at
                FROM service_alerts a
                WHERE %s
                ORDER BY a.first_seen_at DESC, a.id DESC
                LIMIT ? OFFSET ?
                """.formatted(where.sql()),
                (results, index) -> row(results),
                arguments.toArray());
        return assemble(rows);
    }

    public long count(boolean active, String route, String stop) {
        Where where = where(active, route, stop);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM service_alerts a WHERE " + where.sql(), Long.class, where.arguments().toArray());
        return total == null ? 0 : total;
    }

    public Optional<AlertView> findById(long id) {
        List<Row> rows = jdbc.query(
                """
                SELECT a.id, a.source_alert_id, a.cause, a.effect, a.header, a.description, a.url,
                       a.first_seen_at, a.last_seen_at, a.last_changed_at, a.resolved_at
                FROM service_alerts a WHERE a.id = ?
                """,
                (results, index) -> row(results),
                id);
        return assemble(rows).stream().findFirst();
    }

    /**
     * When the alerts feed was last read successfully, by the publisher's own
     * timestamp, or empty when it never has been.
     *
     * <p>Suspect and failed cycles are not counted: they changed nothing, so the stored
     * alerts are only as current as the last cycle that was applied or confirmed.
     */
    public Optional<Instant> lastAppliedFeedTimestamp() {
        List<Instant> found = jdbc.query(
                """
                SELECT max(feed_header_timestamp) AS latest FROM ingestion_runs
                WHERE feed = 'ALERTS' AND status IN ('SUCCESS', 'NO_CHANGE')
                """,
                (results, index) -> {
                    Timestamp latest = results.getTimestamp("latest");
                    return latest == null ? null : latest.toInstant();
                });
        return found.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private record Where(String sql, List<Object> arguments) {
    }

    private static Where where(boolean active, String route, String stop) {
        StringBuilder sql = new StringBuilder(active ? "a.resolved_at IS NULL" : "a.resolved_at IS NOT NULL");
        List<Object> arguments = new ArrayList<>();
        if (route != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM service_alert_entities e WHERE e.alert_id = a.id AND e.route_id = ?)");
            arguments.add(route);
        }
        if (stop != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM service_alert_entities e WHERE e.alert_id = a.id AND e.stop_id = ?)");
            arguments.add(stop);
        }
        return new Where(sql.toString(), arguments);
    }

    private record Row(
            long id,
            String sourceAlertId,
            Integer cause,
            Integer effect,
            String header,
            String description,
            String url,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant lastChangedAt,
            Instant resolvedAt) {
    }

    private static Row row(ResultSet results) throws SQLException {
        Timestamp resolved = results.getTimestamp("resolved_at");
        return new Row(
                results.getLong("id"),
                results.getString("source_alert_id"),
                results.getObject("cause", Integer.class),
                results.getObject("effect", Integer.class),
                results.getString("header"),
                results.getString("description"),
                results.getString("url"),
                results.getTimestamp("first_seen_at").toInstant(),
                results.getTimestamp("last_seen_at").toInstant(),
                results.getTimestamp("last_changed_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private List<AlertView> assemble(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<AffectedRoute>> routes = new LinkedHashMap<>();
        Map<Long, List<AffectedStop>> stops = new LinkedHashMap<>();
        String placeholders = String.join(", ", rows.stream().map(row -> "?").toList());

        jdbc.query(
                """
                SELECT e.alert_id, e.route_id, e.stop_id,
                       r.route_id IS NOT NULL AS route_known, r.short_name, r.long_name, r.route_type, r.color, r.text_color,
                       s.stop_id IS NOT NULL AS stop_known, s.name AS stop_name
                FROM service_alert_entities e
                LEFT JOIN routes r ON r.route_id = e.route_id
                LEFT JOIN stops s ON s.stop_id = e.stop_id
                WHERE e.alert_id IN (%s)
                ORDER BY e.alert_id, %s, e.stop_id
                """.formatted(placeholders, ROUTE_ORDER),
                results -> {
                    long alertId = results.getLong("alert_id");
                    String routeId = results.getString("route_id");
                    if (routeId != null) {
                        List<AffectedRoute> list = routes.computeIfAbsent(alertId, key -> new ArrayList<>());
                        // An alert naming a route at several stops lists the route once.
                        if (list.stream().noneMatch(existing -> existing.routeId().equals(routeId))) {
                            boolean known = results.getBoolean("route_known");
                            list.add(new AffectedRoute(
                                    routeId,
                                    known,
                                    results.getString("short_name"),
                                    results.getString("long_name"),
                                    known ? TransitMode.fromRouteType(results.getInt("route_type")) : null,
                                    results.getString("color"),
                                    results.getString("text_color")));
                        }
                    }
                    String stopId = results.getString("stop_id");
                    if (stopId != null) {
                        List<AffectedStop> list = stops.computeIfAbsent(alertId, key -> new ArrayList<>());
                        if (list.stream().noneMatch(existing -> existing.stopId().equals(stopId))) {
                            list.add(new AffectedStop(
                                    stopId, results.getBoolean("stop_known"), results.getString("stop_name")));
                        }
                    }
                },
                rows.stream().map(Row::id).toArray());

        return rows.stream()
                .map(row -> new AlertView(
                        row.id(),
                        row.sourceAlertId(),
                        row.resolvedAt() == null ? AlertStatus.ACTIVE : AlertStatus.RESOLVED,
                        AlertCause.fromNumber(row.cause()),
                        AlertEffect.fromNumber(row.effect()),
                        row.header(),
                        row.description(),
                        row.url(),
                        row.firstSeenAt(),
                        row.lastSeenAt(),
                        row.lastChangedAt(),
                        row.resolvedAt(),
                        routes.getOrDefault(row.id(), List.of()),
                        stops.getOrDefault(row.id(), List.of())))
                .toList();
    }
}
