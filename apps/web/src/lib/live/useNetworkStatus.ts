"use client";

import { useEffect, useState } from "react";

import { ProtocolError, parseNetwork } from "@/lib/live/protocol";
import { StreamConnection } from "@/lib/live/streamConnection";
import { NETWORK_EVENTS, networkStreamUrl } from "@/lib/live/streamUrl";
import type { ConnectionStatus } from "@/lib/live/types";

/** Fleet-wide counts, which a stream filtered to one route cannot give. */
export type NetworkSummary = {
  vehiclesTracked: number;
  routesReporting: number;
  /** Open alerts, or null while the API has not yet ingested any. Null is not zero. */
  activeAlerts: number | null;
};

export type NetworkStatusResult = {
  summary: NetworkSummary | null;
  connection: ConnectionStatus;
};

/**
 * The network-wide counts, kept current by the network stream.
 *
 * A second connection beside the vehicle stream, because the two answer different
 * questions. Filtered to one route, the vehicle stream knows only that route's
 * vehicles; "1,500 vehicles on 110 routes" is the whole network, and has to come from
 * something that is never filtered.
 */
export function useNetworkStatus(): NetworkStatusResult {
  const [result, setResult] = useState<NetworkStatusResult>({ summary: null, connection: "connecting" });

  useEffect(() => {
    if (typeof EventSource === "undefined") {
      return;
    }

    const connection = new StreamConnection({
      url: () => networkStreamUrl(),
      eventNames: NETWORK_EVENTS,
      onStatus: (connectionStatus) => setResult((previous) => ({ ...previous, connection: connectionStatus })),
      onEvent: (name, data) => {
        if (name !== "network") {
          return;
        }
        try {
          const message = parseNetwork(data);
          setResult((previous) => ({
            ...previous,
            summary: {
              vehiclesTracked: message.vehiclesTracked,
              routesReporting: message.routesReporting,
              activeAlerts: message.activeAlerts,
            },
          }));
        } catch (error) {
          if (error instanceof ProtocolError) {
            console.warn(`Ignored a malformed network event: ${error.message}`);
            return;
          }
          throw error;
        }
      },
    });
    connection.start();

    return () => connection.stop();
  }, []);

  return result;
}
