import { apiBaseUrl } from "@/lib/api/client";

/**
 * Where the live streams are, in one place so no component builds a URL by hand.
 *
 * The resume id goes in the query string as well as the header the browser sends on
 * its own: when this code reopens a connection itself, after an HTTP error the browser
 * would not retry, there is no way to set a header on an EventSource.
 */

export const VEHICLE_EVENTS = ["snapshot", "vehicles", "heartbeat"] as const;
export const NETWORK_EVENTS = ["network", "heartbeat"] as const;

export function vehicleStreamUrl(routeIds: readonly string[], resumeFrom: string | null): string {
  const query = new URLSearchParams();
  if (routeIds.length > 0) {
    query.set("routes", routeIds.join(","));
  }
  if (resumeFrom !== null) {
    query.set("lastEventId", resumeFrom);
  }
  const text = query.toString();
  return `${apiBaseUrl()}/api/v1/stream/vehicles${text === "" ? "" : `?${text}`}`;
}

export function networkStreamUrl(): string {
  return `${apiBaseUrl()}/api/v1/stream/network`;
}
