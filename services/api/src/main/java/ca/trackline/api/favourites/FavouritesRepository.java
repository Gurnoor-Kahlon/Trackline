package ca.trackline.api.favourites;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What each person has saved.
 *
 * <p>Every statement takes the user id and puts it in the WHERE clause. There is no query here that reads or removes a row
 * without it, which is what keeps one person out of another's list: not a check made earlier and trusted, but a
 * condition the data cannot be reached without.
 *
 * <p>Table names are fixed in {@link Kind}, never taken from a caller.
 */
@Repository
public class FavouritesRepository {

    /** The two kinds of thing that can be saved, with the table and the column that names each. */
    enum Kind {
        ROUTE("favourite_routes", "route_id"),
        STOP("favourite_stops", "stop_id");

        private final String table;
        private final String column;

        Kind(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }

    /** One saved id and when it was saved. */
    record Saved(String id, Instant savedAt) {
    }

    private final JdbcTemplate jdbc;

    public FavouritesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Most recent first, then by id so the order is the same each time. */
    List<Saved> list(UUID userId, Kind kind) {
        return jdbc.query(
                "SELECT %s AS id, created_at FROM %s WHERE user_id = ? ORDER BY created_at DESC, %s"
                        .formatted(kind.column, kind.table, kind.column),
                (rows, index) -> new Saved(rows.getString("id"), rows.getTimestamp("created_at").toInstant()),
                userId);
    }

    boolean contains(UUID userId, Kind kind, String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM %s WHERE user_id = ? AND %s = ?)".formatted(kind.table, kind.column),
                Boolean.class, userId, id));
    }

    int count(UUID userId, Kind kind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM %s WHERE user_id = ?".formatted(kind.table), Integer.class, userId);
    }

    /** Saves it if it is not saved already; saving twice is the same as once. */
    void add(UUID userId, Kind kind, String id, Instant now) {
        jdbc.update(
                "INSERT INTO %s (user_id, %s, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                        .formatted(kind.table, kind.column),
                userId, id, Timestamp.from(now));
    }

    /** Removes it if it is saved; removing what is not there is the same as removing it. */
    void remove(UUID userId, Kind kind, String id) {
        jdbc.update(
                "DELETE FROM %s WHERE user_id = ? AND %s = ?".formatted(kind.table, kind.column), userId, id);
    }
}
