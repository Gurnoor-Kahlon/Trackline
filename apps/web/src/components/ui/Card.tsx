import type { ReactNode } from "react";

import { cn } from "@/lib/cn";

type CardProps = {
  children: ReactNode;
  className?: string | undefined;
  /** Renders as a <section>; pass a labelled heading id for landmark naming. */
  as?: "div" | "section" | "article" | "li" | undefined;
  ariaLabelledBy?: string | undefined;
};

/**
 * The standard surface for a block of content. A single hairline border and a
 * raised background carry the separation, without a shadow stack or gradient.
 */
export function Card({ children, className, as = "div", ariaLabelledBy }: CardProps) {
  const Element = as;
  return (
    <Element
      aria-labelledby={ariaLabelledBy}
      className={cn(
        "rounded-card border border-border bg-surface-raised",
        className,
      )}
    >
      {children}
    </Element>
  );
}

type CardHeaderProps = {
  title: ReactNode;
  /** Heading level, so a card nested in a section keeps the outline correct. */
  level?: 2 | 3 | 4 | undefined;
  id?: string | undefined;
  action?: ReactNode;
  className?: string | undefined;
};

export function CardHeader({ title, level = 2, id, action, className }: CardHeaderProps) {
  const Heading = `h${level}` as const;
  return (
    <div className={cn("flex items-center justify-between gap-3 px-4 pt-4", className)}>
      <Heading id={id} className="text-sm font-semibold tracking-wide text-ink-muted uppercase">
        {title}
      </Heading>
      {action}
    </div>
  );
}

export function CardBody({ children, className }: { children: ReactNode; className?: string | undefined }) {
  return <div className={cn("p-4", className)}>{children}</div>;
}
