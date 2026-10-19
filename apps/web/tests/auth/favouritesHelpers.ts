import type { RouteSummary } from "@/lib/api/types";
import type { Favourites, FavouriteRoute, FavouriteStop } from "@/lib/favourites/api";

export function routeSummary(overrides: Partial<RouteSummary> = {}): RouteSummary {
  return {
    routeId: "501",
    shortName: "501",
    longName: "Queen",
    mode: "STREETCAR",
    colour: "DA251D",
    textColour: "FFFFFF",
    supportsRealtime: true,
    ...overrides,
  };
}

export function savedRoute(routeId: string, route: RouteSummary | null = routeSummary({ routeId })): FavouriteRoute {
  return { routeId, savedAt: "2026-10-20T12:00:00Z", route };
}

export function savedStop(stopId: string, name: string | null = "Union Station", code: string | null = "1"): FavouriteStop {
  return {
    stopId,
    savedAt: "2026-10-20T12:00:00Z",
    stop:
      name === null
        ? null
        : {
            stopId,
            name,
            code,
            latitude: 43.64,
            longitude: -79.38,
            wheelchairBoarding: 1,
            stationId: null,
            stationName: null,
          },
  };
}

export function favourites(routes: FavouriteRoute[] = [], stops: FavouriteStop[] = []): Favourites {
  return { routes, stops };
}
