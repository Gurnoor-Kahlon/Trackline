/**
 * Route colours come from the GTFS feed, which means Trackline does not choose
 * them and cannot assume they are legible. The feed also supplies a text colour,
 * but it is not guaranteed to contrast well against its own background, so the
 * pairing is checked here rather than trusted.
 */

const HEX_PATTERN = /^[0-9a-fA-F]{6}$/;

/** WCAG AA for large/bold text. Route numbers are always rendered bold. */
const MINIMUM_CONTRAST_RATIO = 3;

export type RouteColours = {
  /** CSS colour for the badge background. */
  background: string;
  /** CSS colour for the route number drawn on it. */
  foreground: string;
};

/** Neutral pairing used when the feed gives no colour, or an unusable one. */
const FALLBACK: RouteColours = {
  background: "var(--color-surface-inset)",
  foreground: "var(--color-ink)",
};

/**
 * Relative luminance per WCAG 2.x, used for the contrast ratio below.
 */
function relativeLuminance(hex: string): number {
  const channels = [0, 2, 4].map((offset) => {
    const value = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];

  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

export function contrastRatio(hexA: string, hexB: string): number {
  const a = relativeLuminance(hexA);
  const b = relativeLuminance(hexB);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Chooses the colours for a route badge.
 *
 * If the feed's own text colour does not meet the contrast threshold against the
 * route colour, black or white is substituted — whichever reads better. A route
 * number that cannot be read is worse than one drawn off-brand.
 */
export function routeColours(
  colour: string | null | undefined,
  textColour: string | null | undefined,
): RouteColours {
  if (!colour || !HEX_PATTERN.test(colour)) {
    return FALLBACK;
  }

  const background = colour.toLowerCase();
  const preferred = textColour && HEX_PATTERN.test(textColour) ? textColour.toLowerCase() : null;

  if (preferred && contrastRatio(background, preferred) >= MINIMUM_CONTRAST_RATIO) {
    return { background: `#${background}`, foreground: `#${preferred}` };
  }

  const onWhite = contrastRatio(background, "ffffff");
  const onBlack = contrastRatio(background, "000000");
  return {
    background: `#${background}`,
    foreground: onWhite >= onBlack ? "#ffffff" : "#000000",
  };
}
