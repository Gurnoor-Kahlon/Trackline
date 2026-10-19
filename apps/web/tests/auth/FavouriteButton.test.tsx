import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAuth } from "@/components/auth/AuthProvider";
import { FavouriteButton } from "@/components/favourites/FavouriteButton";
import { useFavourites } from "@/components/favourites/FavouritesProvider";

import { ANONYMOUS, LOADING, authValue, authenticated } from "./authHelpers";

vi.mock("@/components/auth/AuthProvider", () => ({ useAuth: vi.fn() }));
vi.mock("@/components/favourites/FavouritesProvider", () => ({ useFavourites: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);
const useFavouritesMock = vi.mocked(useFavourites);

function favouritesValue(saved: boolean, pending = false) {
  const toggle = vi.fn().mockResolvedValue(undefined);
  useFavouritesMock.mockReturnValue({
    status: "ready",
    favourites: null,
    isSaved: () => saved,
    isPending: () => pending,
    toggle,
    error: null,
  });
  return toggle;
}

beforeEach(() => {
  useAuthMock.mockReset();
  useFavouritesMock.mockReset();
});

describe("saving a route", () => {
  it("draws nothing while it is finding out who is signed in", () => {
    useAuthMock.mockReturnValue(authValue(LOADING));
    favouritesValue(false);

    const { container } = render(<FavouriteButton kind="routes" id="501" name="route 501" signInReturnTo="/map?route=501" />);

    expect(container).toBeEmptyDOMElement();
  });

  it("offers a visitor a way to sign in, and brings them back to the route", () => {
    useAuthMock.mockReturnValue(authValue(ANONYMOUS));
    favouritesValue(false);

    render(<FavouriteButton kind="routes" id="501" name="route 501" signInReturnTo="/map?route=501" />);

    expect(screen.getByRole("link", { name: "Sign in to save this route" })).toHaveAttribute(
      "href",
      "/login?next=%2Fmap%3Froute%3D501",
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("offers a visitor nothing in a crowded list, where a prompt on every row would be noise", () => {
    useAuthMock.mockReturnValue(authValue(ANONYMOUS));
    favouritesValue(false);

    const { container } = render(<FavouriteButton kind="stops" id="3718" name="Union Station" compact />);

    expect(container).toBeEmptyDOMElement();
  });

  it("is a button that is not pressed when the route is not saved", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(false);

    render(<FavouriteButton kind="routes" id="501" name="route 501" />);

    const button = screen.getByRole("button", { name: /Save route 501/ });
    expect(button).toHaveAttribute("aria-pressed", "false");
    expect(button).toHaveTextContent("Save");
  });

  it("says Saved, and is pressed, when it is", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(true);

    render(<FavouriteButton kind="routes" id="501" name="route 501" />);

    const button = screen.getByRole("button", { name: /Remove route 501 from saved routes/ });
    expect(button).toHaveAttribute("aria-pressed", "true");
    expect(button).toHaveTextContent("Saved");
  });

  it("tells saved from not saved by shape as well as by word, so it never rests on colour alone", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(true);
    const saved = render(<FavouriteButton kind="routes" id="501" name="route 501" />);
    const filled = saved.container.querySelector("path")?.getAttribute("fill");
    saved.unmount();

    favouritesValue(false);
    const notSaved = render(<FavouriteButton kind="routes" id="501" name="route 501" />);

    expect(filled).toBe("currentColor");
    expect(notSaved.container.querySelector("path")?.getAttribute("fill")).toBe("none");
  });

  it("toggles when pressed", async () => {
    const user = userEvent.setup();
    useAuthMock.mockReturnValue(authValue(authenticated()));
    const toggle = favouritesValue(false);
    render(<FavouriteButton kind="routes" id="501" name="route 501" />);

    await user.click(screen.getByRole("button"));

    expect(toggle).toHaveBeenCalledWith("routes", "501");
  });

  it("is marked busy while a change is on its way", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(true, true);

    render(<FavouriteButton kind="routes" id="501" name="route 501" />);

    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
  });
});

describe("saving a stop in a list", () => {
  it("is an icon with the stop's name as its accessible name", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(false);

    render(<FavouriteButton kind="stops" id="3718" name="Union Station" compact />);

    const button = screen.getByRole("button", { name: "Save Union Station" });
    expect(button).not.toHaveTextContent("Save");
  });

  it("names the stop when removing it too", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));
    favouritesValue(true);

    render(<FavouriteButton kind="stops" id="3718" name="Union Station" compact />);

    expect(screen.getByRole("button", { name: "Remove Union Station from saved stops" })).toBeInTheDocument();
  });
});
