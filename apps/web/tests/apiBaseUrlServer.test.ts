// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";

import { apiBaseUrl } from "@/lib/api/client";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("where the API is, from the server", () => {
  it("prefers the private address, since the public one may mean something else on a container network", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.test");
    vi.stubEnv("API_INTERNAL_URL", "http://api:8080");

    expect(apiBaseUrl()).toBe("http://api:8080");
  });

  it("uses the public address when there is no private one, as on a developer's machine", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.test");
    vi.stubEnv("API_INTERNAL_URL", "");

    expect(apiBaseUrl()).toBe("https://api.example.test");
  });

  it("uses the local default when neither is set", () => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;
    delete process.env.API_INTERNAL_URL;

    expect(apiBaseUrl()).toBe("http://localhost:8080");
  });
});
