import { fetchEnvelope } from "@/lib/api/client";
import type { ApiEnvelope, Page, ServiceAlert } from "@/lib/api/types";

/**
 * Alert queries.
 *
 * Never cached: an alert that has just ended or just begun is the whole point, and the
 * API answers from its own database in a few milliseconds. The envelope is returned in
 * full because its `meta` is what tells an empty list that means "no alerts" from one
 * that means "alerts have never been ingested".
 */
export type AlertQuery = {
  /** True for alerts still in the feed (the default), false for those that have left it. */
  active?: boolean;
  route?: string;
  page?: number;
  size?: number;
};

export function fetchAlerts(
  query: AlertQuery = {},
  signal?: AbortSignal,
): Promise<ApiEnvelope<Page<ServiceAlert>>> {
  const parameters = new URLSearchParams();
  if (query.active !== undefined) {
    parameters.set("active", String(query.active));
  }
  if (query.route !== undefined) {
    parameters.set("route", query.route);
  }
  if (query.page !== undefined) {
    parameters.set("page", String(query.page));
  }
  if (query.size !== undefined) {
    parameters.set("size", String(query.size));
  }
  const suffix = parameters.size === 0 ? "" : `?${parameters}`;

  return fetchEnvelope<Page<ServiceAlert>>(`/api/v1/alerts${suffix}`, signal ? { signal } : {});
}
