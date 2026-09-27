package ca.trackline.api.network;

import java.time.Clock;
import java.time.Instant;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import org.springframework.stereotype.Service;

/**
 * Assembles the current network summary.
 *
 * <p>Realtime ingestion does not exist yet, so this reports nothing observed
 * with a {@code NO_DATA} provenance rather than inventing plausible figures.
 * When ingestion lands, only this class changes: the endpoint contract, envelope
 * and frontend already handle real counts and real feed timestamps.
 */
@Service
public class NetworkStatusService {

    private final Clock clock;

    public NetworkStatusService(Clock clock) {
        this.clock = clock;
    }

    public ApiEnvelope<NetworkStatus> currentStatus() {
        Instant now = Instant.now(clock);
        return ApiEnvelope.of(NetworkStatus.NOTHING_OBSERVED, ResponseMeta.noData(now));
    }
}
