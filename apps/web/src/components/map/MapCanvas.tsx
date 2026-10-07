"use client";

import {
  LngLatBounds,
  Map as MapLibreMap,
  NavigationControl,
  ScaleControl,
  setWorkerUrl,
  type GeoJSONSource,
  type MapLayerMouseEvent,
} from "maplibre-gl";
import { useEffect, useRef, useSyncExternalStore } from "react";

import type { RouteShape, RouteStop } from "@/lib/api/types";
import {
  DEFAULT_ZOOM,
  MAX_BOUNDS,
  STOP_VISIBILITY_ZOOM,
  TORONTO_CENTRE,
  mapColours,
  mapStyleUrl,
} from "@/lib/map/mapStyle";
import { getServerTheme, getTheme, subscribe } from "@/lib/theme";

import "maplibre-gl/dist/maplibre-gl.css";

/**
 * Where MapLibre's worker is served from.
 *
 * It otherwise resolves the worker against its own bundled chunk, which does not
 * exist as a file, and the map then draws nothing while reporting only "Worker
 * failed to load". The file is copied into public/ before each dev run and build
 * by scripts/sync-maplibre-worker.mjs.
 */
setWorkerUrl("/vendor/maplibre-gl-worker.mjs");

const ROUTE_SOURCE = "trackline-route";
const ROUTE_LAYER = "trackline-route-line";
const ROUTE_CASING_LAYER = "trackline-route-casing";
const STOP_SOURCE = "trackline-stops";
const STOP_LAYER = "trackline-stop-circles";

type MapCanvasProps = {
  /** Geometry of the selected route, or null when nothing is selected. */
  shape: RouteShape | null;
  /** Stops of the selected route, drawn above the zoom threshold. */
  stops: RouteStop[];
  /** The selected route's colour, six hex digits without a hash. */
  routeColour: string | null;
  /** Called when a stop marker is clicked. */
  onStopSelect: (stopId: string) => void;
  /** The currently selected stop, highlighted on the map. */
  selectedStopId: string | null;
};

/** Everything the layers need, so they can be re-applied at any moment. */
type MapData = Pick<MapCanvasProps, "shape" | "stops" | "routeColour" | "selectedStopId">;

/**
 * The MapLibre instance.
 *
 * Imperative by nature: MapLibre owns its own canvas and render loop, so this
 * component creates the map once and then pushes changes into it rather than
 * re-rendering it. Treating a WebGL map as a React-rendered tree would tear down
 * and rebuild every layer on each state change.
 *
 * <p>The current data is held in a ref as well as in props. The map can only
 * accept layer data once its style has loaded, and that moment arrives
 * asynchronously — possibly after the user has already picked a route. Reading
 * the ref means whatever handler gets there first draws what is selected now,
 * rather than the selection that existed when the handler was registered.
 *
 * <p>Everything this map can do is also available from the lists beside it. The
 * map is the pleasant way to use Trackline, not the only way: a WebGL canvas
 * conveys nothing to a screen reader however it is labelled, so the page does
 * not depend on it.
 */
export function MapCanvas({
  shape,
  stops,
  routeColour,
  onStopSelect,
  selectedStopId,
}: MapCanvasProps) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const styled = useRef(false);
  const appliedStyle = useRef<string | null>(null);
  const theme = useSyncExternalStore(subscribe, getTheme, getServerTheme);

  const data = useRef<MapData>({ shape, stops, routeColour, selectedStopId });
  const onStopSelectRef = useRef(onStopSelect);

  // Refreshed after every render, with no dependency list, and declared before
  // the effects that read it so it is already current when they run. Assigning
  // during render instead would be a write to a ref React may discard.
  useEffect(() => {
    data.current = { shape, stops, routeColour, selectedStopId };
    onStopSelectRef.current = onStopSelect;
  });

  // Created once. Later changes are pushed in by the effects that follow.
  useEffect(() => {
    if (!container.current || map.current) {
      return;
    }

    appliedStyle.current = mapStyleUrl(getTheme() ?? "light");

    const instance = new MapLibreMap({
      container: container.current,
      style: appliedStyle.current,
      center: TORONTO_CENTRE,
      zoom: DEFAULT_ZOOM,
      maxBounds: MAX_BOUNDS,
      attributionControl: { compact: true },
      // Someone who has asked for less motion should not be flown across the city.
      fadeDuration: prefersReducedMotion() ? 0 : 300,
    });

    instance.addControl(new NavigationControl({ showCompass: false }), "top-right");
    instance.addControl(new ScaleControl({ unit: "metric" }), "bottom-left");

    instance.on("load", () => {
      styled.current = true;
      addLayers(instance);
      applyData(instance, data.current);
      fitTo(instance, data.current.shape);
    });

    instance.on("click", STOP_LAYER, (event: MapLayerMouseEvent) => {
      const stopId = event.features?.[0]?.properties?.["stopId"];
      if (typeof stopId === "string") {
        onStopSelectRef.current(stopId);
      }
    });
    instance.on("mouseenter", STOP_LAYER, () => {
      instance.getCanvas().style.cursor = "pointer";
    });
    instance.on("mouseleave", STOP_LAYER, () => {
      instance.getCanvas().style.cursor = "";
    });

    // The container is sized in dvh, which settles after the map is created and
    // again whenever the browser chrome shows or hides. MapLibre reads its size
    // once at construction, so without this it keeps rendering into the original
    // rectangle and the tiles stop part-way across a canvas that has since grown.
    const resizeObserver = new ResizeObserver(() => instance.resize());
    resizeObserver.observe(container.current);

    map.current = instance;

    return () => {
      resizeObserver.disconnect();
      instance.remove();
      map.current = null;
      styled.current = false;
    };
  }, []);

  // Swapping the style discards every layer, so they are rebuilt afterwards.
  // Compared against the style actually in use rather than guarded on the first
  // run, so that a theme toggled before the map finished loading is not lost and
  // an unchanged theme does not trigger a pointless style reload.
  useEffect(() => {
    const instance = map.current;
    const next = theme === null ? null : mapStyleUrl(theme);
    if (!instance || next === null || next === appliedStyle.current) {
      return;
    }

    appliedStyle.current = next;
    styled.current = false;
    instance.setStyle(next);
    instance.once("styledata", () => {
      styled.current = true;
      addLayers(instance);
      applyData(instance, data.current);
    });
  }, [theme]);

  useEffect(() => {
    const instance = map.current;
    if (!instance || !styled.current) {
      return;
    }
    applyData(instance, { shape, stops, routeColour, selectedStopId });
  }, [shape, stops, routeColour, selectedStopId]);

  // Separate from the data effect: re-framing the map on a stop selection would
  // yank the view away from whatever the user was looking at.
  useEffect(() => {
    if (map.current && styled.current) {
      fitTo(map.current, shape);
    }
  }, [shape]);

  return (
    // Deliberately not aria-hidden. The canvas itself conveys nothing to a
    // screen reader, but MapLibre puts real focusable buttons inside this
    // container, and hiding focusable controls from assistive technology while
    // leaving them in the tab order is worse than not hiding them: it produces
    // a stop with no name. The lists beside the map carry the same information,
    // so nothing here is the only way to reach anything.
    <div ref={container} data-testid="map-canvas" className="size-full" />
  );
}

function addLayers(instance: MapLibreMap) {
  const colours = mapColours();

  if (!instance.getSource(ROUTE_SOURCE)) {
    instance.addSource(ROUTE_SOURCE, { type: "geojson", data: emptyCollection() });
  }
  if (!instance.getSource(STOP_SOURCE)) {
    instance.addSource(STOP_SOURCE, { type: "geojson", data: emptyCollection() });
  }

  // A casing under the line keeps a route legible over both pale and dark
  // basemaps without altering the route's own colour, which carries meaning.
  if (!instance.getLayer(ROUTE_CASING_LAYER)) {
    instance.addLayer({
      id: ROUTE_CASING_LAYER,
      type: "line",
      source: ROUTE_SOURCE,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": colours.casing,
        "line-opacity": 0.7,
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 5.5, 14, 11],
      },
    });
  }

  if (!instance.getLayer(ROUTE_LAYER)) {
    instance.addLayer({
      id: ROUTE_LAYER,
      type: "line",
      source: ROUTE_SOURCE,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        // The feed's own route colour when it published one, the interface accent
        // otherwise. All 236 TTC routes publish one, so the fallback is for safety.
        "line-color": ["coalesce", ["get", "colour"], colours.route],
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 2.5, 14, 6],
      },
    });
  }

  if (!instance.getLayer(STOP_LAYER)) {
    instance.addLayer({
      id: STOP_LAYER,
      type: "circle",
      source: STOP_SOURCE,
      // Hidden at city zoom, where sixty stops merge into a smear that hides the
      // route beneath them.
      minzoom: STOP_VISIBILITY_ZOOM,
      paint: {
        "circle-radius": ["case", ["get", "selected"], 8, 4.5],
        "circle-color": ["case", ["get", "selected"], colours.stopSelected, colours.stopFill],
        "circle-stroke-color": colours.stopStroke,
        "circle-stroke-width": 1.5,
      },
    });
  }
}

function applyData(instance: MapLibreMap, { shape, stops, routeColour, selectedStopId }: MapData) {
  const route = instance.getSource(ROUTE_SOURCE) as GeoJSONSource | undefined;
  if (route) {
    route.setData(
      shape === null
        ? emptyCollection()
        : {
            type: "FeatureCollection",
            features: [
              {
                type: "Feature",
                properties: { colour: routeColour === null ? null : `#${routeColour}` },
                geometry: shape.geometry,
              },
            ],
          },
    );
  }

  const stopSource = instance.getSource(STOP_SOURCE) as GeoJSONSource | undefined;
  if (stopSource) {
    stopSource.setData({
      type: "FeatureCollection",
      features: stops.map((stop) => ({
        type: "Feature",
        properties: {
          stopId: stop.stopId,
          name: stop.name,
          selected: stop.stopId === selectedStopId,
        },
        geometry: { type: "Point", coordinates: [stop.longitude, stop.latitude] },
      })),
    });
  }
}

/** Frames the selected route. A route can cross the city, so a fixed zoom will not do. */
function fitTo(instance: MapLibreMap, shape: RouteShape | null) {
  if (!shape || shape.geometry.coordinates.length === 0) {
    return;
  }

  const bounds = new LngLatBounds();
  for (const coordinate of shape.geometry.coordinates) {
    bounds.extend(coordinate);
  }

  instance.fitBounds(bounds, {
    padding: 48,
    duration: prefersReducedMotion() ? 0 : 600,
  });
}

function emptyCollection() {
  return { type: "FeatureCollection" as const, features: [] };
}

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
