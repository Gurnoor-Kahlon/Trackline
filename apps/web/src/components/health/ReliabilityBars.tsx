"use client";

import { useState, type KeyboardEvent } from "react";

import { describeSlots, formatShare, type BarSlot } from "@/lib/reliability/chart";

type ReliabilityBarsProps = {
  /** Names what is plotted; with one series there is no legend, so this is what says it. */
  title: string;
  slots: BarSlot[];
  /** Which axis labels to print; every one when omitted. */
  labelEvery?: number;
  /** One line under the picture saying what a missing bar means. */
  note: string;
};

const WIDTH = 320;
const PLOT_HEIGHT = 72;
const TOP = 8;
const LEFT = 34;
const RIGHT = 4;
const AXIS_BAND = 18;
/** A bar never fills its slot: the leftover is air. */
const MAX_BAR = 24;
const GAP = 2;
const HEIGHT = TOP + PLOT_HEIGHT + AXIS_BAND;
const GRID = [0, 0.5, 1];

/**
 * A column chart of one measure, the steady share, across the slots of a day or a week.
 *
 * A single series in one colour, so there is no legend; the title says what is plotted. A slot without
 * enough behind it has no bar, only a dot on the baseline, because a bar at zero would claim every sample
 * was unsteady. Every value is reachable three ways, none of which needs the pointer: the readout under
 * the picture follows the arrow keys, a sentence describes the whole chart to a screen reader, and a
 * table below it lists every slot.
 */
export function ReliabilityBars({ title, slots, labelEvery = 1, note }: ReliabilityBarsProps) {
  const [active, setActive] = useState<number | null>(null);

  const slotWidth = (WIDTH - LEFT - RIGHT) / slots.length;
  const barWidth = Math.min(MAX_BAR, slotWidth - GAP);
  const baseline = TOP + PLOT_HEIGHT;
  const summary = describeSlots(title, slots);
  const current = active === null ? null : (slots[active] ?? null);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const last = slots.length - 1;
    if (event.key === "ArrowRight") {
      setActive((value) => (value === null ? 0 : Math.min(last, value + 1)));
    } else if (event.key === "ArrowLeft") {
      setActive((value) => (value === null ? last : Math.max(0, value - 1)));
    } else if (event.key === "Home") {
      setActive(0);
    } else if (event.key === "End") {
      setActive(last);
    } else if (event.key === "Escape") {
      setActive(null);
    } else {
      return;
    }
    event.preventDefault();
  }

  return (
    <figure className="space-y-1.5">
      <figcaption className="text-xs font-semibold text-ink">{title}</figcaption>

      <div
        role="group"
        tabIndex={0}
        aria-label={`${title}. Use the arrow keys to read each bar.`}
        onKeyDown={onKeyDown}
        onBlur={() => setActive(null)}
        className="rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          role="img"
          aria-label={summary}
          className="block w-full"
          onPointerLeave={() => setActive(null)}
        >
          {GRID.map((fraction) => {
            const y = baseline - fraction * PLOT_HEIGHT;
            return (
              <g key={fraction}>
                <line
                  x1={LEFT}
                  x2={WIDTH - RIGHT}
                  y1={y}
                  y2={y}
                  stroke="var(--color-border)"
                  strokeWidth={1}
                />
                <text x={LEFT - 6} y={y + 3} textAnchor="end" fontSize={9} fill="var(--color-ink-subtle)">
                  {Math.round(fraction * 100)}%
                </text>
              </g>
            );
          })}

          {slots.map((slot, index) => {
            const x = LEFT + index * slotWidth;
            const barX = x + (slotWidth - barWidth) / 2;
            const isActive = index === active;
            // Zero is real data and stays visible as a sliver; only a missing value has no bar.
            const height = slot.value === null ? 0 : Math.max(2, slot.value * PLOT_HEIGHT);
            const radius = Math.min(4, barWidth / 2, height);
            const top = baseline - height;

            return (
              <g key={slot.key} data-testid="bar-slot" data-has-bar={slot.value !== null}>
                {isActive ? (
                  <rect
                    x={x}
                    y={TOP}
                    width={slotWidth}
                    height={PLOT_HEIGHT}
                    fill="var(--color-surface-inset)"
                  />
                ) : null}

                {slot.value === null ? (
                  <circle cx={x + slotWidth / 2} cy={baseline - 2} r={1.5} fill="var(--color-ink-subtle)" />
                ) : (
                  <path
                    // Rounded at the data end, square on the baseline.
                    d={`M${barX},${baseline} V${top + radius} Q${barX},${top} ${barX + radius},${top} H${barX + barWidth - radius} Q${barX + barWidth},${top} ${barX + barWidth},${top + radius} V${baseline} Z`}
                    fill={isActive ? "var(--color-accent-hover)" : "var(--color-accent)"}
                  />
                )}

                {index % labelEvery === 0 ? (
                  <text
                    x={x + slotWidth / 2}
                    y={baseline + 12}
                    textAnchor="middle"
                    fontSize={9}
                    fill="var(--color-ink-subtle)"
                  >
                    {slot.label}
                  </text>
                ) : null}

                {/* The whole slot is the target, so a thin bar is not a pinpoint. */}
                <rect
                  x={x}
                  y={TOP}
                  width={slotWidth}
                  height={PLOT_HEIGHT + AXIS_BAND}
                  fill="transparent"
                  onPointerEnter={() => setActive(index)}
                />
              </g>
            );
          })}
        </svg>
      </div>

      <p aria-live="polite" data-testid="bar-readout" className="min-h-8 text-xs text-ink-muted">
        {current === null ? (
          "Point at a bar or use the arrow keys to read it."
        ) : (
          <>
            <span className="font-semibold text-ink">{current.longLabel}</span>: {current.detail}.
          </>
        )}
      </p>
      <p className="text-xs text-ink-subtle">{note}</p>

      <details className="text-xs">
        <summary className="cursor-pointer text-ink-muted">Show as a table</summary>
        <table className="mt-1 w-full text-left">
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr className="text-ink-muted">
              <th scope="col" className="py-0.5 pr-2 font-medium">
                {slots.length === 24 ? "Hour" : "Day"}
              </th>
              <th scope="col" className="py-0.5 pr-2 font-medium">
                Steady
              </th>
              <th scope="col" className="py-0.5 font-medium">
                Behind it
              </th>
            </tr>
          </thead>
          <tbody>
            {slots.map((slot) => (
              <tr key={slot.key} className="border-t border-border">
                <th scope="row" className="py-0.5 pr-2 font-normal whitespace-nowrap text-ink">
                  {slot.longLabel}
                </th>
                <td className="py-0.5 pr-2 whitespace-nowrap text-ink tabular-nums">
                  {slot.value === null ? "–" : formatShare(slot.value)}
                </td>
                <td className="py-0.5 text-ink-muted">{slot.value === null ? "Not enough yet" : slot.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
