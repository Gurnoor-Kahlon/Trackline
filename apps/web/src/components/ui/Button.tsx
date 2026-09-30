import type { ButtonHTMLAttributes, ReactNode } from "react";

import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "quiet";

const VARIANT_CLASS: Record<Variant, string> = {
  primary: "bg-accent text-surface hover:bg-accent-hover",
  secondary: "border border-border-strong bg-surface-raised text-ink hover:bg-surface-inset",
  quiet: "text-ink-muted hover:bg-surface-inset hover:text-ink",
};

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  children: ReactNode;
};

/**
 * A real <button>, so keyboard activation, focus order and the disabled state all
 * behave without being reimplemented. Focus styling comes from the global
 * :focus-visible rule rather than per-component rings.
 */
export function Button({ variant = "secondary", className, children, type, ...rest }: ButtonProps) {
  return (
    <button
      type={type ?? "button"}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium",
        "transition-colors disabled:cursor-not-allowed disabled:opacity-60",
        VARIANT_CLASS[variant],
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}
