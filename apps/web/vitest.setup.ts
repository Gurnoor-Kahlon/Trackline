import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, beforeAll, vi } from "vitest";

// A test file can opt out of the browser with `// @vitest-environment node`, for server code such as route handlers.
// There is then no document to clean up and nothing here to stub.
const inBrowser = typeof document !== "undefined";

afterEach(() => {
  if (!inBrowser) {
    return;
  }
  cleanup();
  document.documentElement.removeAttribute("data-theme");
  localStorage.clear();
});

beforeAll(() => {
  if (!inBrowser) {
    return;
  }
  // jsdom implements neither of these, and both are load-bearing here: the theme
  // toggle reads the colour-scheme preference, and Sheet drives a native dialog.
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );

  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
      this.open = true;
    };
    HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
      this.open = false;
      this.dispatchEvent(new Event("close"));
    };
  }
});
