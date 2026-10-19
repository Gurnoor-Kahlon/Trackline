import { afterEach, describe, expect, it, vi } from "vitest";

import { apiBaseUrl } from "@/lib/api/client";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("where the API is", () => {
  it("is the public address in a browser, whatever the server was told", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.test");
    vi.stubEnv("API_INTERNAL_URL", "http://api:8080");

    // jsdom has a window, as a browser does: the private address means nothing there and must not be used.
    expect(apiBaseUrl()).toBe("https://api.example.test");
  });

  it("falls back to a local API when nothing is configured", () => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;

    expect(apiBaseUrl()).toBe("http://localhost:8080");
  });
});
