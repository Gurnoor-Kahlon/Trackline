package ca.trackline.api.gtfs.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The result of parsing one feed response.
 *
 * @param feed          which feed this came from
 * @param headerTimestamp the publisher's own timestamp for the payload
 * @param vehicles      vehicles, empty for other feeds
 * @param tripUpdates   trip updates, empty for other feeds
 * @param alerts        alerts, empty for other feeds
 * @param rejections    how many entities were dropped, by reason
 * @param rawEntityCount entities in the payload before validation
 */
public record FeedSnapshot(
        RealtimeFeed feed,
        Instant headerTimestamp,
        List<VehicleSnapshot> vehicles,
        List<TripUpdateSnapshot> tripUpdates,
        List<AlertSnapshot> alerts,
        Map<RejectionReason, Integer> rejections,
        int rawEntityCount) {

    /** Entities that survived validation. */
    public int acceptedCount() {
        return vehicles.size() + tripUpdates.size() + alerts.size();
    }

    public int rejectedCount() {
        return rejections.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Share of entities dropped. A cycle above the configured threshold is recorded
     * as suspect rather than trusted, because a feed that has started producing
     * rubbish should not quietly replace good data.
     */
    public double rejectionRate() {
        return rawEntityCount == 0 ? 0 : (double) rejectedCount() / rawEntityCount;
    }
}
