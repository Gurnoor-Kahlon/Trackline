package ca.trackline.api.network;

import java.time.Clock;
import java.time.Instant;

import ca.trackline.api.alerts.ActiveAlerts;
import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import org.springframework.stereotype.Service;

/**
 * Assembles the current network summary from the live vehicle snapshot.
 *
 * <p>Read through the same three-tier chain as the vehicle endpoints, so this
 * answers from Redis, then this instance's memory, then stored observations, and its
 * envelope says which. When nothing has ever been ingested it reports nothing
 * observed with a {@code NO_DATA} provenance rather than inventing figures.
 *
 * <p>The alert count comes from the alerts Trackline stores, which have their own
 * feed and their own age, so it is not covered by the vehicle feed's {@code meta}.
 */
@Service
public class NetworkStatusService {

    private final LiveVehicleReader vehicles;
    private final ActiveAlerts activeAlerts;
    private final Clock clock;

    public NetworkStatusService(LiveVehicleReader vehicles, ActiveAlerts activeAlerts, Clock clock) {
        this.vehicles = vehicles;
        this.activeAlerts = activeAlerts;
        this.clock = clock;
    }

    public ApiEnvelope<NetworkStatus> currentStatus() {
        Instant now = Instant.now(clock);
        LiveVehicles live = vehicles.readAll();
        Integer alerts = activeAlerts.current();

        if (live.feedTimestamp() == null) {
            return ApiEnvelope.of(NetworkStatus.nothingObserved(alerts), ResponseMeta.noData(now));
        }

        int routes = (int) live.vehicles().stream()
                .map(VehicleSnapshot::routeId)
                .filter(route -> route != null)
                .distinct()
                .count();

        return ApiEnvelope.of(
                new NetworkStatus(live.vehicles().size(), routes, alerts),
                ResponseMeta.fromFeed(now, live.feedTimestamp(), live.degraded(), live.source()));
    }
}
