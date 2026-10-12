"use client";

import { useSyncExternalStore } from "react";

import { cn } from "@/lib/cn";
import type { LiveStore } from "@/lib/live/liveStore";
import { describeLiveStatus, type StatusTone } from "@/lib/live/status";
import type { NetworkStatusResult } from "@/lib/live/useNetworkStatus";
import { useNow } from "@/lib/live/useNow";

type LiveStatusChipProps = {
  store: LiveStore;
  network: NetworkStatusResult;
  className?: string | undefined;
};

const TONE_DOT: Record<StatusTone, string> = {
  live: "bg-status-excellent",
  warning: "bg-stale",
  muted: "bg-ink-subtle",
};

/**
 * How live the map is, always on screen.
 *
 * The visible text changes every second as the age ticks; the announcement does not.
 * A live region that read out "updated 12 seconds ago, 13, 14" would be unusable, so
 * the region carries only the state, and speaks when the state changes.
 */
export function LiveStatusChip({ store, network, className }: LiveStatusChipProps) {
  const state = useSyncExternalStore(store.subscribe, store.getState, store.getState);
  const now = useNow();

  if (now === null) {
    return null;
  }

  const status = describeLiveStatus(state, now);
  const fleet = network.summary;

  return (
    <div
      className={cn(
        "tabular inline-flex max-w-full flex-wrap items-center gap-x-2 gap-y-0.5 rounded-pill border border-border",
        "bg-surface-raised/95 px-3 py-1.5 text-xs shadow-sm backdrop-blur-sm",
        className,
      )}
    >
      <span aria-hidden className={cn("size-2 shrink-0 rounded-full", TONE_DOT[status.tone])} />
      <span aria-hidden className="font-medium text-ink">
        {status.label}
      </span>
      {status.detail === null ? null : (
        <span aria-hidden className="text-ink-muted">
          {status.detail}
        </span>
      )}
      {fleet === null ? null : (
        <span aria-hidden className="text-ink-subtle">
          {fleet.vehiclesTracked.toLocaleString("en-CA")} vehicles on {fleet.routesReporting} routes
        </span>
      )}
      <span role="status" className="sr-only">
        {status.announcement}
      </span>
    </div>
  );
}
