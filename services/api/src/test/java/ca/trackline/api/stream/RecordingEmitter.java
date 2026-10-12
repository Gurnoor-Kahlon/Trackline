package ca.trackline.api.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * An emitter that records what would have been written, instead of writing it.
 *
 * <p>The container normally drives an emitter: it supplies the response to write to
 * and calls back when the client goes away. Neither exists in a unit test, so this
 * stands in for both: it captures each event as the text a browser would receive,
 * can be made to block or fail a write, and can simulate the client disconnecting by
 * running the callbacks the subscription registered.
 */
final class RecordingEmitter extends SseEmitter {

    /** One event as it would reach the client. */
    record Sent(String id, String name, String data, Long retry) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<Runnable> completion = new CopyOnWriteArrayList<>();
    private final List<Runnable> timeout = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> error = new CopyOnWriteArrayList<>();

    private volatile boolean completed;
    private volatile IOException failWith;
    private volatile CountDownLatch gate;

    RecordingEmitter() {
        super(0L);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        CountDownLatch blocked = gate;
        if (blocked != null) {
            try {
                blocked.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while blocked", interrupted);
            }
        }
        if (failWith != null) {
            throw failWith;
        }

        StringBuilder text = new StringBuilder();
        builder.build().forEach(piece -> text.append(piece.getData()));
        sent.add(parse(text.toString()));
    }

    private static Sent parse(String text) {
        String id = null;
        String name = null;
        Long retry = null;
        StringBuilder data = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("id:")) {
                id = line.substring(3);
            } else if (line.startsWith("event:")) {
                name = line.substring(6);
            } else if (line.startsWith("retry:")) {
                retry = Long.parseLong(line.substring(6));
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5));
            }
        }
        return new Sent(id, name, data.toString(), retry);
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public void onCompletion(Runnable callback) {
        completion.add(callback);
    }

    @Override
    public void onTimeout(Runnable callback) {
        timeout.add(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        error.add(callback);
    }

    List<Sent> sent() {
        return new ArrayList<>(sent);
    }

    List<String> names() {
        return sent.stream().map(Sent::name).toList();
    }

    boolean isCompleted() {
        return completed;
    }

    /** Makes every write from now on fail, as writing to a closed connection does. */
    void failWrites(IOException failure) {
        this.failWith = failure;
    }

    /** Makes writes wait until {@link #release()}: a client whose socket has stopped draining. */
    void blockWrites() {
        this.gate = new CountDownLatch(1);
    }

    void release() {
        CountDownLatch blocked = gate;
        if (blocked != null) {
            blocked.countDown();
        }
    }

    /** What the container does when the client closes the connection. */
    void simulateDisconnect() {
        completion.forEach(Runnable::run);
    }

    void simulateTimeout() {
        timeout.forEach(Runnable::run);
    }

    void simulateError(Throwable cause) {
        error.forEach(callback -> callback.accept(cause));
    }

}
