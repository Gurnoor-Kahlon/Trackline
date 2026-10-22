import { fetchData } from "@/lib/api/client";
import type { SearchHit, StopDetail } from "@/lib/api/types";

/** What the search box asks for. Enough to pick from, without a list a person would have to scroll. */
export const SEARCH_RESULT_LIMIT = 15;

/**
 * Routes, stops and stations matching a name or number.
 *
 * Never cached: it is typed by a person, a query rarely repeats, and the API limits how many a caller may make a minute.
 */
export function searchTransit(query: string, signal?: AbortSignal): Promise<SearchHit[]> {
  const params = new URLSearchParams({ q: query, limit: String(SEARCH_RESULT_LIMIT) });
  return fetchData<SearchHit[]>(`/api/v1/search?${params}`, signal ? { signal } : {});
}

/** A stop and the routes that call there, so that a stop found by name can lead somewhere the map can show. */
export function fetchStopDetail(stopId: string, signal?: AbortSignal): Promise<StopDetail> {
  return fetchData<StopDetail>(`/api/v1/stops/${encodeURIComponent(stopId)}`, {
    // The schedule changes monthly.
    revalidateSeconds: 300,
    ...(signal ? { signal } : {}),
  });
}
