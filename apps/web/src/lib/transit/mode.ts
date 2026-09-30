/**
 * Transit modes, derived from the GTFS `route_type` values the TTC actually
 * publishes: 213 bus (3), 20 streetcar (0) and 3 subway (1).
 */

export const TRANSIT_MODES = ["BUS", "STREETCAR", "SUBWAY"] as const;

export type TransitMode = (typeof TRANSIT_MODES)[number];

export const MODE_LABEL: Record<TransitMode, string> = {
  BUS: "Bus",
  STREETCAR: "Streetcar",
  SUBWAY: "Subway",
};
