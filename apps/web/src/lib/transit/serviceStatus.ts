/**
 * Service status vocabulary, mirroring the states the route-health algorithm
 * produces. The algorithm itself is built later; this is the presentation
 * contract it will feed.
 */

export const SERVICE_STATUSES = ["EXCELLENT", "NORMAL", "UNSTABLE", "MAJOR_DISRUPTION", "UNKNOWN"] as const;

export type ServiceStatus = (typeof SERVICE_STATUSES)[number];

export type ServiceStatusPresentation = {
  /** Short label shown beside the glyph. Colour is never the only signal. */
  label: string;
  /** Sentence used for assistive technology and tooltips. */
  description: string;
};

/**
 * UNKNOWN is a first-class state, not an error. Subway routes have no realtime
 * feed at all and a quiet route can have too few vehicles to judge, so "we
 * cannot tell" must be sayable without implying something is broken.
 */
export const SERVICE_STATUS_PRESENTATION: Record<ServiceStatus, ServiceStatusPresentation> = {
  EXCELLENT: {
    label: "Excellent",
    description: "Vehicles are evenly spaced along the route.",
  },
  NORMAL: {
    label: "Normal service",
    description: "Spacing between vehicles is within the usual range.",
  },
  UNSTABLE: {
    label: "Unstable",
    description: "Vehicle spacing is uneven, so waits will be inconsistent.",
  },
  MAJOR_DISRUPTION: {
    label: "Major disruption",
    description: "Service on this route is badly affected.",
  },
  UNKNOWN: {
    label: "Not measured",
    description: "There is not enough live data to judge this route.",
  },
};
