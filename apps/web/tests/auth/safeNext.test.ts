import { describe, expect, it } from "vitest";

import { safeNext } from "@/lib/auth/safeNext";

describe("where to go after signing in", () => {
  it.each(["/", "/map", "/map?route=501", "/alerts", "/map?route=501&x=y"])("follows the path %s on this site", (path) => {
    expect(safeNext(path)).toBe(path);
  });

  it.each([
    ["https://evil.example", "an address on another site"],
    ["http://evil.example/x", "an http address"],
    ["//evil.example", "a protocol-relative address, which a leading-slash check lets through"],
    ["/\\evil.example", "a backslash, which some browsers read as a slash"],
    ["\\\\evil.example", "a double backslash"],
    ["javascript:alert(1)", "a script address"],
    ["map", "a path with no leading slash"],
    ["/map\nSet-Cookie: x=1", "a control character"],
    ["", "nothing"],
  ])("refuses %s (%s) and goes home", (path) => {
    expect(safeNext(path)).toBe("/");
  });

  it("goes home when nothing was given", () => {
    expect(safeNext(undefined)).toBe("/");
    expect(safeNext(null)).toBe("/");
  });

  it("does not send someone from the sign-in page back to the sign-in page", () => {
    expect(safeNext("/login")).toBe("/");
    expect(safeNext("/login?next=/map")).toBe("/");
    expect(safeNext("/register")).toBe("/");
  });

  it("refuses an address long enough to be an attack and not a place", () => {
    expect(safeNext(`/${"a".repeat(300)}`)).toBe("/");
  });
});
