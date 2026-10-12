import type { AlertRoute, ApiEnvelope, Page, ResponseMeta, ServiceAlert } from "@/lib/api/types";

/** The moment the helpers' alerts were first seen; ages in tests are measured from here. */
export const FIRST_SEEN = "2026-10-12T10:00:00Z";

/** Server "now" in tests: two hours after the alerts were first seen. */
export const SERVER_NOW = Date.parse("2026-10-12T12:00:00Z");

export function route(shortName: string, overrides: Partial<AlertRoute> = {}): AlertRoute {
  return {
    routeId: shortName,
    known: true,
    shortName,
    longName: `Route ${shortName}`,
    mode: "BUS",
    colour: "DA251D",
    textColour: "FFFFFF",
    ...overrides,
  };
}

/** A route the published alert names but the schedule does not have. */
export function unknownRoute(routeId: string): AlertRoute {
  return { routeId, known: false, shortName: null, longName: null, mode: null, colour: null, textColour: null };
}

let nextId = 1;

export function alert(overrides: Partial<ServiceAlert> = {}): ServiceAlert {
  const id = overrides.id ?? nextId++;
  return {
    id,
    sourceAlertId: `source-${id}`,
    status: "ACTIVE",
    cause: "UNKNOWN_CAUSE",
    effect: "UNKNOWN_EFFECT",
    header: "506 Carlton: Detour via Bay St,",
    description: "506 Carlton: Detour via Bay St, Dundas St E and Broadview Ave due to construction.",
    url: null,
    firstSeenAt: FIRST_SEEN,
    lastSeenAt: "2026-10-12T11:59:40Z",
    lastChangedAt: FIRST_SEEN,
    resolvedAt: null,
    routes: [route("506", { mode: "STREETCAR" })],
    stops: [],
    ...overrides,
  };
}

export function meta(overrides: Partial<ResponseMeta> = {}): ResponseMeta {
  return {
    generatedAt: "2026-10-12T12:00:00Z",
    feedTimestamp: "2026-10-12T11:59:40Z",
    ageSeconds: 20,
    stale: false,
    degraded: false,
    source: "STORED",
    ...overrides,
  };
}

export function envelope(
  alerts: ServiceAlert[],
  overrides: { meta?: Partial<ResponseMeta>; total?: number } = {},
): ApiEnvelope<Page<ServiceAlert>> {
  const total = overrides.total ?? alerts.length;
  return {
    data: { items: alerts, page: 0, size: 100, totalElements: total, totalPages: 1, hasNext: false },
    meta: meta(overrides.meta),
  };
}
