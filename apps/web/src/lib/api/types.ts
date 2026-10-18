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

/** The verdict on a route. UNKNOWN is a statement that there is none, with a reason, not an error. */
export type HealthState = "EXCELLENT" | "NORMAL" | "UNSTABLE" | "MAJOR_DISRUPTION" | "UNKNOWN";

export type HealthConfidence = "NONE" | "LOW" | "MEDIUM" | "HIGH";

/** Why a verdict is what it is, or why there is none. The wording is composed from these. */
export type HealthReason =
  | "HIGH_HEADWAY_VARIATION"
  | "LARGE_GAP"
  | "FEWER_VEHICLES_THAN_SCHEDULED"
  | "BUNCHING"
  | "FEW_VEHICLES"
  | "STALE_VEHICLES"
  | "NO_SCHEDULE_COVERAGE"
  | "NO_SCHEDULED_HEADWAY"
  | "SPEED_UNKNOWN"
  | "INSUFFICIENT_VEHICLES"
  | "NO_REALTIME_FEED"
  | "NOT_ANALYSED_YET";

/** What the expected spacing was taken from. */
export type SpacingBasis = "SCHEDULE" | "OBSERVED";

/**
 * The figures a verdict was reached from. Null where a figure could not be computed, never
 * zero. Distances are in metres.
 */
export type HealthMetrics = {
  vehicles: number;
  gaps: number;
  meanGapMetres: number | null;
  maxGapMetres: number | null;
  /** Standard deviation of the gaps over their mean: 0.62 is "varies by 62 %". */
  variation: number | null;
  /** The largest gap over the expected spacing. */
  gapRatio: number | null;
  expectedGapMetres: number | null;
  expectedVehicles: number | null;
  supplyRatio: number | null;
  scheduledHeadwaySeconds: number | null;
  speedMetresPerSecond: number | null;
  staleVehicles: number;
  terminalVehicles: number;
  basis: SpacingBasis;
  /** Packs of vehicles found while a large gap opens elsewhere on the direction. */
  bunchingClusters: number;
};

export type DirectionHealth = {
  directionId: number;
  state: HealthState;
  confidence: HealthConfidence;
  reasons: HealthReason[];
  metrics: HealthMetrics;
};

export type RouteHealth = {
  routeId: string;
  state: HealthState;
  confidence: HealthConfidence;
  reasons: HealthReason[];
  directions: DirectionHealth[];
  unplacedVehicles: number;
  computedAt: string | null;
};

export type RouteHealthSummary = {
  routeId: string;
  shortName: string;
  longName: string;
  mode: TransitMode;
  colour: string | null;
  textColour: string | null;
  state: HealthState;
  confidence: HealthConfidence;
  reasons: HealthReason[];
  vehicles: number;
};

export type RouteHealthList = {
  routes: RouteHealthSummary[];
  computedAt: string | null;
};

/**
 * What is happening across the network right now. `activeAlerts` is null until alerts have
 * been ingested once: null is not zero.
 */
export type NetworkStatus = {
  vehiclesTracked: number;
  routesReporting: number;
  activeAlerts: number | null;
};

/** What a placed vehicle is doing as far as spacing is concerned. */
export type VehicleRole = "SPACED" | "BUNCHED" | "AT_TERMINAL" | "STALE";

export type SpacingVehicle = {
  vehicleId: string;
  /** Metres along the direction's route. */
  progressMetres: number;
  role: VehicleRole;
};

export type BunchSeverity = "MODERATE" | "SEVERE";

/** Vehicles running together while a large gap opens elsewhere on the same direction. */
export type Bunch = {
  vehicleIds: string[];
  spanMetres: number;
  /** The largest gap elsewhere on the direction. */
  serviceGapMetres: number;
  severity: BunchSeverity;
};

export type DirectionSpacing = {
  directionId: number;
  lengthMetres: number;
  loop: boolean;
  /** What spacing was measured against, or null with too few vehicles to say. */
  expectedGapMetres: number | null;
  basis: SpacingBasis;
  vehicles: SpacingVehicle[];
  bunches: Bunch[];
};

/**
 * One bunch over its life. Every time is one Trackline observed, so each bounds the moment it
 * describes: the pack formed at or before `firstSeenAt` and broke up at or before `resolvedAt`.
 */
export type BunchingEvent = {
  id: number;
  directionId: number;
  status: "ACTIVE" | "RESOLVED";
  vehicleIds: string[];
  peakVehicles: number;
  spanMetres: number;
  expectedGapMetres: number;
  serviceGapMetres: number;
  basis: SpacingBasis;
  severity: BunchSeverity;
  peakSeverity: BunchSeverity;
  sightings: number;
  firstSeenAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
};

export type RouteBunching = {
  routeId: string;
  directions: DirectionSpacing[];
  events: BunchingEvent[];
  computedAt: string | null;
};
