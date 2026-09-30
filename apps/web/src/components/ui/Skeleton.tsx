import { cn } from "@/lib/cn";

type SkeletonProps = {
  className?: string | undefined;
  /** Describes what is loading, for assistive technology. */
  label?: string | undefined;
};

/**
 * A loading placeholder.
 *
 * Marked `aria-busy` with a live region label rather than left as a silent grey
 * box, so a screen reader user is told something is on its way. The pulse is
 * removed automatically for anyone who prefers reduced motion.
 */
export function Skeleton({ className, label }: SkeletonProps) {
  return (
    <span
      role="status"
      aria-busy="true"
      className={cn("block animate-pulse rounded-md bg-surface-inset", className)}
    >
      <span className="sr-only">{label ?? "Loading"}</span>
    </span>
  );
}

/** Several skeleton lines, for a list or paragraph placeholder. */
export function SkeletonLines({ lines = 3, label }: { lines?: number; label?: string | undefined }) {
  return (
    <span className="block space-y-2">
      <Skeleton className="h-4 w-3/4" label={label} />
      {Array.from({ length: Math.max(0, lines - 1) }, (_, index) => (
        <Skeleton key={index} className={index % 2 === 0 ? "h-4 w-full" : "h-4 w-5/6"} />
      ))}
    </span>
  );
}
