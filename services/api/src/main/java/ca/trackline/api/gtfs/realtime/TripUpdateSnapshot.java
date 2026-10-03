package ca.trackline.api.gtfs.realtime;

import java.time.Instant;
import java.util.List;

/**
 * Predicted calls for one trip.
 *
 * <p>The TTC publishes no delay anywhere in this feed — neither on the trip nor on
 * any stop time event, measured across 25,776 stop time updates. Schedule
 * deviation therefore cannot be read from the feed and would have to be derived by
 * comparing a prediction against the schedule, which is why route health is built
 * on headway regularity rather than on lateness.
 *
 * @param sourceTripId the feed's trip id; frequently synthetic and not joinable
 * @param routeId      route the trip belongs to
 * @param vehicleId    vehicle working it, where the feed says
 * @param calls        predicted stop calls, in the order published
 * @param timestamp    when the prediction was made
 */
public record TripUpdateSnapshot(
        String sourceTripId,
        String routeId,
        String vehicleId,
        List<PredictedCall> calls,
        Instant timestamp) {

    /**
     * One predicted call.
     *
     * <p>Arrival is published far more often than departure — 24,570 against 745 in
     * a real snapshot — so a consumer must treat departure as usually missing.
     *
     * @param stopSequence position within the trip
     * @param stopId       the stop, which joins to the schedule only about half the time
     * @param arrival      predicted arrival, or null
     * @param departure    predicted departure, or null
     */
    public record PredictedCall(int stopSequence, String stopId, Instant arrival, Instant departure) {
    }
}
