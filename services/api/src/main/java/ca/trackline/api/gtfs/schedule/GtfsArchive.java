package ca.trackline.api.gtfs.schedule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read access to a downloaded GTFS archive, with the checks that matter for a
 * ZIP from the public internet.
 *
 * <p>The archive is untrusted input. Entry names are rejected if they are
 * absolute or contain a parent-directory segment, so nothing can be written
 * outside a target directory if a future caller extracts rather than streams.
 * Entry count and declared size are capped so a small download cannot expand into
 * an enormous one, and the cap is re-checked while reading because a ZIP's
 * declared size is only a claim.
 */
public final class GtfsArchive implements AutoCloseable {

    private final ZipFile zip;
    private final long maxEntryBytes;
    private final Set<String> entryNames;

    private GtfsArchive(ZipFile zip, long maxEntryBytes, Set<String> entryNames) {
        this.zip = zip;
        this.maxEntryBytes = maxEntryBytes;
        this.entryNames = entryNames;
    }

    public static GtfsArchive open(Path file, int maxEntries, long maxEntryBytes) throws IOException {
        ZipFile zip = new ZipFile(file.toFile());
        try {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > maxEntries) {
                    throw new GtfsFormatException("Archive has more than %d entries".formatted(maxEntries));
                }
                String name = entry.getName();
                if (isUnsafeName(name)) {
                    throw new GtfsFormatException("Archive entry '%s' has an unsafe path".formatted(name));
                }
                // getSize() is -1 when the ZIP does not declare it; the streaming
                // check below is what actually enforces the limit.
                if (entry.getSize() > maxEntryBytes) {
                    throw new GtfsFormatException(
                            "Archive entry '%s' declares %d bytes, over the %d byte limit"
                                    .formatted(name, entry.getSize(), maxEntryBytes));
                }
                if (!entry.isDirectory()) {
                    names.add(name);
                }
            }
            return new GtfsArchive(zip, maxEntryBytes, names);
        } catch (RuntimeException failure) {
            zip.close();
            throw failure;
        }
    }

    private static boolean isUnsafeName(String name) {
        String normalised = name.replace('\\', '/');
        if (normalised.startsWith("/") || normalised.contains(":")) {
            return true;
        }
        for (String segment : normalised.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    public Set<String> entryNames() {
        return Set.copyOf(entryNames);
    }

    public boolean contains(String name) {
        return entryNames.contains(name);
    }

    /**
     * Opens a reader over one entry.
     *
     * <p>UTF-8 with the byte-order mark stripped: the TTC's files carry one, and
     * left in place it becomes part of the first column's name and every lookup
     * of that column fails.
     */
    public BufferedReader reader(String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new GtfsFormatException("Archive is missing required file '%s'".formatted(name));
        }
        try {
            InputStream raw = new BoundedInputStream(zip.getInputStream(entry), maxEntryBytes, name);
            return new BufferedReader(new InputStreamReader(new BomStrippingInputStream(raw), StandardCharsets.UTF_8), 1 << 16);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** Fails once an entry produces more bytes than the archive was allowed. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private final long limit;
        private final String name;
        private long read;

        private BoundedInputStream(InputStream delegate, long limit, String name) {
            this.delegate = delegate;
            this.limit = limit;
            this.name = name;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(long bytes) {
            read += bytes;
            if (read > limit) {
                throw new GtfsFormatException(
                        "Archive entry '%s' expanded past the %d byte limit".formatted(name, limit));
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** Drops a UTF-8 byte-order mark if the stream starts with one. */
    private static final class BomStrippingInputStream extends InputStream {

        private static final int[] BOM = {0xEF, 0xBB, 0xBF};

        private final InputStream delegate;
        private int[] buffered = new int[0];
        private int bufferedIndex;
        private boolean checked;

        private BomStrippingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        private void checkBom() throws IOException {
            if (checked) {
                return;
            }
            checked = true;

            int[] head = new int[3];
            int count = 0;
            while (count < 3) {
                int value = delegate.read();
                if (value < 0) {
                    break;
                }
                head[count++] = value;
            }
            if (count == 3 && head[0] == BOM[0] && head[1] == BOM[1] && head[2] == BOM[2]) {
                return;
            }
            buffered = new int[count];
            System.arraycopy(head, 0, buffered, 0, count);
        }

        @Override
        public int read() throws IOException {
            checkBom();
            if (bufferedIndex < buffered.length) {
                return buffered[bufferedIndex++];
            }
            return delegate.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            checkBom();
            if (length == 0) {
                return 0;
            }
            if (bufferedIndex < buffered.length) {
                int written = 0;
                while (written < length && bufferedIndex < buffered.length) {
                    target[offset + written++] = (byte) buffered[bufferedIndex++];
                }
                return written;
            }
            return delegate.read(target, offset, length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
