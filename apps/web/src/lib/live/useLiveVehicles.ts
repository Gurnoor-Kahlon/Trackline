"use client";

import { useEffect, useState } from "react";

import { LiveStore } from "@/lib/live/liveStore";
import { ProtocolError, parseDelta, parseHeartbeat, parseSnapshot } from "@/lib/live/protocol";
import { StreamConnection } from "@/lib/live/streamConnection";
import { VEHICLE_EVENTS, vehicleStreamUrl } from "@/lib/live/streamUrl";

/**
 * Subscribes to the live vehicle stream for the given routes, or the whole fleet if
 * none are given, and returns the store the stream feeds.
 *
 * The store is returned rather than its contents on purpose. The contents change with
 * every event and are read by the map from an animation loop; handing them to React
 * would re-render the page for each one. Components that need a small derived value,
 * such as the connection status, subscribe to the store themselves.
 */
export function useLiveVehicles(routeIds: readonly string[]): LiveStore {
  const [store] = useState(() => new LiveStore());

  // A string, so the effect restarts when the routes change and not on every render
  // that builds a new array containing the same ones.
  const routeKey = routeIds.join(",");

  useEffect(() => {
    // The server renders the page and has no EventSource; so does a test environment.
    if (typeof EventSource === "undefined") {
      return;
    }

    store.dispatch({ type: "reset", nowMs: Date.now() });

    const routes = routeKey === "" ? [] : routeKey.split(",");
    const connection = new StreamConnection({
      url: (resumeFrom) => vehicleStreamUrl(routes, resumeFrom),
      eventNames: VEHICLE_EVENTS,
      onStatus: (status) => store.dispatch({ type: "connection", status, nowMs: Date.now() }),
      onEvent: (name, data, id) => {
        const nowMs = Date.now();
        try {
          if (name === "snapshot") {
            store.dispatch({ type: "snapshot", message: parseSnapshot(data), eventId: id, nowMs });
          } else if (name === "vehicles") {
            store.dispatch({ type: "delta", message: parseDelta(data), eventId: id, nowMs });
          } else if (name === "heartbeat") {
            const beat = parseHeartbeat(data);
            store.dispatch({ type: "heartbeat", feed: beat.feed, serverTimeMs: beat.serverTimeMs, nowMs });
          }
        } catch (error) {
          if (error instanceof ProtocolError) {
            // One event lost to a malformed payload costs a moment of accuracy; acting
            // on it could corrupt the picture for as long as the page stays open.
            console.warn(`Ignored a malformed live event: ${error.message}`);
            return;
          }
          throw error;
        }
      },
    });
    connection.start();

    return () => connection.stop();
  }, [store, routeKey]);

  return store;
}
