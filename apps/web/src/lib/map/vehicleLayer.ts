import type { GeoJSONSource, Map as MapLibreMap } from "maplibre-gl";

import type { AnimatedVehicle } from "@/lib/live/animator";
import { isVehicleStale } from "@/lib/live/liveState";
import type { Freshness } from "@/lib/live/types";

/**
 * Drawing live vehicles on the map.
 *
 * Kept apart from the component that owns the map so the decisions — what colour a
 * marker is, when it is dimmed, which way it points — are plain functions that can be
 * checked without a map, and the parts that need one are small.
 */

export const VEHICLE_SOURCE = "trackline-vehicles";
export const VEHICLE_LAYER = "trackline-vehicle-markers";
const ARROW_IMAGE = "trackline-vehicle-arrow";
const DOT_IMAGE = "trackline-vehicle-dot";

/** A vehicle on no route, or a route that published no colour, is drawn in neutral grey. */
const NEUTRAL_COLOUR = "#6b7280";

/**
 * How much a marker is dimmed. A vehicle that has not reported for two minutes is
 * halved, because it may no longer be where it is drawn; and when the whole feed is
 * more than five minutes old every marker is faded far down, because none of them is
 * live and the picture must not look as though it were.
 */
const STALE_VEHICLE_OPACITY = 0.5;
const LOST_FEED_OPACITY = 0.3;

/**
 * The shape of the GeoJSON this layer draws, written out rather than taken from the
 * GeoJSON typings. Two small types are not worth a type package, and a structural match
 * is all MapLibre's `setData` asks for.
 */
export type VehicleFeatureCollection = {
  type: "FeatureCollection";
  features: {
    type: "Feature";
    properties: VehicleProperties;
    geometry: { type: "Point"; coordinates: [number, number] };
  }[];
};

export type VehicleProperties = {
  id: string;
  colour: string;
  /** Degrees clockwise from north; 0 for a vehicle with no bearing, which uses the dot. */
  bearing: number;
  hasBearing: boolean;
  opacity: number;
};

export type FeatureContext = {
  /** Route id to six hex digits, without a hash, as the schedule publishes it. */
  routeColours: ReadonlyMap<string, string>;
  serverNowSeconds: number | null;
  freshness: Freshness;
};

export function buildFeatures(
  vehicles: readonly AnimatedVehicle[],
  context: FeatureContext,
): VehicleFeatureCollection {
  return {
    type: "FeatureCollection",
    features: vehicles.map(({ vehicle, position }) => ({
      type: "Feature",
      properties: {
        id: vehicle.id,
        colour: colourFor(vehicle.routeId, context.routeColours),
        bearing: vehicle.bearing ?? 0,
        hasBearing: vehicle.bearing !== null,
        opacity: opacityFor(isVehicleStale(vehicle, context.serverNowSeconds), context.freshness),
      },
      geometry: { type: "Point", coordinates: [position[0], position[1]] },
    })),
  };
}

export function colourFor(routeId: string | null, routeColours: ReadonlyMap<string, string>): string {
  const colour = routeId === null ? undefined : routeColours.get(routeId);
  return colour === undefined ? NEUTRAL_COLOUR : `#${colour}`;
}

export function opacityFor(vehicleStale: boolean, freshness: Freshness): number {
  if (freshness === "lost") {
    return LOST_FEED_OPACITY;
  }
  return vehicleStale ? STALE_VEHICLE_OPACITY : 1;
}

// --- the icons ------------------------------------------------------------------------

const ICON_SIZE = 64;

/** How far, in pixels, the distance field extends from the edge. MapLibre's SDF format assumes 8. */
const SDF_RADIUS = 8;

type Point = readonly [number, number];

/** Points up, so a bearing of 0 is already correct and rotation is the bearing itself. */
const ARROW: readonly Point[] = [
  [32, 8],
  [52, 54],
  [32, 42],
  [12, 54],
];

/**
 * A signed distance field of a shape, in the format MapLibre reads for SDF icons.
 *
 * An SDF icon is what lets one image be drawn in any colour and with a halo, chosen per
 * marker from the route. It is not the shape itself but the distance to its edge, and
 * a plain mask handed over as an SDF draws jagged and loses its halo. The edge sits at
 * 0.75 of full alpha and each pixel of distance moves that by 1/8.
 *
 * Computed by brute force, which is cheap for a 64-pixel icon made once per map.
 */
export function signedDistanceIcon(inside: (x: number, y: number) => number): {
  width: number;
  height: number;
  data: Uint8Array;
} {
  const data = new Uint8Array(ICON_SIZE * ICON_SIZE * 4);
  for (let y = 0; y < ICON_SIZE; y++) {
    for (let x = 0; x < ICON_SIZE; x++) {
      // Positive outside the shape, negative inside.
      const distance = inside(x + 0.5, y + 0.5);
      const alpha = Math.min(1, Math.max(0, 0.75 - distance / SDF_RADIUS));
      const offset = (y * ICON_SIZE + x) * 4;
      data[offset] = 255;
      data[offset + 1] = 255;
      data[offset + 2] = 255;
      data[offset + 3] = Math.round(alpha * 255);
    }
  }
  return { width: ICON_SIZE, height: ICON_SIZE, data };
}

/** Distance from a point to a polygon's edge: positive outside, negative inside. */
export function polygonDistance(polygon: readonly Point[], x: number, y: number): number {
  let nearest = Infinity;
  let crossings = 0;
  for (let index = 0; index < polygon.length; index++) {
    const a = polygon[index]!;
    const b = polygon[(index + 1) % polygon.length]!;
    nearest = Math.min(nearest, distanceToSegment(x, y, a, b));
    if (a[1] > y !== b[1] > y && x < ((b[0] - a[0]) * (y - a[1])) / (b[1] - a[1]) + a[0]) {
      crossings += 1;
    }
  }
  return crossings % 2 === 1 ? -nearest : nearest;
}

function distanceToSegment(x: number, y: number, a: Point, b: Point): number {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0 ? 0 : Math.min(1, Math.max(0, ((x - a[0]) * dx + (y - a[1]) * dy) / lengthSquared));
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
}

export const arrowIcon = () => signedDistanceIcon((x, y) => polygonDistance(ARROW, x, y));
export const dotIcon = () => signedDistanceIcon((x, y) => Math.hypot(x - 32, y - 32) - 16);

// --- the map layer -----------------------------------------------------------------------

/**
 * Adds the vehicle layer if it is not already there. Safe to call repeatedly, which it
 * has to be: changing the theme replaces the map's style and with it every layer,
 * source and image, and each has to be put back.
 */
export function ensureVehicleLayer(map: MapLibreMap, haloColour: string): void {
  if (!map.hasImage(ARROW_IMAGE)) {
    map.addImage(ARROW_IMAGE, arrowIcon(), { sdf: true, pixelRatio: 2 });
  }
  if (!map.hasImage(DOT_IMAGE)) {
    map.addImage(DOT_IMAGE, dotIcon(), { sdf: true, pixelRatio: 2 });
  }
  if (!map.getSource(VEHICLE_SOURCE)) {
    map.addSource(VEHICLE_SOURCE, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
  }
  if (!map.getLayer(VEHICLE_LAYER)) {
    map.addLayer({
      id: VEHICLE_LAYER,
      type: "symbol",
      source: VEHICLE_SOURCE,
      layout: {
        "icon-image": ["case", ["get", "hasBearing"], ARROW_IMAGE, DOT_IMAGE],
        "icon-rotate": ["get", "bearing"],
        // Rotated with the map, so a bearing is a compass direction and stays one when
        // the map is turned.
        "icon-rotation-alignment": "map",
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        "icon-size": ["interpolate", ["linear"], ["zoom"], 9, 0.28, 13, 0.42, 16, 0.65],
      },
      paint: {
        "icon-color": ["get", "colour"],
        "icon-opacity": ["get", "opacity"],
        "icon-halo-color": haloColour,
        "icon-halo-width": 1.5,
      },
    });
  }
}

export function setVehicleData(
  map: MapLibreMap,
  data: VehicleFeatureCollection,
): void {
  const source = map.getSource(VEHICLE_SOURCE) as GeoJSONSource | undefined;
  source?.setData(data);
}
