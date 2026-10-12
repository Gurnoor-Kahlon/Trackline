import { formatAge } from "@/lib/format/age";
import { classifyFreshness, feedAgeSeconds, type LiveState } from "@/lib/live/liveState";

/**
 * What to tell the person looking at the map about how live it is.
 *
 * Every state the data can be in has its own wording, because the failure to avoid is
 * a screen that looks the same whether the vehicles are live or an hour old. A pure
 * function of the state and the time so each wording can be checked exactly.
 */

/** Offline this long, "reconnecting" stops being a blip and is said to be an outage. */
export const UNAVAILABLE_AFTER_SECONDS = 30;

export type StatusTone = "live" | "warning" | "muted";

export type LiveStatus = {
  tone: StatusTone;
  /** Short, always shown. */
  label: string;
  /** Extra, such as how old the data is. Changes every second, so not announced. */
  detail: string | null;
  /**
   * What a screen reader is told. It changes only when the state does, not as the age
   * ticks, so a live region carrying it speaks on transitions and stays quiet between.
   */
  announcement: string;
};

export function describeLiveStatus(state: LiveState, nowMs: number): LiveStatus {
  const age = feedAgeSeconds(state, nowMs);
  const freshness = classifyFreshness(age);
  const ageText = age === null ? null : formatAge(age);

  if (state.connection === "reconnecting") {
    const offlineSeconds = state.disconnectedSinceMs === null ? 0 : (nowMs - state.disconnectedSinceMs) / 1000;
    const outage = offlineSeconds >= UNAVAILABLE_AFTER_SECONDS;
    return {
      tone: "warning",
      label: outage ? "Live data unavailable. Retrying" : "Reconnecting",
      // The last known age keeps growing while offline: the picture on screen gets
      // older each second, and the page says so rather than freezing at the last figure.
      detail: ageText === null ? null : `last update ${ageText} ago`,
      announcement: outage ? "Live data is unavailable. Retrying." : "Reconnecting to live data.",
    };
  }

  if (state.connection === "connecting" && state.lastEventId === null) {
    return {
      tone: "muted",
      label: "Connecting to live data",
      detail: null,
      announcement: "Connecting to live data.",
    };
  }

  switch (freshness) {
    case "unknown":
      return {
        tone: "muted",
        label: "Waiting for data",
        detail: null,
        announcement: "Connected. Waiting for the first data.",
      };
    case "fresh":
      return {
        tone: "live",
        label: "Live",
        detail: `updated ${ageText} ago`,
        announcement: "Live data connected.",
      };
    case "stale":
      return {
        tone: "warning",
        label: `Data is ${ageText} old`,
        detail: null,
        announcement: "Live data is stale.",
      };
    case "lost":
      return {
        tone: "warning",
        label: "Last known positions",
        detail: `${ageText} old`,
        announcement: "Showing last known positions, not live ones.",
      };
  }
}
