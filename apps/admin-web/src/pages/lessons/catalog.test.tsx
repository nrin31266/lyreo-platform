import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
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
import { LessonsPage } from "./LessonsPage";
import { ApiError } from "@/lib/api";
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
  deleteCategory: vi.fn(),
  deleteDraft: vi.fn(),
  setVisibility: vi.fn(),
  restoreLesson: vi.fn(),
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
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled(),
  );
  await userEvent.type(screen.getByLabelText("Image URL"), "javascript:alert(1)");
  await userEvent.click(screen.getByRole("button", { name: "Save" }));
  await screen.findByRole("alert");
  expect(api.saveMetadata).not.toHaveBeenCalled();
  await userEvent.clear(screen.getByLabelText("Image URL"));
  await userEvent.type(screen.getByLabelText("Image URL"), "https://example.com/cover.jpg");
  await userEvent.click(screen.getByRole("button", { name: "Save" }));
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
  fireEvent.click(await screen.findByRole('button', {name: 'Sync Channel'}));
  fireEvent.click(await screen.findByRole('button', {name: 'Retry'}));
  await waitFor(() => expect(api.syncChannels).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.syncChannels).mock.calls.map(call => call[0])).toEqual([['UCabcdefghijklmnopqrstuv'],['UCabcdefghijklmnopqrstuv']]);
});

it("localizes one category ID and edits Vietnamese without dropping other translations", async () => {
  vi.mocked(api.fetchCategories).mockResolvedValue([{id:'travel',name:'Travel',lessonCount:0,translations:{vi:'Du lịch',fr:'Voyage'}}]);
  await i18n.changeLanguage('vi');show(<CategoriesPage />);
  expect(await screen.findByRole('button',{name:'Du lịch'})).toBeVisible();
  await userEvent.click(screen.getByRole('button',{name:i18n.t('admin:catalog.editCategoryNamed',{title:'Du lịch'})}));
  expect(screen.getByLabelText(i18n.t('admin:catalog.categoryDefaultName'))).toHaveValue('Travel');
  await userEvent.clear(screen.getByLabelText(i18n.t('admin:catalog.categoryVietnameseName')));
  await userEvent.type(screen.getByLabelText(i18n.t('admin:catalog.categoryVietnameseName')),'Đi du lịch');
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
  expect(screen.getByRole('button',{name:'Delete Used'}).closest('span')).toHaveAttribute('title',expect.stringContaining('Hide'));
  expect(api.deleteChannel).not.toHaveBeenCalled();
});

it("opens category creation in a dialog and retains invalid or failed edits for retry", async () => {
  vi.mocked(api.saveCategory).mockRejectedValueOnce(new ApiError(409,'Duplicate','STATE_CONFLICT','A category with this name already exists')).mockResolvedValueOnce({id:'new',name:'Music',lessonCount:0});
  show(<CategoriesPage />);
  expect(screen.queryByLabelText('Default name (English)')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button',{name:'Add category'}));
  const dialog=screen.getByRole('dialog');
  expect(within(dialog).getByRole('button',{name:'Save'})).toBeDisabled();
  await userEvent.click(within(dialog).getByLabelText('Default name (English)'));
  await userEvent.paste('Music');
  await waitFor(()=>expect(within(dialog).getByRole('button',{name:'Save'})).toBeEnabled());
  await userEvent.click(within(dialog).getByRole('button',{name:'Save'}));
  await within(dialog).findByText('A category with this name already exists');
  expect(within(dialog).getByLabelText('Default name (English)')).toHaveValue('Music');
  await userEvent.click(within(dialog).getByRole('button',{name:'Save'}));
  await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(api.saveCategory).toHaveBeenLastCalledWith('Music',undefined,{});
});
it("confirms category deletion and keeps used categories protected", async () => {
  vi.mocked(api.fetchCategories).mockResolvedValue([{id:'empty',name:'Empty',lessonCount:0},{id:'used',name:'Used',lessonCount:2}]);
  vi.mocked(api.deleteCategory).mockResolvedValue();
  show(<CategoriesPage />);
  expect(await screen.findByRole('button',{name:'Delete Used'})).toBeDisabled();
  await userEvent.click(screen.getByRole('button',{name:'Delete Empty'}));
  expect(api.deleteCategory).not.toHaveBeenCalled();
  const dialog=screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('Empty');
  await userEvent.click(within(dialog).getByRole('button',{name:'Delete'}));
  await waitFor(()=>expect(api.deleteCategory).toHaveBeenCalledWith('empty',expect.anything()));
});
it("searches categories without moving the edit form to the top of the page", async () => {
  vi.mocked(api.fetchCategories).mockResolvedValue([{id:'travel',name:'Travel',lessonCount:0,translations:{vi:'Du lịch'}},{id:'science',name:'Science',lessonCount:0}]);
  show(<CategoriesPage />);
  await screen.findByRole('button',{name:'Science'});
  await userEvent.type(screen.getByRole('textbox',{name:'Search categories'}),'Du lịch');
  expect(screen.queryByRole('button',{name:'Science'})).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button',{name:'Edit Travel'}));
  expect(within(screen.getByRole('dialog')).getByLabelText('Default name (English)')).toHaveValue('Travel');
  expect(within(screen.getByRole('dialog')).getByRole('button',{name:'Save'})).toBeDisabled();
});
const lesson = {id:'lesson-a',title:'A lesson',sourceType:'YOUTUBE',status:'PUBLISHED',startMs:0,endMs:120000,channelId:null,channelTitle:null,updatedAt:'2026-11-17T00:00:00Z',authoring:true,categories:[],description:'',imageUrl:null,visible:true,channelVisible:true,effectiveVisible:true,cefr:'A1',activities:['SHADOWING']};
it("keeps category selections while filtering the metadata picker", async () => {
  vi.mocked(api.fetchCategories).mockResolvedValue([{id:'travel',name:'Travel',lessonCount:0},{id:'weather',name:'Weather',lessonCount:0}]);
  vi.mocked(api.saveMetadata).mockResolvedValue();
  show(<LessonMetadataForm lesson={{...lesson,categories:[{id:'travel',name:'Travel',lessonCount:0}]}} onSaved={()=>{}} onCancel={()=>{}} />);
  await screen.findByRole('checkbox',{name:'Travel'});
  await userEvent.type(screen.getByRole('textbox',{name:'Search categories'}),'Weather');
  await userEvent.click(screen.getByRole('checkbox',{name:'Weather'}));
  await userEvent.clear(screen.getByRole('textbox',{name:'Search categories'}));
  expect(screen.getByRole('checkbox',{name:'Travel'})).toBeChecked();
  expect(screen.getByRole('checkbox',{name:'Weather'})).toBeChecked();
  await userEvent.click(screen.getByRole('button',{name:'Save'}));
  await waitFor(()=>expect(api.saveMetadata).toHaveBeenCalledWith('lesson-a',expect.objectContaining({categoryIds:['travel','weather']})));
});
it("retains metadata edits and selections when reloading failed categories", async () => {
  vi.mocked(api.fetchCategories).mockRejectedValueOnce(new Error('Network unavailable')).mockResolvedValueOnce([{id:'travel',name:'Travel',lessonCount:0}]);
  show(<LessonMetadataForm lesson={{...lesson,categories:[{id:'travel',name:'Travel',lessonCount:0}]}} onSaved={()=>{}} onCancel={()=>{}} />);
  await userEvent.type(screen.getByLabelText('Title'),' edited');
  expect(await screen.findByRole('alert')).toBeVisible();
  expect(screen.getByRole('button',{name:'Save'})).toBeDisabled();
  await userEvent.click(screen.getByRole('button',{name:'Retry'}));
  expect(await screen.findByRole('checkbox',{name:'Travel'})).toBeChecked();
  expect(screen.getByLabelText('Title')).toHaveValue('A lesson edited');
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save'})).toBeEnabled());
});
it("shows one truthful lifecycle badge and keeps deleted or processing lessons protected", async () => {
  vi.mocked(api.fetchLessons).mockResolvedValue({items:[{...lesson,id:'deleted',title:'Deleted lesson',deleted:true},{...lesson,id:'busy',title:'Busy lesson',status:'TRANSCRIPT_REVIEW',processing:true}],total:2,page:0,size:20});
  show(<LessonsPage />);
  const deleted=await screen.findByRole('row',{name:/Deleted lesson/});
  expect(within(deleted).getByText('Deleted')).toBeVisible();
  expect(within(deleted).queryByText('Published')).not.toBeInTheDocument();
  expect(within(deleted).queryByRole('switch')).not.toBeInTheDocument();
  expect(within(deleted).getByRole('button',{name:'Edit information and categories'})).toBeDisabled();
  const busy=screen.getByRole('row',{name:/Busy lesson/});
  expect(within(busy).getByRole('button',{name:'Delete lesson'})).toBeDisabled();
  expect(within(busy).queryByRole('switch')).not.toBeInTheDocument();
});
