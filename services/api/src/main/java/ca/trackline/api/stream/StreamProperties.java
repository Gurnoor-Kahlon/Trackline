package ca.trackline.api.stream;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the live streams.
 *
 * @param heartbeatInterval       how often an idle connection is written to; keeps
 *                                proxies from closing it and reveals dead peers
 * @param ringCapacity            how many recent events are kept for resuming clients
 * @param maxSubscribers          most simultaneous stream connections, all clients
 * @param maxSubscribersPerClient most simultaneous stream connections from one address
 * @param emitterTimeout          longest one connection lives before the client is
 *                                asked to reconnect
 * @param queueCapacity           events that may wait for one slow client before it
 *                                is dropped and left to resume
 * @param moveThresholdMetres     movement that makes a vehicle worth re-sending
 * @param keepaliveInterval       longest a reporting vehicle goes unsent, so one that
 *                                is standing still does not look silent
 */
@Validated
@ConfigurationProperties(prefix = "trackline.stream")
public record StreamProperties(
        @NotNull Duration heartbeatInterval,
        @Positive int ringCapacity,
        @Positive int maxSubscribers,
        @Positive int maxSubscribersPerClient,
        @NotNull Duration emitterTimeout,
        @Positive int queueCapacity,
        @Positive double moveThresholdMetres,
        @NotNull Duration keepaliveInterval) {
}
