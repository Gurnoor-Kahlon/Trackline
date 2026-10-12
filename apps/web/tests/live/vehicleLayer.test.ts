import type { Map as MapLibreMap } from "maplibre-gl";
import { describe, expect, it, vi } from "vitest";

import type { AnimatedVehicle } from "@/lib/live/animator";
import {
  VEHICLE_LAYER,
  VEHICLE_SOURCE,
  arrowIcon,
  buildFeatures,
  colourFor,
  dotIcon,
  ensureVehicleLayer,
  opacityFor,
  polygonDistance,
  setVehicleData,
} from "@/lib/map/vehicleLayer";

import { vehicle } from "./helpers";

const COLOURS = new Map([
  ["501", "DA251D"],
  ["504", "0071CE"],
]);

function drawn(...vehicles: ReturnType<typeof vehicle>[]): AnimatedVehicle[] {
  return vehicles.map((item) => ({ vehicle: item, position: [item.longitude, item.latitude] as const }));
}

describe("how a marker is drawn", () => {
  it("is coloured by its route, as the schedule publishes the colour", () => {
    expect(colourFor("501", COLOURS)).toBe("#DA251D");
    expect(colourFor("504", COLOURS)).toBe("#0071CE");
  });

  it("is neutral grey for a vehicle on no route or a route with no colour", () => {
    expect(colourFor(null, COLOURS)).toBe("#6b7280");
    expect(colourFor("999", COLOURS)).toBe("#6b7280");
  });

  describe("opacity", () => {
    it("is full for a vehicle that has reported recently on a live feed", () => {
      expect(opacityFor(false, "fresh")).toBe(1);
      expect(opacityFor(false, "stale")).toBe(1);
    });

    it("is halved for a vehicle that has not reported for two minutes", () => {
      expect(opacityFor(true, "fresh")).toBe(0.5);
    });

    it("is faded right down when the whole feed is past five minutes", () => {
      expect(opacityFor(false, "lost")).toBe(0.3);
      // And a lost feed outranks one vehicle's staleness: none of them is live.
      expect(opacityFor(true, "lost")).toBe(0.3);
    });

    it("does not dim a vehicle just because nothing is known yet", () => {
      expect(opacityFor(false, "unknown")).toBe(1);
    });
  });
});

describe("the features handed to the map", () => {
  const context = { routeColours: COLOURS, serverNowSeconds: 1_000_000, freshness: "fresh" as const };

  it("places each marker at the position it was given, in longitude then latitude order", () => {
    const collection = buildFeatures(
      [{ vehicle: vehicle("a"), position: [-79.5, 43.7] }],
      context,
    );

    expect(collection.type).toBe("FeatureCollection");
    // GeoJSON is [longitude, latitude]. The reverse puts Toronto in Antarctica.
    expect(collection.features[0]?.geometry.coordinates).toEqual([-79.5, 43.7]);
  });

  it("points a marker the way the vehicle is heading, and says it has a heading", () => {
    const feature = buildFeatures(drawn(vehicle("a", { bearing: 217 })), context).features[0];

    expect(feature?.properties.bearing).toBe(217);
    expect(feature?.properties.hasBearing).toBe(true);
  });

  it("draws a vehicle with no heading as a dot, not an arrow pointing north", () => {
    const feature = buildFeatures(drawn(vehicle("a", { bearing: null })), context).features[0];

    // A bearing of 0 would be an arrow confidently pointing due north.
    expect(feature?.properties.hasBearing).toBe(false);
  });

  it("keeps a heading of exactly 0 as a real heading", () => {
    const feature = buildFeatures(drawn(vehicle("a", { bearing: 0 })), context).features[0];

    expect(feature?.properties.hasBearing).toBe(true);
    expect(feature?.properties.bearing).toBe(0);
  });

  it("dims a vehicle whose own report is old, judged by the server's clock", () => {
    const old = vehicle("a", { reportedAt: 1_000_000 - 300 });
    const recent = vehicle("b", { reportedAt: 1_000_000 - 20 });

    const [first, second] = buildFeatures(drawn(old, recent), context).features;

    expect(first?.properties.opacity).toBe(0.5);
    expect(second?.properties.opacity).toBe(1);
  });

  it("dims nothing for staleness while the server's clock is unknown", () => {
    const features = buildFeatures(drawn(vehicle("a", { reportedAt: 1 })), { ...context, serverNowSeconds: null });

    expect(features.features[0]?.properties.opacity).toBe(1);
  });

  it("fades every marker when the feed is lost", () => {
    const features = buildFeatures(drawn(vehicle("a"), vehicle("b")), { ...context, freshness: "lost" });

    expect(features.features.map((feature) => feature.properties.opacity)).toEqual([0.3, 0.3]);
  });

  it("is an empty collection for no vehicles", () => {
    expect(buildFeatures([], context).features).toEqual([]);
  });
});

describe("the icons", () => {
  /** Alpha at a pixel of a 64x64 icon. */
  const alphaAt = (icon: { data: Uint8Array }, x: number, y: number) => icon.data[(y * 64 + x) * 4 + 3]!;

  it("are 64 pixels square with four bytes a pixel", () => {
    for (const icon of [arrowIcon(), dotIcon()]) {
      expect(icon.width).toBe(64);
      expect(icon.height).toBe(64);
      expect(icon.data).toHaveLength(64 * 64 * 4);
    }
  });

  it("are signed distance fields: solid inside, empty far outside, three quarters at the edge", () => {
    const dot = dotIcon();
    const edge = Math.round(0.75 * 255);

    // The format MapLibre reads: the edge at 0.75 of full alpha, which is what lets
    // one image take any colour and a halo.
    expect(alphaAt(dot, 32, 32)).toBe(255);
    expect(alphaAt(dot, 0, 0)).toBe(0);
    // The dot's radius is 16 from the middle, so along the row the value crosses the
    // edge value between the last pixel inside and the first outside.
    expect(alphaAt(dot, 47, 32)).toBeGreaterThan(edge);
    expect(alphaAt(dot, 48, 32)).toBeLessThan(edge);
  });

  it("have a gradient of values and not just on and off", () => {
    const dot = dotIcon();
    const between = new Set<number>();
    for (let x = 0; x < 64; x++) {
      const alpha = alphaAt(dot, x, 32);
      if (alpha !== 0 && alpha !== 255) {
        between.add(alpha);
      }
    }

    // A plain mask handed over as an SDF has no values between empty and full, draws
    // jagged, and loses its halo. A real field falls away over several pixels of edge.
    expect(between.size).toBeGreaterThanOrEqual(6);
  });

  it("point up, so a bearing of 0 needs no rotation", () => {
    const arrow = arrowIcon();
    const solid = (x: number, y: number) => alphaAt(arrow, x, y) >= Math.round(0.75 * 255);
    const column = (x: number) => Array.from({ length: 64 }, (_, y) => y).filter((y) => solid(x, y));

    // The tip is at the top, in the middle...
    expect(Math.min(...column(32))).toBeLessThan(14);
    // ...and the middle is solid only on the tip side of the notch at the back,
    expect(Math.max(...column(32))).toBeLessThan(44);
    // while the wings reach lower at the sides. That is a chevron pointing up, which a
    // plain triangle, or one pointing down, would not be.
    expect(Math.max(...column(14))).toBeGreaterThan(50);
    expect(Math.max(...column(50))).toBeGreaterThan(50);
  });

  it("leave a margin at the edges for the field to fall away into", () => {
    for (const icon of [arrowIcon(), dotIcon()]) {
      for (let index = 0; index < 64; index++) {
        expect(alphaAt(icon, index, 0)).toBe(0);
        expect(alphaAt(icon, 0, index)).toBe(0);
        expect(alphaAt(icon, 63, index)).toBe(0);
      }
    }
  });
});

describe("distance to a polygon", () => {
  const square = [
    [0, 0],
    [10, 0],
    [10, 10],
    [0, 10],
  ] as const;

  it("is negative inside, positive outside, and the distance to the nearest edge", () => {
    expect(polygonDistance(square, 5, 5)).toBe(-5);
    expect(polygonDistance(square, 2, 5)).toBe(-2);
    expect(polygonDistance(square, 13, 5)).toBe(3);
    expect(polygonDistance(square, 13, 14)).toBe(5);
  });
});

describe("putting the layer on the map", () => {
  function fakeMap(existing: { images?: string[]; sources?: string[]; layers?: string[] } = {}) {
    const images = new Set(existing.images ?? []);
    const sources = new Set(existing.sources ?? []);
    const layers = new Set(existing.layers ?? []);
    const setData = vi.fn();

    const map = {
      hasImage: (name: string) => images.has(name),
      addImage: vi.fn((name: string) => images.add(name)),
      getSource: (name: string) => (sources.has(name) ? { setData } : undefined),
      addSource: vi.fn((name: string) => sources.add(name)),
      getLayer: (name: string) => (layers.has(name) ? {} : undefined),
      addLayer: vi.fn((layer: { id: string }) => layers.add(layer.id)),
    };
    return { map: map as unknown as MapLibreMap, spies: map, setData };
  }

  it("adds both icons as signed distance fields, the source and the layer", () => {
    const { map, spies } = fakeMap();

    ensureVehicleLayer(map, "#ffffff");

    expect(spies.addImage).toHaveBeenCalledTimes(2);
    for (const call of spies.addImage.mock.calls) {
      expect((call as unknown[])[2]).toMatchObject({ sdf: true });
    }
    expect(spies.addSource).toHaveBeenCalledWith(VEHICLE_SOURCE, expect.anything());
    expect(spies.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: VEHICLE_LAYER, source: VEHICLE_SOURCE }));
  });

  it("is safe to call again, which a theme change makes necessary", () => {
    const { map, spies } = fakeMap();

    ensureVehicleLayer(map, "#ffffff");
    ensureVehicleLayer(map, "#ffffff");
    ensureVehicleLayer(map, "#ffffff");

    expect(spies.addImage).toHaveBeenCalledTimes(2);
    expect(spies.addSource).toHaveBeenCalledTimes(1);
    expect(spies.addLayer).toHaveBeenCalledTimes(1);
  });

  it("restores only what a style change removed", () => {
    // A new style discards layers and the source but the images may survive, or not.
    const { map, spies } = fakeMap({ images: ["trackline-vehicle-arrow", "trackline-vehicle-dot"] });

    ensureVehicleLayer(map, "#ffffff");

    expect(spies.addImage).not.toHaveBeenCalled();
    expect(spies.addSource).toHaveBeenCalledTimes(1);
    expect(spies.addLayer).toHaveBeenCalledTimes(1);
  });

  it("draws the arrow for vehicles with a heading and the dot for those without", () => {
    const { map, spies } = fakeMap();

    ensureVehicleLayer(map, "#ffffff");

    const layer = spies.addLayer.mock.calls[0]?.[0] as unknown as {
      layout: Record<string, unknown>;
      paint: Record<string, unknown>;
    };
    expect(JSON.stringify(layer.layout["icon-image"])).toContain("hasBearing");
    // Rotated with the map, so a bearing stays a compass direction when the map is turned.
    expect(layer.layout["icon-rotation-alignment"]).toBe("map");
    expect(layer.paint["icon-color"]).toEqual(["get", "colour"]);
    expect(layer.paint["icon-opacity"]).toEqual(["get", "opacity"]);
  });

  it("sets the data on the source it added", () => {
    const { map, setData } = fakeMap({ sources: [VEHICLE_SOURCE] });
    const collection = buildFeatures([], { routeColours: COLOURS, serverNowSeconds: null, freshness: "unknown" });

    setVehicleData(map, collection);

    expect(setData).toHaveBeenCalledWith(collection);
  });

  it("does nothing, rather than failing, if the source is not there yet", () => {
    const { map } = fakeMap();

    expect(() =>
      setVehicleData(map, buildFeatures([], { routeColours: COLOURS, serverNowSeconds: null, freshness: "unknown" })),
    ).not.toThrow();
  });
});
