/**
 * Where to go after signing in, taken from the address and so from whoever built the link.
 *
 * Only a path on this site is followed. A link like {@code /login?next=https://evil.example} would otherwise sign the person
 * in on the real site and then send them to the other one, looking as though it had come from here. A protocol-relative
 * address ({@code //evil.example}) and a backslash (which some browsers treat as a slash) are the usual ways round a check
 * that only looks for a leading slash.
 */
export function safeNext(next: string | null | undefined): string {
  if (typeof next !== "string" || next.length === 0 || next.length > 200) {
    return "/";
  }
  if (!next.startsWith("/") || next.startsWith("//") || next.includes("\\") || hasControlCharacter(next)) {
    return "/";
  }
  // Signing in again from the sign-in page would loop.
  if (next === "/login" || next === "/register" || next.startsWith("/login?") || next.startsWith("/register?")) {
    return "/";
  }
  return next;
}

function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    if (value.charCodeAt(index) < 0x20) {
      return true;
    }
  }
  return false;
}
