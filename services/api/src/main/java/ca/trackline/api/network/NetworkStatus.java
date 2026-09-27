package ca.trackline.api.network;

/**
 * A measured summary of what is happening on the network right now.
 *
 * <p>Every field is a count of something Trackline has actually observed. When
 * nothing has been observed the counts are zero and the response's
 * {@code meta.source} is {@code NO_DATA}, which is how a client distinguishes
 * "no service running" from "not measured yet".
 *
 * @param vehiclesTracked vehicles present in the most recent realtime snapshot
 * @param routesReporting distinct routes those vehicles belong to
 * @param activeAlerts    service alerts currently present in the alerts feed
 */
public record NetworkStatus(int vehiclesTracked, int routesReporting, int activeAlerts) {

    static final NetworkStatus NOTHING_OBSERVED = new NetworkStatus(0, 0, 0);
}
