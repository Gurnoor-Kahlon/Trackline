package ca.trackline.api.testsupport;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the recorded GTFS-Realtime payloads.
 *
 * <p>These are genuine TTC responses, trimmed to a handful of entities each. Real
 * bytes rather than hand-built messages, because the point of them is to prove the
 * parser handles what the agency actually publishes — including the fields it
 * leaves out.
 */
public final class FeedFixtures {

    private FeedFixtures() {
    }

    public static byte[] bytes(String fixture) {
        try (InputStream stream = FeedFixtures.class.getResourceAsStream("/gtfsrt/" + fixture)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture /gtfsrt/" + fixture);
            }
            return stream.readAllBytes();
        } catch (IOException cause) {
            throw new IllegalStateException("Could not read fixture " + fixture, cause);
        }
    }
}
