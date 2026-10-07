import type { Theme } from "@/lib/theme";

/**
 * Which basemap to draw under the transit data.
 *
 * OpenFreeMap needs no API key and bills nothing, which is what makes a public
 * portfolio deployment viable. Both URLs are configurable so the whole basemap can
 * be swapped — to a paid provider, or to self-hosted tiles — without touching any
 * component.
 *
 * The light and dark styles are deliberately the muted ones: the basemap is a
 * backdrop for routes and vehicles, and a colourful one would compete with the
 * route colours that actually carry meaning.
 */

const DEFAULT_LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron";
const DEFAULT_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark";

export function mapStyleUrl(theme: Theme): string {
  if (theme === "dark") {
    return process.env.NEXT_PUBLIC_MAP_STYLE_DARK_URL ?? DEFAULT_DARK_STYLE;
  }
  return process.env.NEXT_PUBLIC_MAP_STYLE_URL ?? DEFAULT_LIGHT_STYLE;
}

/** Downtown Toronto, where a first-time visitor expects the map to open. */
export const TORONTO_CENTRE: [number, number] = [-79.3832, 43.6532];

/** Shows the city without the surrounding region. */
export const DEFAULT_ZOOM = 11;

/**
 * Below this, stop markers are hidden.
 *
 * A route has up to sixty stops; drawn at city zoom they merge into a line of
 * overlapping dots that hides the route underneath without conveying anything.
 */
export const STOP_VISIBILITY_ZOOM = 13;

/** Keeps the map over Toronto rather than letting a stray gesture leave the city. */
export const MAX_BOUNDS: [[number, number], [number, number]] = [
  [-80.2, 43.3],
  [-78.6, 44.2],
];

/**
 * Resolves a design token to a concrete colour.
 *
 * MapLibre paint properties are evaluated in WebGL and cannot read a CSS custom
 * property, so the token has to be turned into a literal before it is handed
 * over. Reading it from the document rather than restating the hex values here
 * means the map cannot drift away from the rest of the interface when a token
 * changes, and it picks up the dark theme for free.
 */
function resolveToken(token: string, fallback: string): string {
  if (typeof document === "undefined") {
    return fallback;
  }
  const value = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
  return value.length > 0 ? value : fallback;
}

/** Colours the map layers use, resolved against whichever theme is active. */
export type MapColours = {
  route: string;
  casing: string;
  stopFill: string;
  stopStroke: string;
  stopSelected: string;
};

export function mapColours(): MapColours {
  return {
    route: resolveToken("--color-accent", "#0a58cf"),
    // The page background, used as a halo so a route stays separated from the
    // basemap without tinting the route colour, which carries meaning.
    casing: resolveToken("--color-surface", "#ffffff"),
    stopFill: resolveToken("--color-surface-raised", "#ffffff"),
    stopStroke: resolveToken("--color-ink", "#12171d"),
    stopSelected: resolveToken("--color-accent", "#0a58cf"),
  };
}
