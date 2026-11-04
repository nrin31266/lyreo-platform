import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { ApiError } from "@/lib/api";
import { CatalogError } from "./CatalogFeedback";

beforeEach(async () => { await i18n.changeLanguage("vi"); });
afterEach(cleanup);
function show(props: React.ComponentProps<typeof CatalogError>) {
  return render(<I18nextProvider i18n={i18n}><CatalogError {...props} /></I18nextProvider>);
}
it("explains connection failures in the selected language and retries", () => {
  const retry = vi.fn();
  show({ error: new ApiError(0, "Unable to complete the request"), retry });
  expect(screen.getByRole("alert")).toHaveTextContent("Không kết nối được với máy chủ");
  expect(screen.queryByText("Unable to complete the request")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Thử lại" }));
  expect(retry).toHaveBeenCalledOnce();
});
it("disables retry while fetching and keeps the error reference without internal details", () => {
  show({ error: new ApiError(500, "private stack", "INTERNAL_ERROR", "private stack", "request-42"), retry: vi.fn(), retrying: true });
  expect(screen.getByRole("button", { name: "Đang thử lại…" })).toBeDisabled();
  expect(screen.getByRole("alert")).toHaveTextContent("Mã tra cứu: request-42");
  expect(screen.getByRole("alert")).not.toHaveTextContent("private stack");
});
it.each([401, 403])("explains HTTP %s without an ineffective retry", (status) => {
  show({ error: new ApiError(status, "technical message"), retry: vi.fn() });
  expect(screen.getByRole("alert")).toHaveTextContent(status === 401 ? "đăng nhập lại" : "không có quyền");
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
it("preserves actionable domain validation details", () => {
  show({ error: new ApiError(409, "Category is in use", "STATE_CONFLICT", "Category is in use") });
  expect(screen.getByRole("alert")).toHaveTextContent("Category is in use");
});
it("does not expose unknown exception messages and renders nothing without an error", () => {
  const view = show({ error: new Error("private implementation detail") });
  expect(screen.getByRole("alert")).not.toHaveTextContent("private implementation detail");
  view.unmount();
  show({ error: null });
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
