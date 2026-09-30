package ca.trackline.api.gtfs.schedule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The archive is downloaded from the public internet, so it is untrusted input.
 * These cover the ways a ZIP can be hostile or malformed.
 */
class GtfsArchiveTest {

    private static final int MAX_ENTRIES = 16;
    private static final long MAX_ENTRY_BYTES = 4096;

    @TempDir
    Path tempDir;

    @Test
    void readsEntriesFromAWellFormedArchive() throws IOException {
        Path archive = zip(Map.entry("routes.txt", "route_id,route_short_name\n501,501\n"));

        try (GtfsArchive opened = GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES)) {
            assertThat(opened.entryNames()).containsExactly("routes.txt");
            assertThat(opened.contains("routes.txt")).isTrue();
            assertThat(opened.contains("stops.txt")).isFalse();
        }
    }

    @Test
    void stripsTheByteOrderMarkTheTtcFilesCarry() throws IOException {
        // Left in place the mark becomes part of the first column's name, and
        // every lookup of that column fails with a confusing missing-header error.
        Path archive = zipBytes("routes.txt",
                concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                        "route_id,route_short_name\n501,Queen\n".getBytes(StandardCharsets.UTF_8)));

        try (GtfsArchive opened = GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES);
                BufferedReader reader = opened.reader("routes.txt");
                CSVParser parser = csv(reader)) {

            CSVRecord record = parser.iterator().next();
            assertThat(record.get("route_id")).isEqualTo("501");
        }
    }

    @Test
    void preservesQuotedFieldsContainingCommasAndQuotes() throws IOException {
        // No current TTC stop name contains a comma, but the format permits it and
        // a hand-rolled splitter would silently shift every later column.
        Path archive = zip(Map.entry(
                "stops.txt",
                "stop_id,stop_name\n1,\"Queen St W, at Bathurst\"\n2,\"The \"\"Ex\"\" Loop\"\n"));

        try (GtfsArchive opened = GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES);
                BufferedReader reader = opened.reader("stops.txt");
                CSVParser parser = csv(reader)) {

            List<CSVRecord> records = parser.getRecords();
            assertThat(records.get(0).get("stop_name")).isEqualTo("Queen St W, at Bathurst");
            assertThat(records.get(1).get("stop_name")).isEqualTo("The \"Ex\" Loop");
        }
    }

    @Test
    void rejectsAnEntryThatWouldEscapeTheTargetDirectory() throws IOException {
        Path archive = zip(Map.entry("../../etc/passwd", "nope"));

        assertThatThrownBy(() -> GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("unsafe path");
    }

    @Test
    void rejectsAnAbsoluteEntryPath() throws IOException {
        Path archive = zip(Map.entry("/routes.txt", "nope"));

        assertThatThrownBy(() -> GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("unsafe path");
    }

    @Test
    void rejectsAnArchiveWithTooManyEntries() throws IOException {
        Path archive = tempDir.resolve("many.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index <= MAX_ENTRIES; index++) {
                out.putNextEntry(new ZipEntry("file" + index + ".txt"));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        assertThatThrownBy(() -> GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("more than");
    }

    @Test
    void rejectsAnEntryDeclaringMoreBytesThanAllowed() throws IOException {
        // A zip bomb compresses to almost nothing, so this is caught from the
        // central directory before a single byte is decompressed. The streaming
        // guard in GtfsArchive backs this up for archives that do not declare a
        // size at all, which is not reachable through ZipFile.
        Path archive = zipBytes("stop_times.txt", new byte[(int) MAX_ENTRY_BYTES * 4]);

        assertThatThrownBy(() -> GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("over the %d byte limit".formatted(MAX_ENTRY_BYTES));
    }

    @Test
    void namingAMissingFileFailsClearly() throws IOException {
        Path archive = zip(Map.entry("routes.txt", "route_id\n501\n"));

        try (GtfsArchive opened = GtfsArchive.open(archive, MAX_ENTRIES, MAX_ENTRY_BYTES)) {
            assertThatThrownBy(() -> opened.reader("stop_times.txt"))
                    .isInstanceOf(GtfsFormatException.class)
                    .hasMessageContaining("stop_times.txt");
        }
    }

    private static CSVParser csv(BufferedReader reader) throws IOException {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader);
    }

    @SafeVarargs
    private Path zip(Map.Entry<String, String>... entries) throws IOException {
        Path archive = tempDir.resolve("archive-" + entries[0].getKey().hashCode() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return archive;
    }

    private Path zipBytes(String name, byte[] content) throws IOException {
        Path archive = tempDir.resolve("bytes-" + name.hashCode() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry(name));
            OutputStream stream = out;
            stream.write(content);
            out.closeEntry();
        }
        return archive;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
