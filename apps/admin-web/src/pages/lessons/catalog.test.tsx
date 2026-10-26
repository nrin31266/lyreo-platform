import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { CategoriesPage } from "./CategoriesPage";
import { ChannelsPage } from "./ChannelsPage";
import { LessonMetadataForm } from "./LessonMetadataForm";
import * as api from "./catalog.api";
vi.mock("./catalog.api", async (original) => ({
  ...(await original<typeof import("./catalog.api")>()),
  fetchCategories: vi.fn(),
  fetchChannels: vi.fn(),
  fetchLessons: vi.fn(),
  saveMetadata: vi.fn(),
  saveCategory: vi.fn(),
  syncChannels: vi.fn(),
}));
let client: QueryClient;
beforeEach(async () => {
  vi.clearAllMocks();
  await i18n.changeLanguage("en");
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  vi.mocked(api.fetchCategories).mockResolvedValue([]);
  vi.mocked(api.fetchChannels).mockResolvedValue([]);
});
afterEach(() => {
  cleanup();
  client.clear();
});
function show(element: React.ReactNode) {
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>{element}</MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}
it("shows skeletons while categories load and does not fetch lessons or channels", async () => {
  let finish!: (value: api.Category[]) => void;
  vi.mocked(api.fetchCategories).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  );
  show(<CategoriesPage />);
  expect(screen.getByRole("status")).toBeInTheDocument();
  finish([{ id: "category-a", name: "Travel", lessonCount: 1 }]);
  await screen.findByText("Travel");
  expect(api.fetchLessons).not.toHaveBeenCalled();
  expect(api.fetchChannels).not.toHaveBeenCalled();
});
it("keeps invalid image URLs out of metadata requests and invalidates the catalog after saving", async () => {
  const saved = vi.fn();
  vi.mocked(api.saveMetadata).mockResolvedValue();
  const invalidate = vi.spyOn(client, "invalidateQueries");
  show(
    <LessonMetadataForm
      lesson={{
        id: "lesson-a",
        title: "Lesson",
        status: "PUBLISHED",
        sourceType: "YOUTUBE",
        startMs: 0,
        endMs: 1000,
        channelId: null,
        channelTitle: null,
        updatedAt: "2026-10-26",
        authoring: true,
        categories: [],
        description: "",
        imageUrl: null,
        visible: true,
      }}
      onSaved={saved}
      onCancel={() => {}}
    />,
  );
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled(),
  );
  fireEvent.change(screen.getByLabelText("Image URL"), {
    target: { value: "javascript:alert(1)" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await screen.findByRole("alert");
  expect(api.saveMetadata).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Image URL"), {
    target: { value: "https://example.com/cover.jpg" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(saved).toHaveBeenCalled());
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ["catalog"] });
});
it("selects channels for one explicit synchronization request", async () => {
  vi.mocked(api.fetchChannels).mockResolvedValue([
    {
      id: "UCabcdefghijklmnopqrstuv",
      title: "Channel",
      lessonCount: 2,
      avatarUrl: null,
      syncedAt: null,
      syncError: null,
      syncJobId: null,
      syncStatus: null,
    },
  ]);
  vi.mocked(api.syncChannels).mockResolvedValue({ jobId: "job-a" });
  show(<ChannelsPage />);
  fireEvent.click(
    await screen.findByRole("checkbox", { name: "Select Channel" }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Sync selected (1)" }));
  await waitFor(() =>
    expect(api.syncChannels).toHaveBeenCalledWith(
      ["UCabcdefghijklmnopqrstuv"],
      expect.anything(),
    ),
  );
});
