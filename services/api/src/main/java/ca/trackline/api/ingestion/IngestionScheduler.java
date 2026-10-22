package ca.trackline.api.ingestion;

import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls each feed on its own schedule.
 *
 * <p>The intervals are set from measurement rather than taste. Over a five-minute
 * sample each feed advanced its header about every 20 seconds (median 20-21 s,
 * fastest 6 s, slowest 42 s), so:
 *
 * <ul>
 *   <li>vehicles every 10 s — roughly half the publish interval, so a new position
 *       is picked up within about ten seconds of being published, at 85 KB a poll
 *   <li>trip updates every 20 s — matching the publish rate, because the payload is
 *       480 KB and predictions move slowly enough that halving the interval would
 *       cost five times the bandwidth for nothing
 *   <li>alerts every 60 s — a 2 KB payload that changes a few times a day
 * </ul>
 *
 * <p>Each poller is registered separately so one slow feed cannot delay another.
 *
 * <p>Every poll first asks {@link IngestionLeadership} whether this instance is the one that polls, so that a second instance
 * running beside this one (during a rolling deployment, say) stands by instead of fetching every feed again.
 */
@Component
@ConditionalOnProperty(prefix = "trackline.gtfs.realtime", name = "enabled", havingValue = "true")
public class IngestionScheduler {

    private final IngestionCycleService cycleService;
    private final IngestionLeadership leadership;

    public IngestionScheduler(IngestionCycleService cycleService, IngestionLeadership leadership) {
        this.cycleService = cycleService;
        this.leadership = leadership;
    }

    @Scheduled(fixedDelayString = "${trackline.gtfs.realtime.vehicle-interval}")
    public void pollVehiclePositions() {
        if (leadership.tryHold()) {
            cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        }
    }

    @Scheduled(fixedDelayString = "${trackline.gtfs.realtime.trip-update-interval}")
    public void pollTripUpdates() {
        if (leadership.tryHold()) {
            cycleService.runCycle(RealtimeFeed.TRIP_UPDATES);
        }
    }

    @Scheduled(fixedDelayString = "${trackline.gtfs.realtime.alert-interval}")
    public void pollAlerts() {
        if (leadership.tryHold()) {
            cycleService.runCycle(RealtimeFeed.ALERTS);
        }
    }
}
