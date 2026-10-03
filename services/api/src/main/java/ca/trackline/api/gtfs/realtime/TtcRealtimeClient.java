package ca.trackline.api.gtfs.realtime;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches a realtime feed.
 *
 * <p>The only place that talks to the publisher. Everything downstream works from
 * bytes, so a change of source — a different URL, a cached mirror, a recorded
 * payload in a test — touches nothing else.
 */
@Component
public class TtcRealtimeClient {

    private final RestClient restClient;
    private final RealtimeProperties properties;

    public TtcRealtimeClient(RestClient realtimeRestClient, RealtimeProperties properties) {
        this.restClient = realtimeRestClient;
        this.properties = properties;
    }

    /**
     * @return the raw protobuf body
     * @throws GtfsRealtimeFormatException on a non-2xx response or an oversized body
     */
    public byte[] fetch(RealtimeFeed feed) {
        String url = properties.urlFor(feed);

        return restClient.get().uri(url).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new GtfsRealtimeFormatException(
                        "%s returned %s".formatted(url, response.getStatusCode()));
            }
            return readBounded(response.getBody(), properties.maxResponseBytes(), feed);
        });
    }

    /**
     * Reads with a ceiling rather than calling readAllBytes. The response is a third
     * party's, and an unbounded read turns a misbehaving publisher into an
     * out-of-memory error in the application.
     */
    private static byte[] readBounded(InputStream body, long limit, RealtimeFeed feed) throws IOException {
        byte[] buffer = new byte[1 << 16];
        var collected = new java.io.ByteArrayOutputStream(1 << 20);
        int read;

        while ((read = body.read(buffer)) >= 0) {
            if (collected.size() + read > limit) {
                throw new GtfsRealtimeFormatException(
                        "%s response exceeded the %d byte limit".formatted(feed, limit));
            }
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }
}
