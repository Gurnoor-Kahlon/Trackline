import { act, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider, useAuth } from "@/components/auth/AuthProvider";
import { authenticate, endSession, renew } from "@/lib/auth/client";
import { AuthError, type Session } from "@/lib/auth/types";

vi.mock("@/lib/auth/client", () => ({ authenticate: vi.fn(), endSession: vi.fn(), renew: vi.fn() }));

const renewMock = vi.mocked(renew);
const authenticateMock = vi.mocked(authenticate);
const endSessionMock = vi.mocked(endSession);

const NOW = Date.parse("2026-10-20T12:00:00Z");
const USER = { id: "u-1", email: "p@example.test", createdAt: "2026-10-20T12:00:00Z" };

function session(token: string, lifetimeMs: number): Session {
  return { accessToken: token, expiresAt: Date.now() + lifetimeMs, user: USER };
}

/** Shows what the provider says, and exposes its calls to the test. */
let calls: ReturnType<typeof useAuth>;
const remember = (value: ReturnType<typeof useAuth>) => {
  calls = value;
};
function Probe() {
  const value = useAuth();
  remember(value);
  const { state } = value;
  return (
    <p data-testid="state">
      {state.status === "authenticated" ? `in:${state.session.user.email}:${state.session.accessToken}` : state.status}
    </p>
  );
}

function shown() {
  return screen.getByTestId("state").textContent;
}

async function mount() {
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
  // Let the first renewal settle.
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(NOW);
  renewMock.mockReset();
  authenticateMock.mockReset();
  endSessionMock.mockReset();
  endSessionMock.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("finding out who is signed in", () => {
  it("is loading until the first answer and then a visitor when there is no session", async () => {
    let resolve: (value: Session | null) => void = () => {};
    renewMock.mockReturnValue(new Promise((done) => (resolve = done)));
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(shown()).toBe("loading");

    await act(async () => resolve(null));

    expect(shown()).toBe("anonymous");
  });

  it("signs the person in without their doing anything when a session is found", async () => {
    renewMock.mockResolvedValue(session("tok-1", 900_000));

    await mount();

    expect(shown()).toBe("in:p@example.test:tok-1");
  });

  it("asks once even though development renders every effect twice, since a refresh token can be used only once", async () => {
    renewMock.mockResolvedValue(null);
    render(
      <StrictMode>
        <AuthProvider>
          <Probe />
        </AuthProvider>
      </StrictMode>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(renewMock).toHaveBeenCalledTimes(1);
  });

  it("shows a visitor when the first answer could not be had, so the page still works", async () => {
    renewMock.mockRejectedValue(new AuthError("down", 502));

    await mount();

    expect(shown()).toBe("anonymous");
  });
});

describe("signing in and out", () => {
  it("signs in and shows who", async () => {
    renewMock.mockResolvedValue(null);
    authenticateMock.mockResolvedValue(session("tok-login", 900_000));
    await mount();

    await act(async () => {
      await calls.signIn("login", "p@example.test", "pw");
    });

    expect(authenticateMock).toHaveBeenCalledWith("login", "p@example.test", "pw");
    expect(shown()).toBe("in:p@example.test:tok-login");
  });

  it("stays a visitor and passes the error on when sign-in is refused", async () => {
    renewMock.mockResolvedValue(null);
    authenticateMock.mockRejectedValue(new AuthError("Invalid email or password.", 401));
    await mount();

    await expect(act(async () => calls.signIn("login", "p@example.test", "wrong"))).rejects.toMatchObject({
      message: "Invalid email or password.",
    });

    expect(shown()).toBe("anonymous");
  });

  it("registers through the same call", async () => {
    renewMock.mockResolvedValue(null);
    authenticateMock.mockResolvedValue(session("tok-new", 900_000));
    await mount();

    await act(async () => {
      await calls.signIn("register", "p@example.test", "a-long-enough-password");
    });

    expect(authenticateMock).toHaveBeenCalledWith("register", "p@example.test", "a-long-enough-password");
  });

  it("signs out: tells the server and forgets the person", async () => {
    renewMock.mockResolvedValue(session("tok-1", 900_000));
    await mount();

    await act(async () => calls.signOut());

    expect(endSessionMock).toHaveBeenCalledTimes(1);
    expect(shown()).toBe("anonymous");
    expect(await calls.getAccessToken()).toBeNull();
  });
});

describe("keeping the access token fresh", () => {
  it("renews a minute before the token expires, without being asked", async () => {
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000)).mockResolvedValueOnce(session("tok-2", 900_000));
    await mount();
    expect(shown()).toBe("in:p@example.test:tok-1");

    // Fourteen minutes: still inside the window, so nothing yet.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(13 * 60_000);
    });
    expect(renewMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000 + 100);
    });

    expect(renewMock).toHaveBeenCalledTimes(2);
    expect(shown()).toBe("in:p@example.test:tok-2");
  });

  it("is signed out when a scheduled renewal finds the session over", async () => {
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000)).mockResolvedValueOnce(null);
    await mount();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(14 * 60_000 + 100);
    });

    expect(shown()).toBe("anonymous");
  });

  it("stays signed in when a scheduled renewal merely could not be answered", async () => {
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000)).mockRejectedValueOnce(new AuthError("down", 502));
    await mount();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(14 * 60_000 + 100);
    });

    expect(shown()).toBe("in:p@example.test:tok-1");
  });

  it("never schedules renewals closer together than five seconds, whatever the expiry says", async () => {
    // A token that is already about to expire must not make a tight loop.
    renewMock.mockResolvedValue(session("tok", 1_000));
    await mount();
    renewMock.mockClear();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });

    expect(renewMock).not.toHaveBeenCalled();
  });
});

describe("handing out the token", () => {
  it("returns the current token while it has more than half a minute left", async () => {
    renewMock.mockResolvedValue(session("tok-1", 900_000));
    await mount();
    renewMock.mockClear();

    expect(await calls.getAccessToken()).toBe("tok-1");
    expect(renewMock).not.toHaveBeenCalled();
  });

  it("renews first when the token is nearly out of time", async () => {
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000)).mockResolvedValueOnce(session("tok-2", 900_000));
    await mount();
    vi.setSystemTime(NOW + 900_000 - 20_000);

    const token = await act(async () => calls.getAccessToken());

    expect(token).toBe("tok-2");
  });

  it("renews first when asked to, for a call the API has just refused", async () => {
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000)).mockResolvedValueOnce(session("tok-2", 900_000));
    await mount();

    const token = await act(async () => calls.getAccessToken(true));

    expect(token).toBe("tok-2");
  });

  it("renews once however many callers ask together, since a refresh token can be used only once", async () => {
    let finish: (value: Session | null) => void = () => {};
    renewMock.mockResolvedValueOnce(session("tok-1", 900_000));
    await mount();
    renewMock.mockReturnValueOnce(new Promise((done) => (finish = done)));

    let tokens: (string | null)[] = [];
    await act(async () => {
      const asks = [calls.getAccessToken(true), calls.getAccessToken(true), calls.getAccessToken(true)];
      finish(session("tok-2", 900_000));
      tokens = await Promise.all(asks);
    });

    expect(renewMock).toHaveBeenCalledTimes(2);
    expect(tokens).toEqual(["tok-2", "tok-2", "tok-2"]);
  });

  it("is null for a visitor, without asking the server", async () => {
    renewMock.mockResolvedValue(null);
    await mount();
    renewMock.mockClear();

    expect(await calls.getAccessToken()).toBeNull();
    expect(renewMock).not.toHaveBeenCalled();
  });
});

describe("without a provider", () => {
  it("is a visitor, since every page must work without an account", () => {
    vi.useRealTimers();
    render(<Probe />);

    expect(shown()).toBe("anonymous");
  });
});
