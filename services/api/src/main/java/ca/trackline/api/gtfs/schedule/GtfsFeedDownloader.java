package ca.trackline.api.gtfs.schedule;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads a GTFS archive to a temporary file.
 *
 * <p>Streamed to disk rather than held in memory, and hashed on the way past so
 * the digest costs nothing extra. The hash is what makes a repeat import a no-op:
 * the feed is republished monthly, so most runs find the archive unchanged.
 */
@Component
public class GtfsFeedDownloader {

    private static final Logger log = LoggerFactory.getLogger(GtfsFeedDownloader.class);

    private final RestClient restClient;
    private final GtfsStaticProperties properties;

    public GtfsFeedDownloader(RestClient gtfsRestClient, GtfsStaticProperties properties) {
        this.restClient = gtfsRestClient;
        this.properties = properties;
    }

    /**
     * @param target file to write to; the caller owns deleting it
     * @return the archive's SHA-256, lowercase hex
     */
    public String downloadTo(GtfsFeedResource resource, Path target) throws IOException {
        MessageDigest digest = sha256();
        long started = System.nanoTime();

        long bytes = restClient.get()
                .uri(resource.downloadUrl())
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new GtfsFormatException(
                                "Download of %s returned %s".formatted(resource.downloadUrl(), response.getStatusCode()));
                    }
                    try (InputStream body = new DigestInputStream(response.getBody(), digest);
                            OutputStream out = Files.newOutputStream(target)) {
                        return copyBounded(body, out, properties.maxArchiveBytes());
                    }
                });

        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.info(
                "Downloaded GTFS archive bytes={} sha256={} durationMs={}",
                bytes,
                sha256,
                (System.nanoTime() - started) / 1_000_000);
        return sha256;
    }

    private static long copyBounded(InputStream source, OutputStream target, long limit) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        int read;

        while ((read = source.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                // Stop paying for a download that is already too large to accept.
                throw new GtfsFormatException("Archive exceeded the %d byte download limit".formatted(limit));
            }
            target.write(buffer, 0, read);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 is required but unavailable", cause);
        }
    }
}
