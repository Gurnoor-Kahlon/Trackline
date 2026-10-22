package ca.trackline.api.ingestion;

import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The pollers only fetch a feed on the instance that holds the ingestion lock. */
class IngestionSchedulerTest {

    private final IngestionCycleService cycles = mock(IngestionCycleService.class);
    private final IngestionLeadership leadership = mock(IngestionLeadership.class);
    private final IngestionScheduler scheduler = new IngestionScheduler(cycles, leadership);

    @Test
    void pollsEveryFeedWhenThisInstanceHoldsTheLock() {
        when(leadership.tryHold()).thenReturn(true);

        scheduler.pollVehiclePositions();
        scheduler.pollTripUpdates();
        scheduler.pollAlerts();

        verify(cycles).runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        verify(cycles).runCycle(RealtimeFeed.TRIP_UPDATES);
        verify(cycles).runCycle(RealtimeFeed.ALERTS);
    }

    @Test
    void fetchesNothingWhenAnotherInstanceHoldsIt() {
        when(leadership.tryHold()).thenReturn(false);

        scheduler.pollVehiclePositions();
        scheduler.pollTripUpdates();
        scheduler.pollAlerts();

        verify(cycles, never()).runCycle(any());
    }
}
