/**
 * The first tab stop on every page.
 *
 * Hidden until focused, so a keyboard user can jump past the header and
 * navigation instead of tabbing through them on every page, while a mouse user
 * never sees it.
 */
export function SkipLink() {
  return (
    <a
      href="#main"
      className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-20 focus:rounded-md focus:border focus:border-border focus:bg-surface-raised focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:text-ink"
    >
      Skip to content
    </a>
  );
}
