import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthForm } from "@/components/auth/AuthForm";
import { useAuth } from "@/components/auth/AuthProvider";
import { AuthError } from "@/lib/auth/types";

import { ANONYMOUS, authValue, authenticated } from "./authHelpers";

const push = vi.fn();
const replace = vi.fn();

vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace }) }));
vi.mock("@/components/auth/AuthProvider", () => ({ useAuth: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);
let auth = authValue(ANONYMOUS);

beforeEach(() => {
  push.mockReset();
  replace.mockReset();
  auth = authValue(ANONYMOUS);
  useAuthMock.mockReturnValue(auth);
});

async function fill(user: ReturnType<typeof userEvent.setup>, email: string, password: string) {
  await user.type(screen.getByLabelText("Email address"), email);
  await user.type(screen.getByLabelText("Password"), password);
}

describe("the sign-in form", () => {
  it("has labelled fields with the right autocomplete hints, so a password manager can help", () => {
    render(<AuthForm mode="login" next="/" />);

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toHaveAttribute("autocomplete", "username");
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("Password")).toHaveAttribute("autocomplete", "current-password");
  });

  it("says plainly that an account is only for saving things", () => {
    render(<AuthForm mode="login" next="/" />);

    expect(screen.getByText(/Everything else on Trackline works without one/)).toBeInTheDocument();
  });

  it("signs in and goes where it was asked to", async () => {
    const user = userEvent.setup();
    auth.signIn.mockResolvedValue(undefined);
    render(<AuthForm mode="login" next="/map?route=501" />);

    await fill(user, "person@example.test", "a-test-passphrase");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(auth.signIn).toHaveBeenCalledWith("login", "person@example.test", "a-test-passphrase");
    await waitFor(() => expect(push).toHaveBeenCalledWith("/map?route=501"));
  });

  it("shows the words the server used when sign-in is refused, and reads them out", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("Invalid email or password.", 401));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "wrong");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
    expect(push).not.toHaveBeenCalled();
  });

  it("keeps the address for another go and clears the password after a refusal", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("Invalid email or password.", 401));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "wrong");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("alert");

    expect(screen.getByLabelText("Email address")).toHaveValue("person@example.test");
    expect(screen.getByLabelText("Password")).toHaveValue("");
  });

  it("says how long to wait, in minutes, when the account has been shut", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("Too many failed sign-in attempts.", 429, 900));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "x");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Try again in about 15 minutes.");
  });

  it("says a single minute in the singular", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("x", 429, 30));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "x");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("about 1 minute.");
  });

  it("disables the button while signing in, so a second press cannot send a second request", async () => {
    const user = userEvent.setup();
    let finish: () => void = () => {};
    auth.signIn.mockReturnValue(new Promise<void>((done) => (finish = done)));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "pw");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const button = await screen.findByRole("button", { name: "Signing in…" });
    expect(button).toBeDisabled();
    finish();
  });

  it("links to registration, keeping where the person was going", () => {
    render(<AuthForm mode="login" next="/map?route=501" />);

    expect(screen.getByRole("link", { name: "Create one" })).toHaveAttribute(
      "href",
      "/register?next=%2Fmap%3Froute%3D501",
    );
  });

  it("does not put a next parameter on that link when the person is only going home", () => {
    render(<AuthForm mode="login" next="/" />);

    expect(screen.getByRole("link", { name: "Create one" })).toHaveAttribute("href", "/register");
  });

  it("sends someone who is already signed in on, without showing the form's outcome", () => {
    useAuthMock.mockReturnValue(authValue(authenticated()));

    render(<AuthForm mode="login" next="/alerts" />);

    expect(replace).toHaveBeenCalledWith("/alerts");
  });

  it("does not send a visitor anywhere", () => {
    render(<AuthForm mode="login" next="/alerts" />);

    expect(replace).not.toHaveBeenCalled();
  });
});

describe("the registration form", () => {
  it("asks for a new password and says what makes one acceptable", () => {
    render(<AuthForm mode="register" next="/" />);

    expect(screen.getByRole("heading", { name: "Create an account" })).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByLabelText("Email address")).toHaveAttribute("autocomplete", "email");
    expect(screen.getByText(/At least 10 characters/)).toBeInTheDocument();
  });

  it("ties the hint to the field so a screen reader hears it", () => {
    render(<AuthForm mode="register" next="/" />);

    const hint = screen.getByText(/At least 10 characters/);
    expect(screen.getByLabelText("Password").getAttribute("aria-describedby")).toContain(hint.id);
  });

  it("registers through the same call", async () => {
    const user = userEvent.setup();
    auth.signIn.mockResolvedValue(undefined);
    render(<AuthForm mode="register" next="/" />);

    await fill(user, "person@example.test", "a-long-enough-passphrase");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(auth.signIn).toHaveBeenCalledWith("register", "person@example.test", "a-long-enough-passphrase");
  });

  it("shows the reason when the password is refused", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("password must be at least 10 characters", 400));
    render(<AuthForm mode="register" next="/" />);

    await fill(user, "person@example.test", "short");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("password must be at least 10 characters");
  });

  it("shows the conflict plainly when the address is taken", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new AuthError("An account with this email address already exists.", 409));
    render(<AuthForm mode="register" next="/" />);

    await fill(user, "person@example.test", "a-long-enough-passphrase");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
  });

  it("links to sign-in", () => {
    render(<AuthForm mode="register" next="/" />);

    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
  });

  it("falls back to a general message for an error that is not ours", async () => {
    const user = userEvent.setup();
    auth.signIn.mockRejectedValue(new Error("boom"));
    render(<AuthForm mode="login" next="/" />);

    await fill(user, "person@example.test", "pw");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong. Try again.");
  });
});
