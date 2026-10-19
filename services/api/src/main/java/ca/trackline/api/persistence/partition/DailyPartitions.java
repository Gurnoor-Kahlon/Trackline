package ca.trackline.api.persistence.partition;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, lists and drops the daily partitions of a range-partitioned history table.
 *
 * <p>Retention that deletes rows costs a vacuum for every row; dropping a partition costs nothing, which
 * is why the large history tables are partitioned by day. What this class adds is the knowledge of which
 * partitions have ended: it reads each partition's bounds from the catalogue instead of trusting its
 * name, because a name says which day it was made for and the bounds say what it holds.
 *
 * <p>Table names are interpolated into DDL, which cannot take parameters, so every name is checked
 * against a strict pattern first. Partition names come from the catalogue, never from a caller.
 */
@Component
public class DailyPartitions {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /** Reads the upper bound out of how the catalogue prints a partition's range. */
    private static final String UPPER_BOUND = "TO \\('([^']+)'\\)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DailyPartitions(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * A partition of a table, and the instant from which it no longer holds anything.
     *
     * @param upperBound exclusive upper bound of the range it covers
     */
    public record Partition(String name, Instant upperBound) {
    }

    /**
     * Makes sure the partition for a UTC day exists. The bounds are written with an explicit offset, so they
     * mean the same thing whatever time zone the database session is in.
     */
    public void ensure(String parent, LocalDate day) {
        requireIdentifier(parent);
        String name = "%s_%s".formatted(parent, day.toString().replace('-', '_'));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS "%s" PARTITION OF "%s"
                FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')
                """.formatted(name, parent, day, day.plusDays(1)));
    }

    /** Every partition of a table that has a bounded range, oldest name first. A default partition is not one. */
    public List<Partition> list(String parent) {
        requireIdentifier(parent);
        List<Partition> partitions = new ArrayList<>();
        jdbc.query(
                """
                SELECT child.relname AS name,
                       (regexp_match(pg_get_expr(child.relpartbound, child.oid), ?))[1]::timestamptz AS upper_bound
                FROM pg_inherits link
                JOIN pg_class child ON child.oid = link.inhrelid
                JOIN pg_class owner ON owner.oid = link.inhparent
                JOIN pg_namespace namespace ON namespace.oid = owner.relnamespace
                WHERE owner.relname = ? AND namespace.nspname = current_schema()
                ORDER BY child.relname
                """,
                rows -> {
                    Timestamp bound = rows.getTimestamp("upper_bound");
                    if (bound != null) {
                        partitions.add(new Partition(rows.getString("name"), bound.toInstant()));
                    }
                },
                UPPER_BOUND, parent);
        return partitions;
    }

    /**
     * Drops one partition, giving up rather than waiting if something holds a lock on it.
     *
     * <p>Dropping needs an exclusive lock, and while it waits every later insert into the table queues
     * behind it. Ingestion writes constantly, so a long wait would stall it; a short one that fails is
     * retried at the next run, when nothing is likely to be holding the lock.
     */
    public void drop(String parent, Partition partition, Duration lockTimeout) {
        requireIdentifier(parent);
        requireIdentifier(partition.name());
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '%d ms'".formatted(lockTimeout.toMillis()));
            jdbc.execute("DROP TABLE \"%s\"".formatted(partition.name()));
        });
    }

    private static void requireIdentifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain table name: " + name);
        }
    }
}
