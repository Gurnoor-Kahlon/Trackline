package ca.trackline.api.stream;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes to every connection on a timer.
 *
 * <p>An idle stream looks identical to a dead one from both ends. The heartbeat is
 * what lets a proxy see traffic and keep the connection open, lets the server
 * discover a client that has vanished (the write fails), and lets the client
 * conclude the server has gone if the beats stop. It also carries the feed's age,
 * so a quiet stretch with no vehicle changes still shows how fresh the data is.
 */
@Component
class StreamHeartbeat {

    private final StreamHub hub;

    StreamHeartbeat(StreamHub hub) {
        this.hub = hub;
    }

    @Scheduled(fixedDelayString = "${trackline.stream.heartbeat-interval}")
    void beat() {
        hub.heartbeat();
    }
}
