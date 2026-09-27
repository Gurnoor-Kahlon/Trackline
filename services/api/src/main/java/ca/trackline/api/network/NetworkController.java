package ca.trackline.api.network;

import ca.trackline.api.common.envelope.ApiEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/network")
@Tag(name = "Network", description = "Network-wide live status")
public class NetworkController {

    private final NetworkStatusService networkStatusService;

    public NetworkController(NetworkStatusService networkStatusService) {
        this.networkStatusService = networkStatusService;
    }

    @GetMapping("/status")
    @Operation(
            summary = "Current network status",
            description = "Counts observed in the most recent realtime snapshot. Check "
                    + "meta.source: NO_DATA means nothing has been observed yet, not that "
                    + "the network is idle.")
    public ApiEnvelope<NetworkStatus> status() {
        return networkStatusService.currentStatus();
    }
}
