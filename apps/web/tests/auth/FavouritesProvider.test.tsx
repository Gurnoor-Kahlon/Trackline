import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAuth } from "@/components/auth/AuthProvider";
import { FavouritesProvider, useFavourites } from "@/components/favourites/FavouritesProvider";
import { ApiError } from "@/lib/api/client";
import { fetchFavourites, setFavourite } from "@/lib/favourites/api";
import type { AuthState } from "@/lib/auth/types";

import { ANONYMOUS, authValue, authenticated } from "./authHelpers";
import { favourites, savedRoute, savedStop } from "./favouritesHelpers";

vi.mock("@/components/auth/AuthProvider", () => ({ useAuth: vi.fn() }));
vi.mock("@/lib/favourites/api", () => ({ fetchFavourites: vi.fn(), setFavourite: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);
const fetchMock = vi.mocked(fetchFavourites);
const setMock = vi.mocked(setFavourite);

let seen: ReturnType<typeof useFavourites>;
const remember = (value: ReturnType<typeof useFavourites>) => {
  seen = value;
};
function Probe() {
  const current = useFavourites();
  remember(current);
  return (
    <p data-testid="out">
      {current.status}|{current.favourites?.routes.map((saved) => saved.routeId).join(",") ?? "-"}|{current.error ?? "-"}
    </p>
  );
}

function out() {
  return screen.getByTestId("out").textContent;
}

function as(state: AuthState) {
  useAuthMock.mockReturnValue(authValue(state));
}

function mount(state: AuthState = authenticated()) {
  as(state);
  return render(
    <FavouritesProvider>
      <Probe />
    </FavouritesProvider>,
  );
}

async function settle() {
  await act(async () => {
    await Promise.resolve();
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  setMock.mockReset();
  useAuthMock.mockReset();
});

describe("what a signed-in person has saved", () => {
  it("fetches nothing for a visitor and says it is idle", () => {
    mount(ANONYMOUS);

    expect(out()).toBe("idle|-|-");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("loads the list once someone is signed in", async () => {
    fetchMock.mockResolvedValue(favourites([savedRoute("501"), savedRoute("504")]));

    mount();
    expect(out()).toBe("loading|-|-");
    await settle();

    expect(out()).toBe("ready|501,504|-");
  });

  it("says what went wrong when the list could not be loaded", async () => {
    fetchMock.mockRejectedValue(new ApiError("Could not reach the Trackline API.", 0));

    mount();
    await settle();

    expect(out()).toBe("error|-|Could not reach the Trackline API.");
  });

  it("knows what is saved, for routes and for stops separately", async () => {
    fetchMock.mockResolvedValue(favourites([savedRoute("501")], [savedStop("3718")]));
    mount();
    await settle();

    expect(seen.isSaved("routes", "501")).toBe(true);
    expect(seen.isSaved("routes", "504")).toBe(false);
    expect(seen.isSaved("stops", "3718")).toBe(true);
    // The same id as a route and as a stop are different things.
    expect(seen.isSaved("stops", "501")).toBe(false);
  });
});

describe("saving and removing", () => {
  it("shows a save at once and confirms it by reading the list again", async () => {
    fetchMock.mockResolvedValueOnce(favourites([])).mockResolvedValueOnce(favourites([savedRoute("501")]));
    let finish: () => void = () => {};
    setMock.mockReturnValue(new Promise<void>((done) => (finish = done)));
    mount();
    await settle();

    let toggling: Promise<void> = Promise.resolve();
    act(() => {
      toggling = seen.toggle("routes", "501");
    });

    // Shown before the API has answered.
    expect(seen.isSaved("routes", "501")).toBe(true);
    expect(seen.isPending("routes", "501")).toBe(true);

    await act(async () => {
      finish();
      await toggling;
    });

    expect(setMock).toHaveBeenCalledWith(expect.any(Function), "routes", "501", true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(seen.isPending("routes", "501")).toBe(false);
    expect(out()).toBe("ready|501|-");
  });

  it("removes what is saved", async () => {
    fetchMock.mockResolvedValueOnce(favourites([savedRoute("501")])).mockResolvedValueOnce(favourites([]));
    setMock.mockResolvedValue(undefined);
    mount();
    await settle();

    await act(async () => seen.toggle("routes", "501"));

    expect(setMock).toHaveBeenCalledWith(expect.any(Function), "routes", "501", false);
    expect(seen.isSaved("routes", "501")).toBe(false);
  });

  it("puts it back and says why when the API refuses", async () => {
    fetchMock.mockResolvedValue(favourites([]));
    setMock.mockRejectedValue(new ApiError("You can save at most 100 routes. Remove one first.", 400));
    mount();
    await settle();

    await act(async () => seen.toggle("routes", "501"));

    expect(seen.isSaved("routes", "501")).toBe(false);
    expect(seen.error).toBe("You can save at most 100 routes. Remove one first.");
    expect(seen.isPending("routes", "501")).toBe(false);
  });

  it("clears an earlier error when the next change is asked for", async () => {
    fetchMock.mockResolvedValue(favourites([]));
    setMock.mockRejectedValueOnce(new ApiError("nope", 400)).mockResolvedValueOnce(undefined);
    mount();
    await settle();
    await act(async () => seen.toggle("routes", "501"));
    expect(seen.error).toBe("nope");

    await act(async () => seen.toggle("routes", "504"));

    expect(seen.error).toBeNull();
  });

  it("ignores a second press while the first is on its way, so they cannot race", async () => {
    fetchMock.mockResolvedValue(favourites([]));
    let finish: () => void = () => {};
    setMock.mockReturnValue(new Promise<void>((done) => (finish = done)));
    mount();
    await settle();

    let first: Promise<void> = Promise.resolve();
    act(() => {
      first = seen.toggle("routes", "501");
    });
    await act(async () => seen.toggle("routes", "501"));
    expect(setMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      finish();
      await first;
    });
  });

  it("does nothing for a visitor", async () => {
    mount(ANONYMOUS);

    await act(async () => seen.toggle("routes", "501"));

    expect(setMock).not.toHaveBeenCalled();
  });
});

describe("one person after another", () => {
  it("shows nothing of the last person's list once they sign out", async () => {
    fetchMock.mockResolvedValue(favourites([savedRoute("501")]));
    const view = mount();
    await settle();
    expect(out()).toBe("ready|501|-");

    as(ANONYMOUS);
    view.rerender(
      <FavouritesProvider>
        <Probe />
      </FavouritesProvider>,
    );

    // In the same render as the sign-out: there is no moment in which the list is still on screen.
    expect(out()).toBe("idle|-|-");
    expect(seen.isSaved("routes", "501")).toBe(false);
  });

  it("does not show one person's list to the next while theirs loads", async () => {
    fetchMock.mockResolvedValueOnce(favourites([savedRoute("501")]));
    const view = mount(authenticated({ user: { id: "user-a", email: "a@example.test", createdAt: "x" } }));
    await settle();
    expect(out()).toBe("ready|501|-");

    // The next person signs in, and their list has not arrived yet.
    let arrive: (value: ReturnType<typeof favourites>) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise((done) => (arrive = done)));
    as(authenticated({ user: { id: "user-b", email: "b@example.test", createdAt: "x" } }));
    view.rerender(
      <FavouritesProvider>
        <Probe />
      </FavouritesProvider>,
    );

    expect(out()).toBe("loading|-|-");
    expect(seen.isSaved("routes", "501")).toBe(false);

    await act(async () => arrive(favourites([savedRoute("7")])));
    expect(out()).toBe("ready|7|-");
  });
});

describe("without a provider", () => {
  it("says nothing is saved", () => {
    function Bare() {
      const bare = useFavourites();
      return <p data-testid="out">{`${bare.status}:${bare.isSaved("routes", "501")}`}</p>;
    }
    render(<Bare />);

    expect(out()).toBe("idle:false");
  });
});
