import { fetchData } from "@/lib/api/client";
import type { Page, RouteDetail, RouteShape, RouteStop, RouteSummary } from "@/lib/api/types";

/**
 * Route queries.
 *
 * One function per endpoint, each naming its own caching. The schedule changes
 * monthly, so route data is cached for minutes rather than fetched per render —
 * but the decision is made here, where it can be seen, rather than inside a
 * component.
 */

/** The schedule changes monthly; a few minutes of staleness costs nothing. */
const SCHEDULE_CACHE_SECONDS = 300;

/** The map needs every route at once, and there are 236 of them. */
const ALL_ROUTES_PAGE_SIZE = 100;

export async function fetchAllRoutes(): Promise<RouteSummary[]> {
  const collected: RouteSummary[] = [];
  let page = 0;

  // Paged rather than asking for an unbounded list: the API caps page size, and a
  // client that assumes one page works until the network grows.
  for (;;) {
    const query = new URLSearchParams({ page: String(page), size: String(ALL_ROUTES_PAGE_SIZE) });

    const result = await fetchData<Page<RouteSummary>>(`/api/v1/routes?${query}`, {
      revalidateSeconds: SCHEDULE_CACHE_SECONDS,
    });
    collected.push(...result.items);

    if (!result.hasNext) {
      return collected;
    }
    page += 1;
  }
}

export function fetchRoute(routeId: string, signal?: AbortSignal): Promise<RouteDetail> {
  return fetchData<RouteDetail>(`/api/v1/routes/${encodeURIComponent(routeId)}`, {
    revalidateSeconds: SCHEDULE_CACHE_SECONDS,
    ...(signal ? { signal } : {}),
  });
}

/**
 * A route's geometry.
 *
 * Simplified server-side by default. At city zoom the full geometry draws several
 * hundred points that land on the same handful of pixels, so the detail costs
 * bandwidth and rendering time without being visible.
 */
export function fetchRouteShape(
  routeId: string,
  directionId: number,
  simplifyMetres = 15,
  signal?: AbortSignal,
): Promise<RouteShape> {
  const query = new URLSearchParams({
    direction: String(directionId),
    simplifyMetres: String(simplifyMetres),
  });

  return fetchData<RouteShape>(`/api/v1/routes/${encodeURIComponent(routeId)}/shape?${query}`, {
    revalidateSeconds: SCHEDULE_CACHE_SECONDS,
    ...(signal ? { signal } : {}),
  });
}

export function fetchRouteStops(
  routeId: string,
  directionId: number,
  signal?: AbortSignal,
): Promise<RouteStop[]> {
  const query = new URLSearchParams({ direction: String(directionId) });

  return fetchData<RouteStop[]>(`/api/v1/routes/${encodeURIComponent(routeId)}/stops?${query}`, {
    revalidateSeconds: SCHEDULE_CACHE_SECONDS,
    ...(signal ? { signal } : {}),
  });
}

