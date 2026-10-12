package ca.trackline.api.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.common.error.TooManyConnectionsException;

/**
 * Who is connected, and the limits on how many may be.
 *
 * <p>Capped overall and per client address. A live stream holds a queue and a
 * delivery thread for as long as it is open, so unlimited connections are an easy way
 * to exhaust a small server, deliberately or through a client that reconnects in a
 * loop. The per-client figure is by remote address, which behind a proxy is the
 * proxy's; the deployment must forward the real address for the limit to mean what
 * it says.
 */
final class SubscriptionRegistry {

    private final int maxTotal;
    private final int maxPerClient;

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Map<String, Integer> perClient = new HashMap<>();

    SubscriptionRegistry(int maxTotal, int maxPerClient) {
        this.maxTotal = maxTotal;
        this.maxPerClient = maxPerClient;
    }

    /**
     * Checks the limits before anything is allocated for a new connection.
     *
     * @throws TooManyConnectionsException when either limit is reached
     */
    synchronized void admit(String clientKey) {
        if (subscriptions.size() >= maxTotal) {
            throw new TooManyConnectionsException(
                    "The server is holding as many live connections as it allows. Try again shortly.");
        }
        if (perClient.getOrDefault(clientKey, 0) >= maxPerClient) {
            throw new TooManyConnectionsException(
                    "This address already holds %d live connections, which is the most allowed.".formatted(maxPerClient));
        }
    }

    synchronized void add(Subscription subscription) {
        subscriptions.add(subscription);
        perClient.merge(subscription.clientKey(), 1, Integer::sum);
    }

    synchronized void remove(Subscription subscription) {
        if (subscriptions.remove(subscription)) {
            perClient.computeIfPresent(subscription.clientKey(), (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** A copy, so callers can iterate while connections come and go. */
    synchronized List<Subscription> all() {
        return List.copyOf(subscriptions);
    }

    synchronized int size() {
        return subscriptions.size();
    }

    synchronized int size(String channel) {
        return (int) subscriptions.stream().filter(subscription -> subscription.channel().equals(channel)).count();
    }
}
