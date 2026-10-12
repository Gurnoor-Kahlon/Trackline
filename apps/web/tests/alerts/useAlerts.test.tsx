import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ALERT_REFRESH_MS, useAlerts } from "@/lib/alerts/useAlerts";
import { fetchAlerts } from "@/lib/api/alerts";
import { ApiError } from "@/lib/api/client";

import { alert, envelope } from "./helpers";

vi.mock("@/lib/api/alerts", () => ({ fetchAlerts: vi.fn() }));

const fetchMock = vi.mocked(fetchAlerts);

describe("the alerts hook", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(Date.parse("2026-10-12T12:00:05Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts as loading and then holds the alerts with how current they are", async () => {
    fetchMock.mockResolvedValue(envelope([alert()], { total: 7 }));

    const { result } = renderHook(() => useAlerts({ active: true }));

    expect(result.current.status).toBe("loading");
    await waitFor(() => expect(result.current.status).toBe("ready"));
    if (result.current.status !== "ready") {
      throw new Error("not ready");
    }
    expect(result.current.alerts).toHaveLength(1);
    expect(result.current.total).toBe(7);
    expect(result.current.meta.source).toBe("STORED");
    expect(result.current.refreshFailed).toBe(false);
  });

  it("measures the server's clock against the browser's, so ages survive a wrong laptop clock", async () => {
    // The server says it is 12:00:00; this browser thinks it is 12:00:05.
    fetchMock.mockResolvedValue(envelope([alert()]));

    const { result } = renderHook(() => useAlerts({ active: true }));

    await waitFor(() => expect(result.current.status).toBe("ready"));
    if (result.current.status !== "ready") {
      throw new Error("not ready");
    }
    expect(result.current.serverOffsetMs).toBeLessThan(-4_000);
    expect(result.current.serverOffsetMs).toBeGreaterThan(-6_000);
  });

  it("asks again every minute, since that is how often the feed is read", async () => {
    fetchMock.mockResolvedValue(envelope([alert()]));
    renderHook(() => useAlerts({ active: true }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS);
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("keeps what it had, flagged, when a refresh fails, instead of replacing alerts with an error", async () => {
    fetchMock.mockResolvedValueOnce(envelope([alert({ description: "Still in force" })]));
    const { result } = renderHook(() => useAlerts({ active: true }));
    await waitFor(() => expect(result.current.status).toBe("ready"));

    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS);
    });

    expect(result.current.status).toBe("ready");
    if (result.current.status !== "ready") {
      throw new Error("not ready");
    }
    expect(result.current.refreshFailed).toBe(true);
    expect(result.current.alerts[0]?.description).toBe("Still in force");
  });

  it("clears the flag once a later refresh succeeds", async () => {
    fetchMock.mockResolvedValueOnce(envelope([alert()]));
    const { result } = renderHook(() => useAlerts({ active: true }));
    await waitFor(() => expect(result.current.status).toBe("ready"));
    fetchMock.mockRejectedValueOnce(new ApiError("down", 0));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS);
    });
    fetchMock.mockResolvedValue(envelope([alert(), alert()]));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS);
    });

    if (result.current.status !== "ready") {
      throw new Error("not ready");
    }
    expect(result.current.refreshFailed).toBe(false);
    expect(result.current.alerts).toHaveLength(2);
  });

  it("reports an error when there was never anything to keep", async () => {
    fetchMock.mockRejectedValue(new ApiError("Database unavailable", 500, "ref-9"));

    const { result } = renderHook(() => useAlerts({ active: true }));

    await waitFor(() => expect(result.current.status).toBe("error"));
    expect(result.current).toMatchObject({ message: "Database unavailable", errorId: "ref-9" });
  });

  it("says the API could not be reached when it was not", async () => {
    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));

    const { result } = renderHook(() => useAlerts({ active: true }));

    await waitFor(() => expect(result.current.status).toBe("error"));
    expect(result.current).toMatchObject({ message: "The Trackline API could not be reached." });
  });

  it("fetches nothing while disabled", async () => {
    const { result } = renderHook(() => useAlerts({ active: true }, false));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS * 2);
    });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.status).toBe("loading");
  });

  it("starts over, as loading, for a different question and does not show the old answer under it", async () => {
    fetchMock.mockResolvedValue(envelope([alert({ description: "For route 501" })]));
    const { result, rerender } = renderHook(({ route }) => useAlerts({ active: true, route }), {
      initialProps: { route: "501" },
    });
    await waitFor(() => expect(result.current.status).toBe("ready"));

    fetchMock.mockReturnValue(new Promise(() => undefined));
    rerender({ route: "504" });

    expect(result.current.status).toBe("loading");
    expect(fetchMock).toHaveBeenLastCalledWith({ active: true, route: "504" }, expect.any(AbortSignal));
  });

  it("cancels the request and the timer when it is unmounted", async () => {
    let signal: AbortSignal | undefined;
    fetchMock.mockImplementation((_query, given) => {
      signal = given;
      return new Promise(() => undefined);
    });
    const { unmount } = renderHook(() => useAlerts({ active: true }));
    await waitFor(() => expect(signal).toBeDefined());

    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ALERT_REFRESH_MS * 3);
    });

    expect(signal?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("ignores a request that finished after it was cancelled", async () => {
    let finish: (value: ReturnType<typeof envelope>) => void = () => undefined;
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const { result, rerender } = renderHook(({ route }) => useAlerts({ active: true, route }), {
      initialProps: { route: "501" },
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    fetchMock.mockReturnValue(new Promise(() => undefined));
    rerender({ route: "504" });
    // The first request, for 501, completes late.
    await act(async () => {
      finish(envelope([alert({ description: "Late answer for 501" })]));
    });

    expect(result.current.status).toBe("loading");
  });
});
