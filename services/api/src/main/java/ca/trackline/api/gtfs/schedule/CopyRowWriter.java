package ca.trackline.api.gtfs.schedule;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import org.postgresql.copy.CopyIn;

/**
 * Writes rows into an open {@code COPY ... FROM STDIN (FORMAT csv)} stream.
 *
 * <p>COPY rather than batched inserts because stop_times alone is 4.36 million
 * rows; the same load through JDBC batches takes long enough to make importing a
 * chore, which is how schedule data ends up stale.
 *
 * <p>Rows are buffered and flushed in large blocks: one network write per row
 * would make the round trips, not the parsing, the bottleneck.
 */
final class CopyRowWriter implements AutoCloseable {

    private final CopyIn copyIn;
    private final int flushThreshold;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
    private final StringBuilder row = new StringBuilder(256);
    private long rowCount;

    CopyRowWriter(CopyIn copyIn, int flushThreshold) {
        this.copyIn = copyIn;
        this.flushThreshold = flushThreshold;
    }

    /**
     * Appends one row. A null value is written as an unquoted empty field, which
     * COPY's CSV format reads as SQL NULL; an empty string would be written as a
     * quoted pair and is therefore distinguishable.
     */
    void writeRow(Object... values) {
        row.setLength(0);
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                row.append(',');
            }
            append(values[index]);
        }
        row.append('\n');
        buffer.writeBytes(row.toString().getBytes(StandardCharsets.UTF_8));
        rowCount++;

        if (buffer.size() >= flushThreshold) {
            flush();
        }
    }

    private void append(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            row.append(value);
            return;
        }

        String text = value.toString();
        if (needsQuoting(text)) {
            row.append('"');
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character == '"') {
                    row.append('"');
                }
                row.append(character);
            }
            row.append('"');
        } else {
            row.append(text);
        }
    }

    private static boolean needsQuoting(String text) {
        if (text.isEmpty()) {
            // Quoted so it is loaded as an empty string rather than NULL.
            return true;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == ',' || character == '"' || character == '\n' || character == '\r') {
                return true;
            }
        }
        return false;
    }

    private void flush() {
        if (buffer.size() == 0) {
            return;
        }
        try {
            byte[] block = buffer.toByteArray();
            copyIn.writeToCopy(block, 0, block.length);
            buffer.reset();
        } catch (SQLException cause) {
            throw new IllegalStateException("Failed writing to COPY stream", cause);
        }
    }

    long rowCount() {
        return rowCount;
    }

    /** Flushes what is left and ends the COPY, returning the server's row count. */
    @Override
    public void close() {
        try {
            flush();
            copyIn.endCopy();
        } catch (SQLException cause) {
            throw new IllegalStateException("Failed completing COPY stream", cause);
        }
    }

    /** Aborts without committing the rows written so far. */
    void cancel() {
        try {
            if (copyIn.isActive()) {
                copyIn.cancelCopy();
            }
        } catch (SQLException ignored) {
            // The transaction is being rolled back anyway; the original failure
            // is the one worth reporting.
        }
    }

}
