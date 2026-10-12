package ca.trackline.api.stream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A real SSE client over a real socket, for tests.
 *
 * <p>Parses the stream the way a browser's EventSource does: fields accumulate until
 * a blank line ends the event. Reading happens on its own thread so a test can wait
 * for the next event with a timeout instead of hanging on a stream that has gone
 * quiet, which is the failure it exists to expose.
 */
final class SseClient implements AutoCloseable {

    /** @param receivedAtNanos when the last byte of the event arrived, from System.nanoTime() */
    record Event(String id, String name, String data, long receivedAtNanos) {

        JsonNode json() {
            try {
                return new ObjectMapper().readTree(data);
            } catch (IOException cause) {
                throw new AssertionError("Event data is not JSON: " + data, cause);
            }
        }
    }

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final List<Event> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int status;
    private final HttpHeaders headers;
    private final InputStream body;
    private final String errorBody;
    private volatile boolean ended;

    private SseClient(int status, HttpHeaders headers, InputStream body, String errorBody) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.errorBody = errorBody;
        if (status == 200) {
            Thread.ofVirtual().start(this::read);
        }
    }

    /** Opens the stream, returning once the response headers have arrived. */
    static SseClient open(String url, Map<String, String> extraHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30));
        extraHeaders.forEach(request::header);

        HttpResponse<InputStream> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            try (InputStream error = response.body()) {
                String text = new String(error.readAllBytes(), StandardCharsets.UTF_8);
                return new SseClient(response.statusCode(), response.headers(), null, text);
            }
        }
        return new SseClient(response.statusCode(), response.headers(), response.body(), null);
    }

    static SseClient open(String url) throws IOException, InterruptedException {
        return open(url, Map.of());
    }

    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String id = null;
            String name = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (name != null || data.length() > 0) {
                        Event event = new Event(id, name == null ? "message" : name, data.toString(), System.nanoTime());
                        received.add(event);
                        events.add(event);
                    }
                    id = null;
                    name = null;
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6);
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
        } catch (IOException closed) {
            // The test closed the connection, or the server did.
        } finally {
            ended = true;
        }
    }

    /** The next event, or null if none arrives in time. */
    Event next(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The next event, failing the test if none arrives. */
    Event expectNext(Duration timeout) throws InterruptedException {
        Event event = next(timeout);
        if (event == null) {
            throw new AssertionError("No event within " + timeout + "; received so far: " + names());
        }
        return event;
    }

    List<String> names() {
        List<String> names = new ArrayList<>();
        received.forEach(event -> names.add(event.name()));
        return names;
    }

    List<Event> allReceived() {
        return List.copyOf(received);
    }

    int status() {
        return status;
    }

    String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    String errorBody() {
        return errorBody;
    }

    boolean hasEnded() {
        return ended;
    }

    @Override
    public void close() {
        try {
            if (body != null) {
                body.close();
            }
        } catch (IOException ignored) {
            // Closing is the point; how it ends is not.
        }
    }
}
