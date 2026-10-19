import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AccountMenu } from "@/components/auth/AccountMenu";
import { useAuth } from "@/components/auth/AuthProvider";

import { ANONYMOUS, LOADING, authValue, authenticated } from "./authHelpers";

vi.mock("@/components/auth/AuthProvider", () => ({ useAuth: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);

beforeEach(() => {
  useAuthMock.mockReset();
});

describe("the account menu in the header", () => {
  it("draws nothing while it is finding out, so a signed-in person never sees Sign in flash up", () => {
    useAuthMock.mockReturnValue(authValue(LOADING));

    const { container } = render(<AccountMenu />);

    expect(container).toBeEmptyDOMElement();
  });

  it("offers a visitor a way to sign in", () => {
    useAuthMock.mockReturnValue(authValue(ANONYMOUS));

    render(<AccountMenu />);

    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
  });

  it("shows who is signed in and a way out", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));

    render(<AccountMenu />);

    expect(screen.getByText("person@example.test")).toBeInTheDocument();
    expect(screen.getByText("Signed in as")).toHaveClass("sr-only");
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("signs out when asked", async () => {
    const user = userEvent.setup();
    const value = authValue(authenticated());
    useAuthMock.mockReturnValue(value);
    render(<AccountMenu />);

    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(value.signOut).toHaveBeenCalledTimes(1);
  });
});
