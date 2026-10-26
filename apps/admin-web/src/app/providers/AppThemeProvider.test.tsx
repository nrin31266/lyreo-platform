import { semanticThemes } from "@lyreo/design-system";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { AppThemeProvider, useAppTheme } from "./AppThemeProvider";
afterEach(() => {
  cleanup();
  localStorage.clear();
  document.documentElement.classList.remove("dark");
  vi.unstubAllGlobals();
});
function Control() {
  const theme = useAppTheme();
  return (
    <button onClick={() => theme.setPreference("dark")}>{theme.mode}</button>
  );
}
it("applies and restores a selected theme preference", () => {
  vi.stubGlobal("matchMedia", () => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  render(
    <AppThemeProvider>
      <Control />
    </AppThemeProvider>,
  );
  fireEvent.click(screen.getByRole("button"));
  expect(document.documentElement).toHaveClass("dark");
  expect(document.documentElement.style.getPropertyValue("--success")).toBe(semanticThemes.dark.success);
  expect(document.documentElement.style.getPropertyValue("--warning")).toBe(semanticThemes.dark.warning);
  expect(localStorage.getItem("lyreo.theme")).toBe("dark");
  cleanup();
  render(
    <AppThemeProvider>
      <Control />
    </AppThemeProvider>,
  );
  expect(screen.getByRole("button")).toHaveTextContent("dark");
});
