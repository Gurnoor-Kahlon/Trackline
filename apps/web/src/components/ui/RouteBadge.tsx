import { cn } from "@/lib/cn";
import { routeColours } from "@/lib/format/colour";
import { MODE_LABEL, type TransitMode } from "@/lib/transit/mode";

type Size = "sm" | "md" | "lg" | "xl";

const SIZE_CLASS: Record<Size, string> = {
  sm: "text-route-sm min-w-8 px-1.5 py-0.5",
  md: "text-route-md min-w-11 px-2 py-0.5",
  lg: "text-route-lg min-w-14 px-2.5 py-1",
  xl: "text-route-xl min-w-20 px-3 py-1.5",
};

type RouteBadgeProps = {
  /** Route number as riders know it, for example "501". */
  number: string;
  mode: TransitMode;
  /** Six hex digits from the GTFS feed, without a leading hash. */
  colour?: string | null | undefined;
  /** The feed's text colour; ignored if it does not contrast enough. */
  textColour?: string | null | undefined;
  size?: Size | undefined;
  className?: string | undefined;
};

/**
 * The route number, treated as the primary wayfinding element.
 *
 * Tight tracking and tabular figures keep a column of badges optically aligned,
 * which matters more than it sounds when scanning a list of routes.
 *
 * The mode is announced for assistive technology because "501" alone does not
 * say whether it is a streetcar or a bus, and the colour that conveys this
 * visually is not available to a screen reader.
 */
export function RouteBadge({
  number,
  mode,
  colour,
  textColour,
  size = "md",
  className,
}: RouteBadgeProps) {
  const { background, foreground } = routeColours(colour, textColour);

  return (
    <span
      className={cn(
        "tabular inline-flex items-center justify-center rounded-md font-bold leading-none tracking-tight",
        SIZE_CLASS[size],
        className,
      )}
      style={{ backgroundColor: background, color: foreground }}
    >
      <span className="sr-only">{MODE_LABEL[mode]} route </span>
      {number}
    </span>
  );
}
