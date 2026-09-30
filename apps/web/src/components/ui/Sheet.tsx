"use client";

import { useEffect, useRef, type ReactNode } from "react";

import { cn } from "@/lib/cn";

type SheetProps = {
  open: boolean;
  onClose: () => void;
  /** Accessible name for the dialog. Rendered as the visible heading. */
  title: string;
  children: ReactNode;
  className?: string | undefined;
};

/**
 * A modal panel: a bottom sheet on small screens, a centred dialog above them.
 *
 * Built on the native `<dialog>` element so focus trapping, the top layer, the
 * backdrop and Escape-to-close come from the platform instead of from hand-rolled
 * key handlers and focus loops — which is where accessible dialogs usually go
 * wrong. Only the parts the platform does not cover are added here: syncing the
 * `open` prop, and treating a backdrop click as a dismissal.
 */
export function Sheet({ open, onClose, title, children, className }: SheetProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) {
      return;
    }

    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) {
      return;
    }

    // Both events are listened for on purpose. Escape fires "cancel", and in
    // Chromium no "close" follows it, so listening only for "close" leaves the
    // parent thinking the sheet is still open and it can never be reopened.
    // "close" still matters for a programmatic dialog.close().
    const handleDismiss = () => onClose();
    dialog.addEventListener("close", handleDismiss);
    dialog.addEventListener("cancel", handleDismiss);
    return () => {
      dialog.removeEventListener("close", handleDismiss);
      dialog.removeEventListener("cancel", handleDismiss);
    };
  }, [onClose]);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="sheet-title"
      onClick={(event) => {
        // The backdrop is part of the dialog element, so a click landing on the
        // dialog itself rather than its content means the backdrop was hit.
        if (event.target === dialogRef.current) {
          onClose();
        }
      }}
      className={cn(
        "m-0 max-h-[85dvh] w-full max-w-none overflow-y-auto border border-border bg-surface-raised text-ink",
        "mt-auto rounded-t-2xl p-0",
        "sm:m-auto sm:max-w-lg sm:rounded-card",
        "backdrop:bg-black/40",
        className,
      )}
    >
      <div className="flex items-start justify-between gap-4 border-b border-border px-4 py-3">
        <h2 id="sheet-title" className="text-base font-semibold text-ink">
          {title}
        </h2>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md p-1 text-ink-muted hover:bg-surface-inset hover:text-ink"
        >
          <span className="sr-only">Close</span>
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.75}>
            <path d="M4 4l8 8M12 4l-8 8" strokeLinecap="round" />
          </svg>
        </button>
      </div>
      <div className="px-4 py-4">{children}</div>
    </dialog>
  );
}
