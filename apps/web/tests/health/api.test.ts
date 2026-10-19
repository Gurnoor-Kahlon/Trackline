import { beforeEach, describe, expect, it, vi } from "vitest";

import { fetchEnvelope } from "@/lib/api/client";
import { fetchRouteBunching } from "@/lib/api/bunching";
import { fetchHealthRoutes, fetchRouteHealth } from "@/lib/api/health";
import { fetchRouteReliability } from "@/lib/api/reliability";

vi.mock("@/lib/api/client", () => ({ fetchEnvelope: vi.fn() }));

const fetchMock = vi.mocked(fetchEnvelope);

describe("the health API client", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({} as never);
  });

  it("asks for one route's health by its encoded id", async () => {
    await fetchRouteHealth("501/odd id");

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501%2Fodd%20id/health", {});
  });

  it("passes a cancellation signal through", async () => {
    const controller = new AbortController();

    await fetchRouteHealth("501", controller.signal);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501/health", { signal: controller.signal });
  });

  it("asks for every route when no state is given", async () => {
    await fetchHealthRoutes();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/health/routes", {});
  });

  it("narrows to the states asked for, repeating the parameter as the API expects", async () => {
    await fetchHealthRoutes(["UNSTABLE", "MAJOR_DISRUPTION"]);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/health/routes?state=UNSTABLE&state=MAJOR_DISRUPTION", {});
  });

  it("asks for a routes bunching by its encoded id", async () => {
    await fetchRouteBunching("501/odd id");

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501%2Fodd%20id/bunching", {});
  });

  it("passes a cancellation signal through for bunching too", async () => {
    const controller = new AbortController();

    await fetchRouteBunching("501", controller.signal);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501/bunching", { signal: controller.signal });
  });

  it("asks for a route's reliability over a window by its encoded id", async () => {
    await fetchRouteReliability("501/odd id", 30);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501%2Fodd%20id/reliability?window=30d", {});
  });

  it("passes a cancellation signal through for reliability too", async () => {
    const controller = new AbortController();

    await fetchRouteReliability("501", 7, controller.signal);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/routes/501/reliability?window=7d", {
      signal: controller.signal,
    });
  });
});
