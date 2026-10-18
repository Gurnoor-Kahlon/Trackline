import type { Bunch, BunchingEvent, DirectionSpacing, SpacingVehicle, VehicleRole } from "@/lib/api/types";
import { formatAge } from "@/lib/format/age";
import { formatDistance } from "@/lib/health/explain";

/**
 * Laying out and describing the spacing strip.
 *
 * All pure: where each marker goes, and the sentences that say the same thing in words. The strip
 * is a picture, and a picture alone is of no use to someone who cannot see it, so everything it
 * shows is also composed here as text from the same figures.
 */

export type StripMarker = {
  vehicleId: string;
  /** Where along the route, from 0 at the start to 1 at the end. */
  fraction: number;
  role: VehicleRole;
};

/** A stretch of the strip a bunch covers. A bunch across the start of a loop has two. */
export type StripSegment = { start: number; end: number };

export type StripBunch = {
  segments: StripSegment[];
  vehicles: number;
  severity: Bunch["severity"];
};

function fractionOf(vehicle: SpacingVehicle, lengthMetres: number): number {
  if (lengthMetres <= 0) {
    return 0;
  }
  return Math.min(1, Math.max(0, vehicle.progressMetres / lengthMetres));
}

export function layoutStrip(direction: DirectionSpacing): { markers: StripMarker[]; bunches: StripBunch[] } {
  const byId = new Map(direction.vehicles.map((vehicle) => [vehicle.vehicleId, vehicle]));

  const markers = direction.vehicles.map((vehicle) => ({
    vehicleId: vehicle.vehicleId,
    fraction: fractionOf(vehicle, direction.lengthMetres),
    role: vehicle.role,
  }));

  const bunches = direction.bunches.map((bunch) => {
    const fractions = bunch.vehicleIds
      .map((id) => byId.get(id))
      .filter((vehicle): vehicle is SpacingVehicle => vehicle !== undefined)
      .map((vehicle) => fractionOf(vehicle, direction.lengthMetres))
      .sort((a, b) => a - b);
    return { segments: segmentsOf(fractions, bunch, direction), vehicles: bunch.vehicleIds.length, severity: bunch.severity };
  });

  return { markers, bunches };
}

function segmentsOf(fractions: number[], bunch: Bunch, direction: DirectionSpacing): StripSegment[] {
  if (fractions.length === 0) {
    return [];
  }
  const first = fractions[0]!;
  const last = fractions.at(-1)!;

  // On a loop a bunch can straddle the point where the route starts and ends. Its members are then
  // at both ends of the strip, far further apart in the picture than the bunch's real span, and it
  // is drawn as two stretches meeting the edges rather than one that crosses the whole route.
  const crossesTheSeam =
    direction.loop &&
    direction.lengthMetres > 0 &&
    (last - first) * direction.lengthMetres > bunch.spanMetres + 1 &&
    last - first > 0.5;
  if (crossesTheSeam) {
    const low = fractions.filter((fraction) => fraction < 0.5);
    const high = fractions.filter((fraction) => fraction >= 0.5);
    return [
      { start: 0, end: low.at(-1) ?? 0 },
      { start: high[0] ?? 1, end: 1 },
    ];
  }
  return [{ start: first, end: last }];
}

const ROLE_WORDS: Record<VehicleRole, string> = {
  SPACED: "in the spacing",
  BUNCHED: "in a group running together",
  AT_TERMINAL: "at a terminal, left out of the spacing",
  STALE: "not reporting recently, left out of the spacing",
};

export function describeRole(role: VehicleRole): string {
  return ROLE_WORDS[role];
}

function plural(count: number, one: string, many: string): string {
  return count === 1 ? one : many;
}

/** What the strip shows, in words: how many were measured, any groups, and what was left out. */
export function describeDirection(direction: DirectionSpacing): string {
  const measured = direction.vehicles.filter((vehicle) => vehicle.role === "SPACED" || vehicle.role === "BUNCHED").length;
  const atTerminal = direction.vehicles.filter((vehicle) => vehicle.role === "AT_TERMINAL").length;
  const stale = direction.vehicles.filter((vehicle) => vehicle.role === "STALE").length;

  const parts: string[] = [];
  if (measured < 3) {
    parts.push(`${measured} ${plural(measured, "vehicle", "vehicles")} measured, too few to say whether any are bunched`);
  } else if (direction.bunches.length === 0) {
    parts.push(`${measured} vehicles measured, none running together while a gap opens elsewhere`);
  } else {
    parts.push(`${measured} vehicles measured`);
    for (const bunch of direction.bunches) {
      parts.push(
        `${bunch.vehicleIds.length} running within ${formatDistance(bunch.spanMetres)} of each other while the largest gap elsewhere is ${formatDistance(bunch.serviceGapMetres)}`,
      );
    }
  }
  if (atTerminal > 0) {
    parts.push(`${atTerminal} at a terminal and left out`);
  }
  if (stale > 0) {
    parts.push(`${stale} not reporting recently and left out`);
  }
  return `${parts.join("; ")}.`;
}

/**
 * When a bunch was seen, without claiming more than was seen. The pack formed at or before the first
 * sighting and broke up at or before the one that found it gone, so the words say "seen" and "apart",
 * never "began".
 */
export function describeEvent(event: BunchingEvent, serverNowMs: number): string {
  const ago = (iso: string) => `${formatAge((serverNowMs - Date.parse(iso)) / 1000)} ago`;
  const checks = `${event.sightings} ${plural(event.sightings, "check", "checks")}`;

  if (event.resolvedAt === null) {
    return `First seen ${ago(event.firstSeenAt)} · still together · seen on ${checks}`;
  }
  return `First seen ${ago(event.firstSeenAt)} · last seen ${ago(event.lastSeenAt)} · apart since ${ago(event.resolvedAt)} · seen on ${checks}`;
}

/** The route's start and end labels for the scale under the strip. */
export function scaleLabels(direction: DirectionSpacing): { start: string; end: string } {
  return { start: "0", end: formatDistance(direction.lengthMetres) };
}
