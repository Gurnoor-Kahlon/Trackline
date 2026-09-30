/**
 * Joins class names, dropping anything falsy.
 *
 * Deliberately not `clsx`: the only thing needed here is conditional joining,
 * and Tailwind conflict resolution is handled by not writing conflicting classes
 * rather than by a merge library.
 */
export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(" ");
}
