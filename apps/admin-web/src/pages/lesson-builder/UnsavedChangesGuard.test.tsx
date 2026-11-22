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
it('keeps view-only query changes available while guarding navigation away from a saved workspace',async()=>{
  vi.stubGlobal('AbortController',transferableAbortController().constructor);
  await i18n.changeLanguage('en');
  const confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
  const router=createMemoryRouter([{path:'/edit',element:<><UnsavedChangesGuard dirty includeSearch={false} /><Link to="?part=7">Part 7</Link><Link to="/lessons">Leave</Link></>},{path:'/lessons',element:<p>Catalog</p>}],{initialEntries:['/edit?part=1']});
  render(<I18nextProvider i18n={i18n}><RouterProvider router={router} /></I18nextProvider>);
  fireEvent.click(screen.getByRole('link',{name:'Part 7'}));
  await waitFor(()=>expect(router.state.location.search).toBe('?part=7'));
  expect(confirm).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('link',{name:'Leave'}));
  await waitFor(()=>expect(confirm).toHaveBeenCalledOnce());
  expect(router.state.location.pathname).toBe('/edit');
});
it('keeps an unsaved question open until changing the question number is confirmed',async()=>{
  vi.stubGlobal('AbortController',transferableAbortController().constructor);
  await i18n.changeLanguage('en');
  const confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
  const router=createMemoryRouter([{path:'/edit',element:<><UnsavedChangesGuard dirty includeSearch /><Link to="?question=102">Question 102</Link></>}],{initialEntries:['/edit?question=101']});
  render(<I18nextProvider i18n={i18n}><RouterProvider router={router} /></I18nextProvider>);
  fireEvent.click(screen.getByRole('link',{name:'Question 102'}));
  await waitFor(()=>expect(confirm).toHaveBeenCalledOnce());
  expect(router.state.location.search).toBe('?question=101');
  confirm.mockReturnValue(true);
  fireEvent.click(screen.getByRole('link',{name:'Question 102'}));
  await waitFor(()=>expect(router.state.location.search).toBe('?question=102'));
});
