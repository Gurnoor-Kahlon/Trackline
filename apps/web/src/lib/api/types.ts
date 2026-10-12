/**
 * The API's response shapes, written by hand to match the Java DTOs.
 *
 * Hand-written rather than generated from the OpenAPI document on purpose: these
 * types are the frontend's statement of what it needs, and a mismatch should
 * surface as a compile error here rather than being papered over by regenerating.
 * There are few enough of them that drift is cheap to catch.
 */

/** Where the data came from. Every response carrying feed data says which. */
export type DataSource = "LIVE" | "CACHED" | "DB_FALLBACK" | "STORED" | "STATIC" | "NO_DATA";

/**
 * Freshness and provenance.
 *
 * `ageSeconds` and `feedTimestamp` are null when no feed data underlies the
 * response. Null means unknown, never zero.
 */
export type ResponseMeta = {
  generatedAt: string;
  feedTimestamp: string | null;
  ageSeconds: number | null;
  stale: boolean;
  degraded: boolean;
  source: DataSource;
};

export type ApiEnvelope<T> = {
  data: T;
  meta: ResponseMeta;
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export type TransitMode = "BUS" | "STREETCAR" | "SUBWAY" | "OTHER";

export type RouteSummary = {
  routeId: string;
  shortName: string;
  longName: string;
  mode: TransitMode;
  /** Six hex digits from the GTFS feed, without a leading hash, or null. */
  colour: string | null;
  textColour: string | null;
  /** False for subway routes: the TTC publishes no subway vehicle positions. */
  supportsRealtime: boolean;
};

export type RouteDirection = {
  directionId: number;
  headsign: string | null;
  tripCount: number;
  stopCount: number | null;
  lengthMetres: number | null;
  loop: boolean;
};

export type RouteDetail = RouteSummary & {
  directions: RouteDirection[];
};

/** A GeoJSON LineString, as PostGIS rendered it. */
export type LineStringGeometry = {
  type: "LineString";
  coordinates: [number, number][];
};

export type RouteShape = {
  routeId: string;
  directionId: number;
  shapeId: string;
  lengthMetres: number;
  simplifiedToMetres: number | null;
  geometry: LineStringGeometry;
};

export type RouteStop = {
  stopId: string;
  name: string;
  code: string | null;
  latitude: number;
  longitude: number;
  sequence: number;
  /** Metres along the route, where the feed states it. Null on the first stop. */
  distanceAlongRouteMetres: number | null;
  /** GTFS value as published: 0 not stated, 1 accessible, 2 not accessible. */
  wheelchairBoarding: number;
  stationId: number | null;
  stationName: string | null;
};

/** A request that failed, as RFC 7807 problem details. */
export type ApiProblem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  errorId?: string;
};

/** GTFS-Realtime `Alert.Effect`, as published. The TTC currently sends UNKNOWN_EFFECT for every alert. */
export type AlertEffect =
  | "NO_SERVICE"
  | "REDUCED_SERVICE"
  | "SIGNIFICANT_DELAYS"
  | "DETOUR"
  | "ADDITIONAL_SERVICE"
  | "MODIFIED_SERVICE"
  | "OTHER_EFFECT"
  | "UNKNOWN_EFFECT"
  | "STOP_MOVED"
  | "NO_EFFECT"
  | "ACCESSIBILITY_ISSUE";

/** GTFS-Realtime `Alert.Cause`, as published. The TTC currently sends UNKNOWN_CAUSE for every alert. */
export type AlertCause = string;

/** A route an alert names. `known` is false when the schedule has no route with that id. */
export type AlertRoute = {
  routeId: string;
  known: boolean;
  shortName: string | null;
  longName: string | null;
  mode: TransitMode | null;
  colour: string | null;
  textColour: string | null;
};

export type AlertStop = {
  stopId: string;
  known: boolean;
  name: string | null;
};

/**
 * One stretch of an alert being present in the feed.
 *
 * The feed publishes no start or end, so every time is one Trackline observed and each
 * is an upper bound on the moment it describes.
 */
export type ServiceAlert = {
  id: number;
  sourceAlertId: string;
  status: "ACTIVE" | "RESOLVED";
  cause: AlertCause | null;
  effect: AlertEffect | null;
  /** Cut off by the publisher at about 31 characters, mid-word. Prefer `description`. */
  header: string | null;
  description: string | null;
  url: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  lastChangedAt: string;
  resolvedAt: string | null;
  routes: AlertRoute[];
  stops: AlertStop[];
};
