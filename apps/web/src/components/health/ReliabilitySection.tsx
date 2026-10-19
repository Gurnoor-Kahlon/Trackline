"use client";

import { useState } from "react";

import { ReliabilityBars } from "@/components/health/ReliabilityBars";
import { cn } from "@/lib/cn";
import type {
  ReliabilityFigures,
  ReliabilityWindowDays,
  RouteReliability,
} from "@/lib/api/types";
import { useRouteReliability } from "@/lib/health/useHealth";
import {
  DEFAULT_WINDOW,
  WINDOWS,
  describeCoverage,
  drawn,
  explainShortfall,
  formatMoment,
  formatShare,
  formatVariation,
  hourSlots,
  weekdaySlots,
} from "@/lib/reliability/chart";

type ReliabilitySectionProps = {
  routeId: string;
};

/**
 * How steady a route's service has been over a past window.
 *
 * Built from what Trackline recorded, and shown only when enough was. Below the minimum the section
 * says how much there is and how much is needed, rather than a chart drawn from an afternoon; within it,
 * a breakdown bar appears only when enough samples from enough different days stand behind it.
 */
export function ReliabilitySection({ routeId }: ReliabilitySectionProps) {
  const [windowDays, setWindowDays] = useState<ReliabilityWindowDays>(DEFAULT_WINDOW);
  const result = useRouteReliability(routeId, windowDays);

  return (
    <section aria-labelledby="route-reliability-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3
          id="route-reliability-heading"
          className="text-sm font-semibold tracking-wide text-ink-muted uppercase"
        >
          How steady it has been
        </h3>
        <WindowPicker value={windowDays} onChange={setWindowDays} />
      </div>

      {result.status === "loading" ? (
        <p className="text-sm text-ink-muted">Loading history…</p>
      ) : result.status === "error" ? (
        <p role="alert" className="text-sm text-status-major">
          History could not be loaded.
        </p>
      ) : result.data.status === "AVAILABLE" && result.data.summary !== null ? (
        <Available data={result.data} summary={result.data.summary} />
      ) : (
        <Shortfall data={result.data} />
      )}
    </section>
  );
}

function WindowPicker({
  value,
  onChange,
}: {
  value: ReliabilityWindowDays;
  onChange: (days: ReliabilityWindowDays) => void;
}) {
  return (
    <div role="radiogroup" aria-label="Time window" className="inline-flex rounded-md border border-border">
      {WINDOWS.map((window) => (
        <button
          key={window.days}
          type="button"
          role="radio"
          aria-checked={value === window.days}
          onClick={() => onChange(window.days)}
          className={cn(
            "px-2 py-1 text-xs first:rounded-l-md last:rounded-r-md focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent",
            value === window.days
              ? "bg-accent-quiet font-semibold text-accent"
              : "text-ink-muted hover:bg-surface-inset",
          )}
        >
          {window.label}
        </button>
      ))}
    </div>
  );
}

function Shortfall({ data }: { data: RouteReliability }) {
  const shortfall = explainShortfall(data);
  const progress = shortfall.progress;
  const fraction = progress === null ? 0 : Math.min(1, progress.have / progress.need);

  return (
    <div className="space-y-2 rounded-md bg-surface-sunken p-3" data-testid="reliability-shortfall">
      <p className="text-sm font-semibold text-ink">{shortfall.headline}</p>

      {progress === null ? null : (
        <div className="space-y-1">
          <div
            role="img"
            aria-label={`${progress.have.toLocaleString("en-CA")} of ${progress.need.toLocaleString("en-CA")} ${progress.unit}`}
            className="h-1.5 overflow-hidden rounded bg-surface-inset"
          >
            <div className="h-full rounded bg-accent" style={{ width: `${fraction * 100}%` }} />
          </div>
          <p className="text-xs text-ink-muted tabular-nums">
            {progress.have.toLocaleString("en-CA")} of {progress.need.toLocaleString("en-CA")} {progress.unit}
          </p>
        </div>
      )}

      <p className="text-xs text-ink-muted">{shortfall.detail}</p>
    </div>
  );
}

function Available({ data, summary }: { data: RouteReliability; summary: ReliabilityFigures }) {
  const hours = hourSlots(data.byHour, data.needs);
  const weekdays = weekdaySlots(data.byWeekday, data.needs);
  const unjudgedShare = summary.samples === 0 ? 0 : summary.unjudged / summary.samples;

  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-3 gap-2" data-testid="reliability-figures">
        <Figure
          label="Steady"
          value={formatShare(summary.steadyShare)}
          caption="of judged samples were excellent or normal"
        />
        <Figure
          label="Spacing varies by"
          value={formatVariation(summary.variation)}
          caption="on average between vehicles"
        />
        <Figure
          label="Running together"
          value={formatShare(summary.bunchingShare)}
          caption="of judged samples had a bunch"
        />
      </dl>

      <p className="text-xs text-ink-muted">
        {describeCoverage(data.coverage, data.windowDays)}
        {summary.unjudged > 0
          ? ` ${formatShare(unjudgedShare)} of samples could not be judged, with fewer than two vehicles tracked, and are in no share.`
          : ""}
        {data.through === null ? "" : ` Counted through ${formatMoment(data.through)}, Toronto time.`}
      </p>

      <ReliabilityBars
        title="Steady share by hour of the day"
        slots={hours}
        labelEvery={6}
        note={
          drawn(hours) === 0
            ? `An hour is drawn once ${data.needs.daysPerHour} different days stand behind it.`
            : `A dot marks an hour without ${data.needs.cellSamples} judged samples from ${data.needs.daysPerHour} different days.`
        }
      />

      <ReliabilityBars
        title="Steady share by day of the week"
        slots={weekdays}
        note={
          drawn(weekdays) === 0
            ? `A day of the week is drawn once ${data.needs.daysPerWeekday} different days stand behind it, which takes more than a week of recording.`
            : `A dot marks a day without ${data.needs.cellSamples} judged samples from ${data.needs.daysPerWeekday} different days.`
        }
      />
    </div>
  );
}

function Figure({ label, value, caption }: { label: string; value: string; caption: string }) {
  return (
    <div className="rounded-md bg-surface-sunken p-2">
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="text-lg font-semibold text-ink">{value}</dd>
      <dd className="text-xs text-ink-subtle">{caption}</dd>
    </div>
  );
}
