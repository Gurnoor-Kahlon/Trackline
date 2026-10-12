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
 * @param activeAlerts    service alerts currently open, or null before alerts have
 *                        ever been ingested. Null, not zero: until the first alert
 *                        cycle has been applied nothing has been counted, and zero
 *                        would claim a measurement that has not been made.
 */
public record NetworkStatus(int vehiclesTracked, int routesReporting, Integer activeAlerts) {

    /** No vehicle has been observed. Alerts are a separate feed, so their count is passed in. */
    static NetworkStatus nothingObserved(Integer activeAlerts) {
        return new NetworkStatus(0, 0, activeAlerts);
    }
}
