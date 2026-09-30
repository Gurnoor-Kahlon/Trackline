package ca.trackline.api.gtfs.schedule;

import java.util.List;

import org.apache.commons.csv.CSVRecord;

/**
 * Loads one GTFS file into one table.
 *
 * <p>Split per file so each transformation is small and independently readable:
 * the rules for stop_times have nothing to do with the rules for routes, and a
 * single class covering both would be where they start leaking into each other.
 */
abstract class GtfsTableLoader {

    /** File name inside the archive, for example {@code routes.txt}. */
    abstract String file();

    /** Target table. */
    abstract String table();

    /** Target columns, in the order {@link #writeRow} emits them. */
    abstract List<String> columns();

    /**
     * Transforms one CSV record into one row.
     *
     * <p>Throwing {@link GtfsFormatException} aborts the whole import, which is
     * intended: a required field that will not parse means the feed is not
     * understood, and serving half of it would be worse than keeping the previous
     * network.
     */
    abstract void writeRow(CSVRecord record, long feedVersionId, CopyRowWriter out);

    /**
     * Whether the file must be present. Only calendar_dates.txt is genuinely
     * optional in the TTC feed, but the flag keeps that decision explicit.
     */
    boolean required() {
        return true;
    }

    String copyStatement() {
        return "COPY %s (%s) FROM STDIN WITH (FORMAT csv)".formatted(table(), String.join(", ", columns()));
    }
}
