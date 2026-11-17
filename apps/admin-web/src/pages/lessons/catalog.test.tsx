import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { CategoriesPage } from "./CategoriesPage";
import { ChannelsPage } from "./ChannelsPage";
import { OrganizationPage } from "./OrganizationPage";
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
  fetchSyncConfiguration: vi.fn(),
  setChannelVisibility: vi.fn(),
  deleteChannel: vi.fn(),
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
  vi.mocked(api.fetchSyncConfiguration).mockResolvedValue({configured: true});
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
        channelVisible: true,
        effectiveVisible: true,
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
      linkedLessonCount: 2,
      avatarUrl: null,
      syncedAt: null,
      syncError: null,
      syncJobId: null,
      syncStatus: null,
      visible: true,
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

it("keeps categories and channels in one tabbed workspace", async () => {
  show(<OrganizationPage />);
  expect(screen.getByRole('tab', {name: 'Categories'})).toHaveAttribute('aria-selected','true');
  await userEvent.click(screen.getByRole('tab', {name: 'Source channels'}));
  await waitFor(() => expect(screen.getByRole('tab', {name: 'Source channels'})).toHaveAttribute('aria-selected','true'));
  expect(api.fetchChannels).toHaveBeenCalled();
});
it("explains missing synchronization configuration and disables sync", async () => {
  vi.mocked(api.fetchSyncConfiguration).mockResolvedValue({configured: false});
  show(<ChannelsPage />);
  await screen.findByText(/synchronization is not enabled/);
  expect(screen.getByRole('button', {name: 'Sync selected (0)'})).toBeDisabled();
  expect(api.syncChannels).not.toHaveBeenCalled();
});
it("retries the same synchronization instead of just reloading the list", async () => {
  vi.mocked(api.fetchChannels).mockResolvedValue([{id:'UCabcdefghijklmnopqrstuv',title:'Channel',lessonCount:1,linkedLessonCount:1,avatarUrl:null,syncedAt:null,syncError:null,syncJobId:null,syncStatus:null,visible:true}]);
  vi.mocked(api.syncChannels).mockRejectedValueOnce(new Error('Sync failed')).mockResolvedValueOnce({jobId:'retry-job'});
  show(<ChannelsPage />);
  fireEvent.click(await screen.findByRole('button', {name: 'Sync metadata'}));
  fireEvent.click(await screen.findByRole('button', {name: 'Retry'}));
  await waitFor(() => expect(api.syncChannels).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.syncChannels).mock.calls.map(call => call[0])).toEqual([['UCabcdefghijklmnopqrstuv'],['UCabcdefghijklmnopqrstuv']]);
});

it("localizes one category ID and edits Vietnamese without dropping other translations", async () => {
  vi.mocked(api.fetchCategories).mockResolvedValue([{id:'travel',name:'Travel',lessonCount:0,translations:{vi:'Du lịch',fr:'Voyage'}}]);
  await i18n.changeLanguage('vi');show(<CategoriesPage />);
  expect(await screen.findByRole('cell',{name:'Du lịch'})).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:i18n.t('admin:catalog.editMetadata')}));
  expect(screen.getByLabelText(i18n.t('admin:catalog.categoryDefaultName'))).toHaveValue('Travel');
  fireEvent.change(screen.getByLabelText(i18n.t('admin:catalog.categoryVietnameseName')),{target:{value:'Đi du lịch'}});
  await userEvent.click(screen.getByRole('button',{name:i18n.t('admin:catalog.save')}));
  await waitFor(()=>expect(api.saveCategory).toHaveBeenCalledWith('Travel','travel',{vi:'Đi du lịch',fr:'Voyage'}));
  expect(api.categoryName({id:'travel',name:'Travel',lessonCount:0},'vi')).toBe('Travel');
});

it("confirms empty channel deletion, keeps failures open and clears its selection on success", async () => {
  vi.mocked(api.fetchChannels).mockResolvedValue([{id:'empty',title:'Unused channel',lessonCount:0,linkedLessonCount:0,avatarUrl:null,syncedAt:null,syncError:null,syncJobId:null,syncStatus:null,visible:true}]);
  vi.mocked(api.deleteChannel).mockRejectedValueOnce(new Error('Network failed')).mockResolvedValueOnce();
  show(<ChannelsPage />);
  await userEvent.click(await screen.findByRole('checkbox',{name:'Select Unused channel'}));
  await userEvent.click(screen.getByRole('button',{name:'Delete Unused channel'}));
  expect(api.deleteChannel).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog')).toHaveTextContent('Unused channel');
  await userEvent.click(screen.getByRole('button',{name:'Delete channel'}));
  await screen.findByRole('alert');
  expect(screen.getByRole('dialog')).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:'Delete channel'}));
  await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(api.deleteChannel).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('button',{name:'Sync selected (0)'})).toBeDisabled();
});
it("prevents deleting channels with even deleted lessons or active synchronization", async () => {
  const channel={avatarUrl:null,syncedAt:null,syncError:null,syncJobId:null,syncStatus:null,visible:true,lessonCount:0};
  vi.mocked(api.fetchChannels).mockResolvedValue([
    {...channel,id:'used',title:'Used',linkedLessonCount:1},
    {...channel,id:'busy',title:'Busy',linkedLessonCount:0,syncStatus:'RUNNING'},
  ]);
  show(<ChannelsPage />);
  expect(await screen.findByRole('button',{name:'Delete Used'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Delete Busy'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Delete Used'})).toHaveAttribute('title',expect.stringContaining('Hide'));
  expect(api.deleteChannel).not.toHaveBeenCalled();
});
