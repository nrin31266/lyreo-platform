import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { adminI18nResources } from "@lyreo/i18n";
import { AiSettingsPage } from "./AiSettingsPage";
import * as api from "./ai-settings.api";
vi.mock("./ai-settings.api", () => ({
  fetchAiProviders: vi.fn(),
  fetchAiRoutes: vi.fn(),
  saveAiProvider: vi.fn(),
  removeAiProvider: vi.fn(),
  saveAiRoute: vi.fn(),
}));
let client: QueryClient;
const i18n = createInstance();
beforeEach(async () => {
  vi.resetAllMocks();
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  vi.mocked(api.fetchAiProviders).mockResolvedValue([
    {
      id: "groq",
      code: "GROQ",
      display_name: "Groq",
      base_url: "https://api.groq.com/openai/v1",
      enabled: true,
      connection_status: "UNKNOWN",
      configured: true,
      key_last4: "1234",
    },
    {
      id: "mock",
      code: "DEV_MOCK",
      display_name: "Development mock",
      enabled: false,
      connection_status: "UNKNOWN",
      configured: false,
    },
  ]);
  vi.mocked(api.fetchAiRoutes).mockResolvedValue([
    {
      id: "r1",
      capability: "GENERAL_LLM",
      provider: "GROQ",
      model: "test-model",
      priority: 10,
      is_fallback: false,
      enabled: true,
    },
    {
      id: "r2",
      capability: "STT",
      provider: "DEV_MOCK",
      model: "mock-model",
      priority: 10,
      is_fallback: false,
      enabled: true,
    },
  ]);
  await i18n
    .use(initReactI18next)
    .init({
      resources: adminI18nResources,
      lng: "en",
      fallbackLng: "en",
      ns: ["admin", "common"],
      interpolation: { escapeValue: false },
    });
});
afterEach(() => {
  cleanup();
  client.clear();
});
function show(path = "/settings/ai") {
  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={[path]}>
          <AiSettingsPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}
describe("AI settings", () => {
  it("switches top tabs without losing an unsaved provider key", async () => {
    show();
    await screen.findByText("•••• 1234");
    fireEvent.change(screen.getByLabelText("New API key"), { target: { value: "unsaved-test-key" } });
    await userEvent.click(screen.getByRole("tab", { name: "AI routing" }));
    expect(await screen.findByText("test-model")).toBeVisible();
    expect(screen.queryByLabelText("New API key")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "Providers" }));
    expect(screen.getByLabelText("New API key")).toHaveValue("unsaved-test-key");
    expect(screen.queryByText("test-model")).not.toBeInTheDocument();
    expect(api.saveAiProvider).not.toHaveBeenCalled();
  });
  it("saves a standard provider with only an API key and keeps the secret out of the form afterwards", async () => {
    show();
    await screen.findByText("•••• 1234");
    expect(screen.queryByLabelText("Base URL")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("New API key"), {
      target: { value: "test-new-key" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save connection" }));
    await waitFor(() =>
      expect(api.saveAiProvider).toHaveBeenCalledWith("GROQ", {
        displayName: "Groq",
        baseUrl: "https://api.groq.com/openai/v1",
        apiKey: "test-new-key",
        enabled: true,
      }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("New API key")).toHaveValue(""),
    );
    expect(
      await screen.findByText("AI configuration saved."),
    ).toBeInTheDocument();
  });
  it("hides disabled providers and their routes until requested", async () => {
    show();
    await screen.findByText("•••• 1234");
    expect(screen.queryByText("Development mock")).not.toBeInTheDocument();
    expect(screen.queryByText("mock-model")).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("switch", { name: "Show disabled configurations" }),
    );
    expect(await screen.findByText("Development mock")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "AI routing" }));
    expect(await screen.findByText("mock-model")).toBeInTheDocument();
  });
  it("requires explicit confirmation before removing the credential", async () => {
    show();
    await screen.findByText("•••• 1234");
    fireEvent.click(
      screen.getByRole("button", { name: "Remove Groq connection" }),
    );
    expect(api.removeAiProvider).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole("button", { name: "Remove key and disable" }),
    );
    await waitFor(() =>
      expect(api.removeAiProvider).toHaveBeenCalledWith(
        "GROQ",
        expect.anything(),
      ),
    );
    expect(
      await screen.findByText("API key removed and related routes disabled."),
    ).toBeInTheDocument();
  });
  it("edits route policy without silently creating a different model route", async () => {
    show("/settings/ai#routes");
    await screen.findByText("test-model");
    expect(screen.getByRole("tab", { name: "AI routing" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("button", { name: "Edit test-model" }));
    expect(screen.getByLabelText("Model")).toHaveAttribute("readonly");
    fireEvent.change(screen.getByLabelText("Priority"), {
      target: { value: "2" },
    });
    fireEvent.click(screen.getByRole("switch", { name: "Fallback" }));
    fireEvent.click(screen.getByRole("button", { name: "Save model" }));
    await waitFor(() =>
      expect(api.saveAiRoute).toHaveBeenCalledWith(
        {
          capability: "GENERAL_LLM",
          providerCode: "GROQ",
          model: "test-model",
          priority: 2,
          fallback: true,
          enabled: true,
        },
        expect.anything(),
      ),
    );
  });
});
