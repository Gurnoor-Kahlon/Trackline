import { cn } from "@/lib/cn";
import {
  SERVICE_STATUS_PRESENTATION,
  type ServiceStatus,
} from "@/lib/transit/serviceStatus";

type StatusStyle = {
  /** Background and text colour tokens. */
  className: string;
  /** Distinct outline so status is legible without colour. */
  glyph: "check" | "dash" | "triangle" | "square" | "question";
};

const STATUS_STYLE: Record<ServiceStatus, StatusStyle> = {
  EXCELLENT: {
    className: "bg-status-excellent-quiet text-status-excellent",
    glyph: "check",
  },
  NORMAL: {
    className: "bg-status-normal-quiet text-status-normal",
    glyph: "dash",
  },
  UNSTABLE: {
    className: "bg-status-unstable-quiet text-status-unstable",
    glyph: "triangle",
  },
  MAJOR_DISRUPTION: {
    className: "bg-status-major-quiet text-status-major",
    glyph: "square",
  },
  UNKNOWN: {
    className: "bg-status-unknown-quiet text-status-unknown",
    glyph: "question",
  },
};

function StatusGlyph({ glyph }: { glyph: StatusStyle["glyph"] }) {
  // aria-hidden: the label beside it already carries the meaning.
  const common = {
    width: 12,
    height: 12,
    viewBox: "0 0 12 12",
    "aria-hidden": true,
    className: "shrink-0",
  } as const;

  switch (glyph) {
    case "check":
      return (
        <svg {...common} fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M2 6.5 4.6 9 10 3.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    case "dash":
      return (
        <svg {...common} fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M2.5 6h7" strokeLinecap="round" />
        </svg>
      );
    case "triangle":
      return (
        <svg {...common} fill="currentColor">
          <path d="M6 1.2 11.2 10.4H0.8z" />
        </svg>
      );
    case "square":
      return (
        <svg {...common} fill="currentColor">
          <rect x="1.5" y="1.5" width="9" height="9" rx="1.5" />
        </svg>
      );
    case "question":
      return (
        <svg {...common} fill="none" stroke="currentColor" strokeWidth={1.75}>
          <circle cx="6" cy="6" r="4.6" />
          <path d="M4.7 4.6a1.35 1.35 0 1 1 1.9 1.25c-.4.2-.6.5-.6.9v.2" strokeLinecap="round" />
          <circle cx="6" cy="9" r="0.6" fill="currentColor" stroke="none" />
        </svg>
      );
  }
}

type ServiceStatusPillProps = {
  status: ServiceStatus;
  /** Why the status was assigned, from the API's reason codes. */
  reason?: string | undefined;
  className?: string | undefined;
};

/**
 * A route's current service status.
 *
 * Three signals carry the meaning at once — glyph shape, colour and text label —
 * so none of them is load-bearing on its own. A colour-only indicator would be
 * unreadable for a colour-blind rider and meaningless to a screen reader.
 */
export function ServiceStatusPill({ status, reason, className }: ServiceStatusPillProps) {
  const { label, description } = SERVICE_STATUS_PRESENTATION[status];
  const style = STATUS_STYLE[status];

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-pill px-2.5 py-1 text-sm font-medium",
        style.className,
        className,
      )}
    >
      <StatusGlyph glyph={style.glyph} />
      {label}
      <span className="sr-only">. {reason ?? description}</span>
    </span>
  );
}
