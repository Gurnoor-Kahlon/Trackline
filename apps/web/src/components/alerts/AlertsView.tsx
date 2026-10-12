"use client";

import { useId, useState } from "react";

import { AlertCard } from "@/components/alerts/AlertCard";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { SkeletonLines } from "@/components/ui/Skeleton";
import type { ServiceAlert } from "@/lib/api/types";
import { cn } from "@/lib/cn";
import { filterAlerts, groupByEffect, isFiltering, NO_FILTER, type AlertFilter } from "@/lib/alerts/display";
import { useAlerts, type AlertsResult } from "@/lib/alerts/useAlerts";
import { formatAge } from "@/lib/format/age";
import { MODE_LABEL, TRANSIT_MODES } from "@/lib/transit/mode";
import { useNow } from "@/lib/live/useNow";

/** The API caps a page at 100, and the network has never had that many open at once. */
const ACTIVE_PAGE_SIZE = 100;
const ENDED_PAGE_SIZE = 50;

type View = "active" | "ended";

/**
 * Every service alert, with the filters a rider would reach for.
 *
 * What it will not do is as deliberate as what it does. It does not rank alerts by
 * severity, because the feed publishes none. It groups by effect only when the TTC
 * states one, which today it does not. And it does not present first-seen and
 * gone-from-the-feed as start and end, because the feed gives neither.
 */
export function AlertsView() {
  const [view, setView] = useState<View>("active");
  const [filter, setFilter] = useState<AlertFilter>(NO_FILTER);

  const active = useAlerts({ active: true, size: ACTIVE_PAGE_SIZE });
  // Fetched only once someone asks for it: ended alerts are history, not the page's purpose.
  const ended = useAlerts({ active: false, size: ENDED_PAGE_SIZE }, view === "ended");
  const shown = view === "active" ? active : ended;

  return (
    <div className="space-y-4">
      <p className="max-w-3xl text-sm text-ink-muted">
        The TTC publishes no start or end time for an alert, and states neither a cause nor an
        effect. The times here are when Trackline first and last saw each alert in the feed, so
        they are not when it began or ended.
      </p>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <ViewTabs view={view} onChange={setView} activeCount={active.status === "ready" ? active.total : null} />
        <Filters filter={filter} onChange={setFilter} />
      </div>

      <AlertResults result={shown} filter={filter} view={view} />
    </div>
  );
}

function ViewTabs({
  view,
  onChange,
  activeCount,
}: {
  view: View;
  onChange: (view: View) => void;
  activeCount: number | null;
}) {
  return (
    <div role="group" aria-label="Which alerts" className="flex gap-1">
      {(["active", "ended"] as const).map((option) => (
        <button
          key={option}
          type="button"
          aria-pressed={view === option}
          onClick={() => onChange(option)}
          className={cn(
            "rounded-pill border px-3 py-1 text-sm font-medium",
            view === option
              ? "border-accent bg-accent-quiet text-accent"
              : "border-border text-ink-muted hover:bg-surface-inset hover:text-ink",
          )}
        >
          {option === "active" ? (
            <>Active{activeCount === null ? "" : ` (${activeCount})`}</>
          ) : (
            "Ended"
          )}
        </button>
      ))}
    </div>
  );
}

function Filters({ filter, onChange }: { filter: AlertFilter; onChange: (filter: AlertFilter) => void }) {
  const inputId = useId();

  return (
    <div className="flex flex-wrap items-end gap-3">
      <div role="group" aria-label="Mode" className="flex gap-1">
        {TRANSIT_MODES.map((mode) => (
          <button
            key={mode}
            type="button"
            aria-pressed={filter.mode === mode}
            onClick={() => onChange({ ...filter, mode: filter.mode === mode ? null : mode })}
            className={cn(
              "rounded-pill border px-3 py-1 text-sm",
              filter.mode === mode
                ? "border-accent bg-accent-quiet text-accent"
                : "border-border text-ink-muted hover:bg-surface-inset hover:text-ink",
            )}
          >
            {MODE_LABEL[mode]}
          </button>
        ))}
      </div>
      <div>
        <label htmlFor={inputId} className="block text-xs font-medium text-ink-muted">
          Route
        </label>
        <input
          id={inputId}
          type="search"
          value={filter.routeQuery}
          onChange={(event) => onChange({ ...filter, routeQuery: event.target.value })}
          placeholder="501, Queen"
          className="w-36 rounded-md border border-border bg-surface px-2 py-1 text-sm text-ink placeholder:text-ink-subtle"
        />
      </div>
    </div>
  );
}

function AlertResults({ result, filter, view }: { result: AlertsResult; filter: AlertFilter; view: View }) {
  const now = useNow();

  if (result.status === "loading") {
    return <SkeletonLines lines={5} label="Loading alerts" />;
  }
  if (result.status === "error") {
    return (
      <ErrorState
        title="Alerts could not be loaded"
        description={result.message}
        errorId={result.errorId}
      />
    );
  }

  const serverNowMs = now === null ? null : now + result.serverOffsetMs;
  const matching = filterAlerts(result.alerts, filter);
  const noun = view === "active" ? "active" : "ended";

  if (result.meta.source === "NO_DATA") {
    return (
      <EmptyState
        title="Alerts have not been ingested yet"
        description="Trackline has not read the TTC's alerts feed, so there is no list to show. That is different from there being no alerts."
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="space-y-1 text-sm text-ink-muted">
        <p aria-live="polite">
          {matching.length === result.alerts.length
            ? `${result.total} ${noun} alert${result.total === 1 ? "" : "s"}`
            : `${matching.length} of ${result.alerts.length} ${noun} alerts match`}
          {result.total > result.alerts.length
            ? `, showing the newest ${result.alerts.length}`
            : ""}
          {serverNowMs === null || result.meta.feedTimestamp === null
            ? ""
            : ` · feed read ${formatAge((serverNowMs - Date.parse(result.meta.feedTimestamp)) / 1000)} ago`}
        </p>
        {result.meta.stale ? (
          <p role="status" className="font-medium text-stale">
            The alerts feed has not been heard from for a while, so this list may be out of date.
          </p>
        ) : null}
        {result.refreshFailed ? (
          <p role="status" className="font-medium text-stale">
            The latest refresh failed. These alerts are from an earlier one.
          </p>
        ) : null}
      </div>

      {matching.length === 0 ? (
        <EmptyState
          title={
            result.alerts.length === 0
              ? view === "active"
                ? "No active alerts"
                : "No ended alerts"
              : "No alerts match"
          }
          description={
            result.alerts.length === 0
              ? view === "active"
                ? "The TTC's alerts feed currently carries none."
                : "Trackline has not seen any alert leave the feed yet."
              : isFiltering(filter)
                ? "Try a different route or clear the mode filter."
                : undefined
          }
        />
      ) : (
        <GroupedAlerts alerts={matching} serverNowMs={serverNowMs} />
      )}
    </div>
  );
}

function GroupedAlerts({ alerts, serverNowMs }: { alerts: ServiceAlert[]; serverNowMs: number | null }) {
  const groups = groupByEffect(alerts);

  // One group means the TTC stated no distinguishing effect, so there is nothing to group
  // by, and a lone heading would only suggest a structure that is not there.
  if (groups.length === 1) {
    return (
      <ul className="grid gap-2 lg:grid-cols-2">
        {alerts.map((alert) => (
          <AlertCard key={alert.id} alert={alert} serverNowMs={serverNowMs} />
        ))}
      </ul>
    );
  }

  return (
    <div className="space-y-5">
      {groups.map((group) => (
        <section key={group.effect ?? "unstated"} aria-label={group.label}>
          <h2 className="mb-2 text-sm font-semibold tracking-wide text-ink-muted uppercase">
            {group.label} <span className="font-normal">({group.alerts.length})</span>
          </h2>
          <ul className="grid gap-2 lg:grid-cols-2">
            {group.alerts.map((alert) => (
              <AlertCard key={alert.id} alert={alert} serverNowMs={serverNowMs} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
