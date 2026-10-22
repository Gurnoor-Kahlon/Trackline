import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";
import { searchTransit } from "@/lib/api/search";
import type { SearchHit } from "@/lib/api/types";
import { useSearch } from "@/lib/search/useSearch";

vi.mock("@/lib/api/search", () => ({ searchTransit: vi.fn(), SEARCH_RESULT_LIMIT: 15 }));

const searchMock = vi.mocked(searchTransit);

const route501: SearchHit = { kind: "ROUTE", id: "501", label: "501", sublabel: "Queen", score: 1 };
const route41: SearchHit = { kind: "ROUTE", id: "41", label: "41", sublabel: "Keele", score: 1 };

describe("the search hook", () => {
  beforeEach(() => {
    searchMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("is idle, and asks nothing, until something is typed", () => {
    const { result, rerender } = renderHook(({ text }) => useSearch(text, 0), { initialProps: { text: "" } });

    expect(result.current).toEqual({ status: "idle" });

    rerender({ text: "   " });
    expect(result.current).toEqual({ status: "idle" });
    expect(searchMock).not.toHaveBeenCalled();
  });

  it("loads, then holds the hits, asking with the text trimmed", async () => {
    searchMock.mockResolvedValue([route501]);

    const { result } = renderHook(() => useSearch("  501 ", 0));

    expect(result.current.status).toBe("loading");
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current).toEqual({ status: "ready", query: "501", hits: [route501] });
    expect(searchMock).toHaveBeenCalledWith("501", expect.any(AbortSignal));
  });

  it("waits for the typing to pause, and asks once for what was typed", async () => {
    vi.useFakeTimers();
    searchMock.mockResolvedValue([route501]);

    const { rerender } = renderHook(({ text }) => useSearch(text, 250), { initialProps: { text: "5" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    rerender({ text: "50" });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    rerender({ text: "501" });
    expect(searchMock).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(250);
    });

    expect(searchMock).toHaveBeenCalledTimes(1);
    expect(searchMock).toHaveBeenCalledWith("501", expect.any(AbortSignal));
  });

  it("cancels the request for earlier text when the text changes", async () => {
    let firstSignal: AbortSignal | undefined;
    searchMock.mockImplementationOnce((_query, signal) => {
      firstSignal = signal;
      return new Promise<SearchHit[]>(() => undefined);
    });
    searchMock.mockResolvedValue([route41]);

    const { result, rerender } = renderHook(({ text }) => useSearch(text, 0), { initialProps: { text: "q" } });
    await waitFor(() => expect(firstSignal).toBeDefined());

    rerender({ text: "k" });

    expect(firstSignal?.aborted).toBe(true);
    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("never shows a slow answer to earlier text in place of the answer to the current text", async () => {
    let answerEarlier: (hits: SearchHit[]) => void = () => undefined;
    searchMock.mockImplementationOnce(() => new Promise<SearchHit[]>((resolve) => (answerEarlier = resolve)));
    searchMock.mockResolvedValueOnce([route41]);

    const { result, rerender } = renderHook(({ text }) => useSearch(text, 0), { initialProps: { text: "queen" } });
    await waitFor(() => expect(searchMock).toHaveBeenCalledTimes(1));
    rerender({ text: "keele" });
    await waitFor(() => expect(result.current).toMatchObject({ status: "ready", query: "keele" }));

    // The first request answers after the second has: its answer is for text no longer there.
    await act(async () => answerEarlier([route501]));

    expect(result.current).toEqual({ status: "ready", query: "keele", hits: [route41] });
  });

  it("is loading again, not showing the old hits, when the text changes", async () => {
    searchMock.mockResolvedValueOnce([route501]);
    searchMock.mockImplementationOnce(() => new Promise<SearchHit[]>(() => undefined));

    const { result, rerender } = renderHook(({ text }) => useSearch(text, 0), { initialProps: { text: "501" } });
    await waitFor(() => expect(result.current.status).toBe("ready"));

    rerender({ text: "41" });

    expect(result.current.status).toBe("loading");
  });

  it("says so, with what to do, when the API says to slow down", async () => {
    searchMock.mockRejectedValue(new ApiError("Too many requests from this address.", 429));

    const { result } = renderHook(() => useSearch("queen", 0));

    await waitFor(() => expect(result.current.status).toBe("error"));
    expect(result.current).toEqual({
      status: "error",
      message: "You have searched a lot in a short time. Wait a moment and try again.",
    });
  });

  it("tells an unreachable API from one that answered with a fault", async () => {
    searchMock.mockRejectedValueOnce(new ApiError("Could not reach the Trackline API.", 0));
    const unreachable = renderHook(() => useSearch("queen", 0));
    await waitFor(() => expect(unreachable.result.current.status).toBe("error"));
    expect(unreachable.result.current).toEqual({ status: "error", message: "Could not reach Trackline to search." });

    searchMock.mockRejectedValueOnce(new ApiError("The API returned 500.", 500));
    const faulty = renderHook(() => useSearch("queen", 0));
    await waitFor(() => expect(faulty.result.current.status).toBe("error"));
    expect(faulty.result.current).toEqual({ status: "error", message: "Search is not working right now." });
  });
});
