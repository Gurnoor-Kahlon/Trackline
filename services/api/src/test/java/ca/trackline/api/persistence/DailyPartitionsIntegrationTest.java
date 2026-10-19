package ca.trackline.api.persistence;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;

import ca.trackline.api.persistence.partition.DailyPartitions;
import ca.trackline.api.persistence.partition.DailyPartitions.Partition;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Daily partitions against a real database: which range each holds, which have ended, and that dropping one
 * gives up instead of holding up the writers.
 *
 * <p>Works on a scratch table with a default partition, as retention must never mistake that for a day.
 */
class DailyPartitionsIntegrationTest extends PostgisIntegrationTest {

    private static final String TABLE = "history_scratch";

    @Autowired
    private DailyPartitions partitions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createScratchTable() {
        dropScratchTable();
        jdbc.execute("CREATE TABLE " + TABLE + " (at timestamptz NOT NULL, label text) PARTITION BY RANGE (at)");
        jdbc.execute("CREATE TABLE " + TABLE + "_default PARTITION OF " + TABLE + " DEFAULT");
    }

    @AfterEach
    void dropScratchTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE + " CASCADE");
    }

    private String partitionHolding(String label) {
        return jdbc.queryForObject("SELECT tableoid::regclass::text FROM " + TABLE + " WHERE label = ?", String.class, label);
    }

    private void insert(String at, String label) {
        jdbc.update("INSERT INTO " + TABLE + " (at, label) VALUES (?::timestamptz, ?)", at, label);
    }

    @Test
    void ensuringAPartitionCreatesItOnceAndAgainIsHarmless() {
        LocalDate day = LocalDate.of(2026, 1, 5);

        partitions.ensure(TABLE, day);
        partitions.ensure(TABLE, day);

        assertThat(partitions.list(TABLE)).extracting(Partition::name).containsExactly("history_scratch_2026_01_05");
    }

    @Test
    void aPartitionHoldsExactlyOneUtcDayWhateverTheSessionTimeZone() {
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 5));
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 6));

        // A session in Toronto would read a bare date as local midnight; the bounds carry their own offset.
        jdbc.execute("SET TIME ZONE 'America/Toronto'");
        try {
            insert("2026-01-05T23:59:59.999Z", "last of the fifth");
            insert("2026-01-06T00:00:00Z", "first of the sixth");
        } finally {
            jdbc.execute("RESET TIME ZONE");
        }

        assertThat(partitionHolding("last of the fifth")).isEqualTo("history_scratch_2026_01_05");
        assertThat(partitionHolding("first of the sixth")).isEqualTo("history_scratch_2026_01_06");
    }

    @Test
    void listingReportsEachPartitionsUpperBoundAndLeavesOutTheDefault() {
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 5));
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 7));

        List<Partition> listed = partitions.list(TABLE);

        assertThat(listed).extracting(Partition::name)
                .containsExactly("history_scratch_2026_01_05", "history_scratch_2026_01_07");
        assertThat(listed).extracting(Partition::upperBound)
                .containsExactly(Instant.parse("2026-01-06T00:00:00Z"), Instant.parse("2026-01-08T00:00:00Z"));
    }

    @Test
    void aTableWithNoDailyPartitionsListsNone() {
        assertThat(partitions.list(TABLE)).isEmpty();
    }

    @Test
    void droppingAPartitionRemovesItAndItsRowsAndNothingElse() {
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 5));
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 6));
        insert("2026-01-05T10:00:00Z", "old");
        insert("2026-01-06T10:00:00Z", "kept");

        Partition old = partitions.list(TABLE).getFirst();
        partitions.drop(TABLE, old, Duration.ofSeconds(2));

        assertThat(partitions.list(TABLE)).extracting(Partition::name).containsExactly("history_scratch_2026_01_06");
        assertThat(jdbc.queryForList("SELECT label FROM " + TABLE, String.class)).containsExactly("kept");
    }

    @Test
    void droppingGivesUpAtOnceWhenSomethingHoldsALockOnThePartition() throws Exception {
        partitions.ensure(TABLE, LocalDate.of(2026, 1, 5));
        Partition partition = partitions.list(TABLE).getFirst();

        // Another session reading the partition, as a long query would.
        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            try (var statement = reader.createStatement()) {
                statement.execute("LOCK TABLE " + partition.name() + " IN ACCESS SHARE MODE");

                long started = System.nanoTime();
                assertThatThrownBy(() -> partitions.drop(TABLE, partition, Duration.ofMillis(300)))
                        .hasMessageContaining("lock timeout");
                long waitedMillis = (System.nanoTime() - started) / 1_000_000;

                // It waited about as long as it was told to, not until the reader finished.
                assertThat(waitedMillis).isLessThan(5_000);
            } finally {
                reader.rollback();
            }
        }

        // And nothing was lost: it is still there, and goes once the lock is released.
        assertThat(partitions.list(TABLE)).hasSize(1);
        partitions.drop(TABLE, partition, Duration.ofSeconds(2));
        assertThat(partitions.list(TABLE)).isEmpty();
    }

    @Test
    void aNameThatIsNotAPlainTableNameIsRefusedBeforeAnythingIsRun() {
        assertThatThrownBy(() -> partitions.ensure("x; DROP TABLE routes", LocalDate.of(2026, 1, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> partitions.list("Routes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> partitions.drop(TABLE, new Partition("routes\"; --", Instant.EPOCH), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'routes'", Integer.class))
                .isEqualTo(1);
    }
}
