import { transferableAbortController } from "node:util";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createMemoryRouter, Link, RouterProvider } from "react-router-dom";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { UnsavedChangesGuard } from "./UnsavedChangesGuard";
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
it("blocks links and browser back until dirty navigation is confirmed", async () => {
  // React Router uses Node's Request; its signal must come from the same realm.
  vi.stubGlobal("AbortController", transferableAbortController().constructor);
  await i18n.changeLanguage("en");
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const router = createMemoryRouter(
    [
      { path: "/lessons", element: <p>Catalog</p> },
      {
        path: "/edit",
        element: (
          <>
            <UnsavedChangesGuard dirty />
            <Link to="/lessons">Leave editor</Link>
          </>
        ),
      },
    ],
    { initialEntries: ["/lessons", "/edit"], initialIndex: 1 },
  );
  render(
    <I18nextProvider i18n={i18n}>
      <RouterProvider router={router} />
    </I18nextProvider>,
  );
  fireEvent.click(screen.getByRole("link"));
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
  expect(router.state.location.pathname).toBe("/edit");
  await act(async () => {
    await router.navigate(-1);
  });
  expect(router.state.location.pathname).toBe("/edit");
  confirm.mockReturnValue(true);
  fireEvent.click(screen.getByRole("link"));
  await screen.findByText("Catalog");
});
