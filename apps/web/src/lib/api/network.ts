import { fetchEnvelope } from "@/lib/api/client";
import type { ApiEnvelope, NetworkStatus } from "@/lib/api/types";

/** The network-wide counts, with the age of the vehicle feed they came from. Never cached. */
export function fetchNetworkStatus(signal?: AbortSignal): Promise<ApiEnvelope<NetworkStatus>> {
  return fetchEnvelope<NetworkStatus>("/api/v1/network/status", signal ? { signal } : {});
}
