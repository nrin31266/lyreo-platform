import { TooltipProvider } from '@/components/ui/tooltip';
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { adminI18nResources } from "@lyreo/i18n";
import { ApiError } from "@/lib/api";
import * as api from "./lesson-builder.api";
import type { LessonDraft } from "./lesson-builder.types";
import { LessonBuilderPage } from "./LessonBuilderPage";
import * as panels from "@/components/ui/resizable";
import type { PanelProps } from "react-resizable-panels";

vi.mock("./lesson-builder.api", () => ({
  fetchDraft: vi.fn(),
  inspectSource: vi.fn(),
  createDraft: vi.fn(),
  saveTranscript: vi.fn(),
  saveTextSource: vi.fn(),
  synthesizeText: vi.fn(),
  saveVoice: vi.fn(),
  approveTranscript: vi.fn(),
  alignTranscript: vi.fn(),
  reviewKaraoke: vi.fn(),
  prepareTranscript: vi.fn(),
  saveTiming: vi.fn(),
}));
vi.mock("@/pages/lessons/catalog.api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/pages/lessons/catalog.api")>()),
  fetchCategories: vi.fn().mockResolvedValue([]),
  fetchMetadata: vi.fn().mockResolvedValue({id:"lesson-a",title:"A lesson",categories:[],description:"",imageUrl:""}),
}));
vi.mock("@/pages/jobs/jobs.api", () => ({ cancelJob: vi.fn() }));
vi.mock("./AudioPreview", () => ({
  LessonPlayer: ({
    onAvailabilityChange, seek,
  }: {
    onAvailabilityChange?: (ready: boolean) => void;
    seek?: { atMs: number } | null;
  }) => (
    <><button onClick={() => onAvailabilityChange?.(true)}>Ready player</button><output aria-label="Player seek">{seek?.atMs}</output></>
  ),
}));

vi.mock('./VoiceSelect', () => ({VoiceSelect: ({value}:{value:string}) => <button type="button" aria-label="Selected voice">{value}</button>}));

const original: LessonDraft = {
  id: "lesson-a",
  title: "A lesson",
  videoId: "abcdefghijk",
  sourceReference: "https://youtu.be/abcdefghijk",
  startMs: 10000,
  endMs: 130000,
  revision: 1,
  transcriptRevision: 1,
  status: "TRANSCRIPT_REVIEW",
  timingStatus: "DRAFT_VALID",
  approvedRevision: null,
  karaokeReviewedRevision: null,
  semanticRevision: null,
  jobs: [],
  sentences: [
    {
      id: "s1",
      text: "Hello world.",
      usage: "LEARN",
      issues: [],
      vocabulary: [],
      grammar: [],
      hints: [],
      notes: [],
      thoughtGroups: [],
      dictationEligible: true,
      shadowingEligible: true,
      dictationSelected: true,
      shadowingSelected: false,
      dictationPriority: "HIGH",
      shadowingPriority: "MEDIUM",
      words: [
        {
          text: "Hello",
          startMs: 0,
          endMs: 500,
          charStart: 0,
          charEnd: 5,
          tokenStart: 0,
          tokenEnd: 1,
        },
        {
          text: "world",
          startMs: 500,
          endMs: 1000,
          charStart: 6,
          charEnd: 11,
          tokenStart: 1,
          tokenEnd: 2,
        },
      ],
    },
  ],
};
let client: QueryClient;
let current: LessonDraft;
const i18n = createInstance();
beforeEach(async () => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
  current = structuredClone(original);
  vi.mocked(api.fetchDraft).mockImplementation(async () =>
    structuredClone(current),
  );
  await i18n.use(initReactI18next).init({
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
  vi.useRealTimers();
});
async function show(path = "/lessons/lesson-a") {
  await act(async () => {
    render(
      <QueryClientProvider client={client}>
        <I18nextProvider i18n={i18n}><TooltipProvider>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route
                path="/lessons/:lessonId"
                element={<LessonBuilderPage />}
              />
              <Route path="/lessons/new" element={<LessonBuilderPage />} />
            </Routes>
          </MemoryRouter>
        </TooltipProvider></I18nextProvider>
      </QueryClientProvider>,
    );
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
}

async function saveDraft() {
  await act(async () => { fireEvent.click(screen.getByRole('button',{name:'Save transcript'})); });
}

describe("lesson authoring checkpoints", () => {
  it("keeps the same player toggle in sync with panel resize", async () => {
    const Panel = panels.ResizablePanel;
    let onResize: PanelProps['onResize'];
    const renderPanel = vi.spyOn(panels, 'ResizablePanel').mockImplementation(props => {
      if (props.id === 'authoring-player') onResize = props.onResize;
      return <Panel {...props} />;
    });
    try {
      await show();
      const toggle = screen.getByRole('button', { name: 'Collapse player' });
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
      act(() => onResize?.({asPercentage: 0, inPixels: 0}, 'authoring-player', undefined));
      expect(screen.getByRole('button', { name: 'Show player' })).toBe(toggle);
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      act(() => onResize?.({asPercentage: 40, inPixels: 400}, 'authoring-player', undefined));
      expect(toggle).toHaveAccessibleName('Collapse player');
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
    } finally { renderPanel.mockRestore(); }
  });

  it("shows word and timing warnings together in one sentence list", async () => {
    current.timingStatus = "MISSING"; current.alignmentRequired = true;
    current.sentences[0].issues = ["TIMING_COVERAGE_MISMATCH", "LONG_SENTENCE"];
    await show();
    expect(screen.queryByRole('button', {name:'Listen & check'})).not.toBeInTheDocument();
    expect(screen.getByText("The words and playback times do not match. Check the transcript, then align again.")).toBeVisible();
    expect(screen.getByText("This sentence is long. Splitting it can make listening and review easier.")).toBeVisible();
    expect(screen.getByLabelText("Text for sentence 1")).toBeVisible();
    expect(screen.getAllByRole("button", {name:"Save and realign"})).toHaveLength(1);
  });

  it("offers one primary action and clears unsaved changes when the saved words are restored", async () => {
    await show();
    expect(screen.queryByRole('button', {name: 'Save and realign'})).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: 'Save transcript'})).toBeDisabled();
    expect(screen.getByRole('button', {name: 'Save reviewed draft'})).toBeDisabled();
    fireEvent.click(screen.getByRole('button', {name: 'Ready player'}));
    fireEvent.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', {name: 'Save reviewed draft'})).toBeEnabled();
    const input = screen.getByLabelText('Text for sentence 1');
    fireEvent.change(input, {target: {value: 'A correction.'}});
    expect(screen.getByRole('button', {name: 'Save and realign'})).toBeEnabled();
    expect(screen.queryByRole('button', {name: 'Save reviewed draft'})).not.toBeInTheDocument();
    fireEvent.change(input, {target: {value: 'Hello world.'}});
    expect(screen.queryByRole('button', {name: 'Save and realign'})).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: 'Save transcript'})).toBeDisabled();
    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(screen.getByRole('button', {name: 'Save reviewed draft'})).toBeDisabled();
    expect(api.saveTranscript).not.toHaveBeenCalled();
    expect(api.alignTranscript).not.toHaveBeenCalled();
  });

  it("keeps grouped timing as compact information and confirmation beside the save action", async () => {
    current.sentences[0].issues = ['GROUPED_TIMING', 'TIMING_COVERAGE_MISMATCH'];
    await show();
    const grouped = screen.getByRole('button', {name: 'Grouped timing'});
    expect(grouped.closest('.authoring-sentence-heading')).not.toBeNull();
    expect(screen.queryByText(/These words share one time range/)).not.toBeInTheDocument();
    fireEvent.focus(grouped);
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(screen.getByRole('tooltip')).toHaveTextContent('These words share one time range');
    expect(screen.getByText('The words and playback times do not match. Check the transcript, then align again.')).toBeVisible();
    const dock = screen.getByRole('contentinfo');
    expect(within(dock).getByRole('checkbox')).toBeInTheDocument();
    expect(within(dock).getByRole('button', {name: 'Save reviewed draft'})).toBeDisabled();
    expect(screen.queryByText('Edit words, then save and align.')).not.toBeInTheDocument();
  });

  it("opens timing edits directly from the sentence icon", async () => {
    current.approvedRevision = current.transcriptRevision;
    await show();
    fireEvent.click(screen.getByRole('button',{name:'Edit timing'}));
    expect(screen.getByRole('dialog',{name:'Edit timing'})).toBeVisible();
  });

  it("edits all transcript sentences directly without duplicate text or word karaoke", async () => {
    current.sentences.push({ ...structuredClone(current.sentences[0]), id: "s2", text: "A second sentence." });
    await show();
    expect(screen.getByRole("textbox", { name: "Text for sentence 1" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "Text for sentence 2" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Hello world." })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Karaoke words")).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "Text for sentence 2" }), { target: { value: "Edited second sentence." } });
    expect(screen.getByRole("textbox", { name: "Text for sentence 2" })).toHaveValue("Edited second sentence.");
  });

  it.each([0, 1234])("seeks with segment preview anchor %s while word timing still needs alignment", async (atMs) => {
    current.timingStatus = "MISSING";
    current.sentences[0].words = [];
    current.sentences[0].previewStartMs = atMs;
    await show();
    const seek = screen.getByRole("button", { name: "Seek to sentence" });
    expect(seek).toBeEnabled();
    fireEvent.click(seek);
    expect(screen.getByLabelText("Player seek")).toHaveTextContent(String(atMs));
  });

  it('keeps source context beside review without numbered navigation or unfinished steps', async () => {
    await show();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.getByText('Selected source', {selector:'summary'})).toBeInTheDocument();
    expect(screen.queryByRole('button', {name:'Publish lesson'})).not.toBeInTheDocument();
  });

  it("filters word suggestions without exercise selection", async () => {
    current.sentences[0].issues = ["SHORT_SENTENCE", "TIMING_MISSING"];
    current.sentences.push({ ...structuredClone(current.sentences[0]), id: "s2", text: "Another full sentence.", issues: ["TIMING_MISSING"] });
    await show();
    expect(screen.queryByRole("button", { name: "Transcript review help" })).not.toBeInTheDocument();
    const filter = screen.getByRole("button", { name: "Word suggestions (1)" });
    fireEvent.click(filter);
    expect(filter).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("textbox", { name: "Text for sentence 1" })).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "Text for sentence 2" })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', {name:'Sentence 1 visibility'})).not.toBeInTheDocument();
  });

  it('shows legacy hidden text, omits visibility controls and saves the complete transcript', async () => {
    current.sentences.push({...structuredClone(current.sentences[0]),id:'s2',text:'Repeated fragment.',hidden:true});
    await show();
    expect(screen.queryByRole('button',{name:'Sentence 2 visibility'})).not.toBeInTheDocument();
    expect(screen.getByRole('textbox',{name:'Text for sentence 2'})).toHaveValue('Repeated fragment.');
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox',{name:'Text for sentence 1'}),{target:{value:'Hello world!'}});
    await saveDraft();
    expect(api.saveTranscript).toHaveBeenCalledWith('lesson-a',1,[{id:'s1',text:'Hello world!'},{id:'s2',text:'Repeated fragment.'}]);
    expect(api.synthesizeText).not.toHaveBeenCalled();
  });

  it('plays karaoke in the same row and pauses before returning to inline editing', async () => {
    await show();
    fireEvent.click(screen.getByRole('button',{name:'Seek to sentence'}));
    expect(screen.getByRole('button',{name:'Hello'})).toBeVisible();
    expect(screen.queryByLabelText('Text for sentence 1')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'Edit words'}));
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Hello world.');
    expect(screen.queryByRole('button',{name:'Hello'})).not.toBeInTheDocument();
    expect(api.alignTranscript).not.toHaveBeenCalled(); expect(api.approveTranscript).not.toHaveBeenCalled();
  });

  it("explains when the problem-sentence filter has no matches", async () => {
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Word suggestions (0)" }));
    expect(screen.getByText("No sentences need review in the Transcript step.")).toBeVisible();
  });

  it("keeps job details in a bottom sheet and translates alignment failures for that step", async () => {
    current.jobs = [{ jobId: "alignment-failed", operation: "ALIGN", status: "FAILED", errorCode: "AUTHORING_VALIDATION_FAILED", errorMessage: "Invalid word coverage", usage: [{provider: "local", model: "whisper", latencyMs: 1200, audioSeconds: 30}] }];
    await show();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByText("Processing did not complete")).not.toBeInTheDocument();
    const trigger = screen.getByRole("button", { name: "Processing and history" });
    expect(trigger.closest("footer")).toBeInTheDocument();
    expect(screen.getByRole("region", {name: "Processing status"})).toHaveTextContent("Failed");
    fireEvent.click(trigger);
    expect(screen.getByRole("dialog", { name: "Processing and history" })).toHaveAttribute("data-side", "bottom");
    expect(screen.getAllByText("The words could not be matched to the audio. Check the transcript, then retry alignment.").some(element => element.closest("[role=dialog]"))).toBe(true);
    fireEvent.click(screen.getByText("Processing details"));
    expect(screen.getByText("local / whisper")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Details and usage" })).not.toBeInTheDocument();
    expect(screen.queryByText("This processing result could not be used. Your saved work is safe; retry this step.")).not.toBeInTheDocument();
  });

  it('retries alignment from its named status action without preparing the source again', async () => {
    current.approvedRevision=current.transcriptRevision;
    current.jobs=[{jobId:'failed',operation:'ALIGN',status:'FAILED',errorCode:'AUTHORING_VALIDATION_FAILED',usage:[]}];
    vi.mocked(api.alignTranscript).mockResolvedValue({lessonId:current.id,jobId:'retry'});
    await show();
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'Retry alignment'}));});
    expect(api.alignTranscript).toHaveBeenCalledWith('lesson-a',1);
    expect(api.prepareTranscript).not.toHaveBeenCalled();
    expect(api.saveTranscript).not.toHaveBeenCalled();
  });
  it('allows retrying initial alignment without implicitly approving words', async () => {
    current.timingStatus = 'MISSING'; current.alignmentRequired = true;
    current.jobs=[{jobId:'failed',operation:'ALIGN',status:'FAILED',errorCode:'TIMING_ZERO_DURATION',errorMessage:'sentence=1, word=0',usage:[]}];
    await show();
    expect(screen.getByRole('button',{name:'Retry alignment'})).toBeEnabled();
    expect(screen.getByRole('button',{name:'Review sentence 1'})).toBeEnabled();
    expect(screen.getByRole('button',{name:'Save and realign'})).toBeEnabled();
  });
  it('locates a timing failure and shows a possible repeat without prescribing a merge', async () => {
    current.approvedRevision=current.transcriptRevision;
    current.jobs=[{jobId:'failed',operation:'ALIGN',status:'FAILED',errorCode:'TIMING_ZERO_DURATION',errorMessage:'sentence=1, word=0, startMs=0, endMs=0',usage:[]}];
    current.sentences[0].issues=['SHORT_SENTENCE','POSSIBLE_REPEAT'];
    await show();
    fireEvent.click(screen.getByRole('button',{name:'Review sentence 1'}));
    expect(screen.getByLabelText('Text for sentence 1')).toBeVisible();
    expect(screen.getByText(/This repeats the end of the previous sentence/)).toBeVisible();
    expect(screen.queryByText(/This sentence is very short/)).not.toBeInTheDocument();
  });

  it('names the unfiltered next sentence before merging and saves both texts with one identity', async () => {
    current.sentences[0].issues=['SHORT_SENTENCE'];
    current.sentences.push({...structuredClone(current.sentences[0]),id:'sentence-b',text:'Next sentence.',issues:[]});
    vi.mocked(api.saveTranscript).mockImplementation(async (_id,_revision,rows)=>{
      current={...current,revision:2,transcriptRevision:2,sentences:rows.map(row=>({...current.sentences[0],...row,id:row.id!}))};
      return structuredClone(current);
    });
    await show();
    fireEvent.click(screen.getByRole('button',{name:'Word suggestions (1)'}));
    expect(screen.queryByLabelText('Text for sentence 2')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'Merge with sentence 2 in the original order'}));
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Hello world. Next sentence.');
    await saveDraft();
    expect(api.saveTranscript).toHaveBeenCalledWith('lesson-a',1,[{id:original.sentences[0].id,text:'Hello world. Next sentence.'}]);
    expect(current.sentences).toHaveLength(1);
  });

  it("uses one alignment action and preserves approved words on retry", async () => {
    current.alignmentRequired = true;
    current.approvedRevision = current.transcriptRevision;
    current.jobs = [
      { jobId: "prepare", operation: "PREPARE", status: "SUCCEEDED", usage: [{ provider: "GROQ", model: "whisper-large-v3-turbo", latencyMs: 1000 }] },
      { jobId: "align", operation: "ALIGN", status: "FAILED", errorCode: "TIMING_ZERO_DURATION", usage: [{ provider: "QWEN", model: "Qwen3-ForcedAligner-0.6B", latencyMs: 1000 }] },
    ];
    vi.mocked(api.alignTranscript).mockImplementation(async () => {
      current = { ...current, jobs: [...current.jobs, { jobId: "retry", operation: "ALIGN", status: "QUEUED", usage: [] }] };
      return { lessonId: current.id, jobId: "retry" };
    });
    await show();
    expect(screen.getByRole("alert")).toHaveTextContent("Some words have no duration yet. Run alignment again.");
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save and realign" })); });
    expect(api.alignTranscript).toHaveBeenCalledWith("lesson-a", 1);
    expect(api.saveTranscript).not.toHaveBeenCalled();
    expect(api.prepareTranscript).not.toHaveBeenCalled();
    expect(current.sentences[0].text).toBe("Hello world.");
    expect(screen.getByRole("button", { name: "Save and realign" })).toBeDisabled();
  });

  it("saves then aligns using the returned revision without approving", async () => {
    current.alignmentRequired=true;
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'),{target:{value:'Hello everybody.'}});
    const saved={...current,revision:2,transcriptRevision:2,sentences:[{...current.sentences[0],text:'Hello everybody.'}]};
    vi.mocked(api.saveTranscript).mockResolvedValue(saved);
    vi.mocked(api.alignTranscript).mockImplementation(async () => {current={...saved,jobs:[{jobId:'align-new',operation:'ALIGN',status:'RUNNING',usage:[]}]};return {lessonId:current.id,jobId:'align-new'};});
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Save and realign'}));});
    expect(api.saveTranscript).toHaveBeenCalledWith('lesson-a',1,expect.any(Array));
    expect(api.approveTranscript).not.toHaveBeenCalled();
    expect(api.alignTranscript).toHaveBeenCalledWith('lesson-a',2);
    expect(screen.getByRole('button',{name:'Save and realign'})).toBeDisabled();
  });
  it("keeps saved words when alignment submission fails", async () => {
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'),{target:{value:'Saved correction.'}});
    vi.mocked(api.saveTranscript).mockResolvedValue({...current,revision:2,transcriptRevision:2,sentences:[{...current.sentences[0],text:'Saved correction.'}]});
    vi.mocked(api.alignTranscript).mockRejectedValue(new ApiError(409,'Changed'));
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Save and realign'}));});
    expect(api.alignTranscript).toHaveBeenCalledWith('lesson-a',2);
    expect(api.approveTranscript).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Saved correction.');
    expect(screen.getByRole('alert')).toHaveTextContent('changed');
  });
  it("records approval only after explicit listening confirmation and save", async () => {
    await show();
    expect(api.approveTranscript).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button',{name:'Ready player'}));
    expect(screen.getByRole('button',{name:'Save reviewed draft'})).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    vi.mocked(api.approveTranscript).mockResolvedValue({...current,revision:2,approvedRevision:1});
    vi.mocked(api.reviewKaraoke).mockResolvedValue({...current,revision:3,approvedRevision:1,karaokeReviewedRevision:1});
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Save reviewed draft'}));});
    expect(api.approveTranscript).toHaveBeenCalledWith('lesson-a',1);
    expect(api.reviewKaraoke).toHaveBeenCalledWith('lesson-a',2);
    expect(api.alignTranscript).not.toHaveBeenCalled();
  });
  it("observes preparation followed by alignment without starting client inference or approval", async () => {
    current.jobs=[{jobId:'prepare',operation:'PREPARE',status:'RUNNING',usage:[]}];
    await show();
    expect(screen.getByLabelText('Text for sentence 1')).toBeDisabled();
    current={...current,revision:2,jobs:[{...current.jobs[0],status:'SUCCEEDED'},{jobId:'align',operation:'ALIGN',status:'RUNNING',usage:[]}]};
    await act(async () => {await vi.advanceTimersByTimeAsync(2600);});
    expect(screen.getByLabelText('Text for sentence 1')).toBeDisabled();
    current={...current,revision:3,jobs:current.jobs.map(job=>({...job,status:'SUCCEEDED'}))};
    await act(async () => {await vi.advanceTimersByTimeAsync(2600);});
    expect(screen.getByLabelText('Text for sentence 1')).toBeEnabled();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'),{target:{value:'Hello English.'}});
    fireEvent.blur(screen.getByLabelText('Text for sentence 1'));
    expect(api.prepareTranscript).not.toHaveBeenCalled();expect(api.alignTranscript).not.toHaveBeenCalled();
    expect(api.synthesizeText).not.toHaveBeenCalled();expect(api.approveTranscript).not.toHaveBeenCalled();
  });
  it("polls active jobs and stops after a terminal checkpoint", async () => {
    current.jobs = [
      {
        jobId: "job-active",
        operation: "PREPARE",
        status: "RUNNING",
        usage: [],
      },
    ];
    await show();
    expect(api.fetchDraft).toHaveBeenCalledTimes(1);
    current.jobs[0].status = "SUCCEEDED";
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    expect(api.fetchDraft).toHaveBeenCalledTimes(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(api.fetchDraft).toHaveBeenCalledTimes(2);
  });
  it("does not poll an idle draft", async () => {
    await show();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(api.fetchDraft).toHaveBeenCalledTimes(1);
  });

  it("preserves unsaved transcript text across refreshes and saves against the original editor revision", async () => {
    await show();
    fireEvent.change(screen.getByLabelText("Text for sentence 1"), {
      target: { value: "My corrected transcript." },
    });
    current = { ...current, revision: 2 };
    await act(async () => {
      void client.refetchQueries({ queryKey: ["authoring"] });
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue(
      "My corrected transcript.",
    );
    expect(
      screen.getByRole("button", { name: "Save and realign" }),
    ).toBeEnabled();
    vi.mocked(api.saveTranscript).mockRejectedValue(
      new ApiError(409, "Changed"),
    );
    await saveDraft();
    expect(api.saveTranscript).toHaveBeenCalledWith("lesson-a", 1, [
      { id: "s1", text: "My corrected transcript." },
    ]);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The lesson changed or is locked",
    );
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue(
      "My corrected transcript.",
    );
  });

  it("requires a fresh karaoke confirmation after a refresh receives a changed draft", async () => {
    current.approvedRevision = 1;
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Ready player" }));
    const confirmation = screen.getByRole("checkbox", {
      name: /I played and checked/,
    });
    fireEvent.click(confirmation);
    expect(confirmation).toBeChecked();
    current = { ...current, revision: 2 };
    await act(async () => {
      void client.refetchQueries({ queryKey: ["authoring"] });
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(confirmation).not.toBeChecked();
    expect(
      screen.getByRole("button", { name: "Save reviewed draft" }),
    ).toBeDisabled();
  });

  it("ignores an old poll response delivered after a newer successful save", async () => {
    await show();
    let resolvePoll!: (draft: LessonDraft) => void;
    vi.mocked(api.fetchDraft).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePoll = resolve;
        }),
    );
    await act(async () => {
      void client.refetchQueries({ queryKey: ["authoring"] });
      await vi.advanceTimersByTimeAsync(1);
    });
    fireEvent.change(screen.getByLabelText("Text for sentence 1"), {
      target: { value: "New saved text." },
    });
    vi.mocked(api.saveTranscript).mockResolvedValue({
      ...current,
      revision: 2,
      sentences: [{ ...current.sentences[0], text: "New saved text." }],
    });
    await saveDraft();
    await act(async () => {
      resolvePoll(structuredClone(original));
    });
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue(
      "New saved text.",
    );
  });

  it("hides metadata until a source is ready and renders both loading panes together", async () => {
    vi.mocked(api.inspectSource).mockImplementation(() => new Promise(() => {}));
    await show("/lessons/new");
    expect(screen.getByText("Start with a source video")).toBeInTheDocument();
    expect(screen.queryByLabelText("Title")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getByRole("region", {name: "Processing status"})).toHaveTextContent("Checking source information");
    expect(screen.queryByLabelText("Title")).not.toBeInTheDocument();
  });
  it("edits exact range endpoints and rejects invalid timecodes before preparing audio", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({videoId:"abcdefghijk", title:"Video", durationMs:223000, embedAllowed:true, usedRanges:[]});
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    fireEvent.change(screen.getByLabelText("End"), {target:{value:"00:25.000"}});
    fireEvent.blur(screen.getByLabelText("End"));
    fireEvent.change(screen.getByLabelText("Start"), {target:{value:"00:05.000"}});
    fireEvent.blur(screen.getByLabelText("Start"));
    expect(screen.getByRole("textbox", {name:"Start"})).toHaveValue("00:05.000");
    expect(screen.getByRole("textbox", {name:"End"})).toHaveValue("00:25.000");
    fireEvent.change(screen.getByLabelText("Start"), {target:{value:"00:30.000"}});
    fireEvent.blur(screen.getByLabelText("Start"));
    expect(screen.getByRole("button", {name:"Prepare transcript"})).toBeDisabled();
    expect(api.createDraft).not.toHaveBeenCalled();
  });
  it("opens source editing after URL input without a rights checkbox", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({
      videoId: "abcdefghijk",
      title: "Video",
      durationMs: 300000,
      embedAllowed: true,
      usedRanges: [
        {
          lessonId: "existing",
          title: "Existing",
          startMs: 0,
          endMs: 200000,
          status: "PUBLISHED",
        },
      ],
    });
    await show("/lessons/new");
    expect(screen.queryByText("Text source")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("YouTube URL"), {
      target: { value: "https://youtu.be/abcdefghijk" },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Search" }));
    });
    expect(api.inspectSource).toHaveBeenCalledWith(
      "https://youtu.be/abcdefghijk",
      expect.anything(),
    );
    fireEvent.click(screen.getByRole('button', { name: /Existing lessons from this video/ }));
    fireEvent.click(screen.getByRole('button', { name: '00:00.000–03:20.000' }));
    expect(
      screen.getByText(
        "This segment overlaps an existing lesson. You may continue.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("checkbox", { name: /responsibility/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load waveform" })).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Prepare transcript" }),
    ).toBeEnabled();
  });
  it("requires a chosen range for videos above ten minutes before preparation", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({videoId:"abcdefghijk", title:"Long video", durationMs:600001, embedAllowed:true, usedRanges:[]});
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), {target:{value:"https://youtu.be/abcdefghijk"}});
    await act(async () => { fireEvent.click(screen.getByRole("button", {name:"Search"})); });
    expect(screen.getByLabelText("Start")).toHaveValue("");
    expect(screen.getByLabelText("End")).toHaveValue("");
    expect(screen.queryByRole("button", {name:"Select full video"})).not.toBeInTheDocument();
    expect(screen.getByRole("button", {name:"Prepare transcript"})).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Start"), {target:{value:"00:05.000"}});
    fireEvent.change(screen.getByLabelText("End"), {target:{value:"00:30.000"}});
    fireEvent.blur(screen.getByLabelText("End"));
    expect(screen.getByRole("button", {name:"Prepare transcript"})).toBeEnabled();
    expect(api.createDraft).not.toHaveBeenCalled();
  });
  it("only inspects a pasted URL after Search, without starting acquisition or AI", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({
      videoId: "abcdefghijk",
      title: "Auto source",
      durationMs: 120000,
      embedAllowed: true,
      usedRanges: [],
    });
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), {
      target: { value: "https://youtu.be/abcdefghijk" },
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(650);
    });
    fireEvent.blur(screen.getByLabelText("YouTube URL"));
    expect(api.inspectSource).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getByLabelText("Title")).toHaveAttribute("placeholder", "Auto source");
    await act(async () => { await vi.dynamicImportSettled(); });
    expect(screen.getByLabelText("Start")).toHaveValue("00:00.000");
    expect(screen.getByLabelText("End")).toHaveValue("02:00.000");
    expect(screen.getByRole("button", {name:"Select full video"})).toHaveAttribute("aria-pressed", "true");
    expect(api.createDraft).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Load waveform" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Start")).toHaveValue("00:00.000");
    expect(screen.getByLabelText("End")).toHaveValue("02:00.000");
    expect(screen.getByRole("button", { name: "Prepare transcript" })).toBeEnabled();
  });
  it("refreshes the same URL while keeping the edited title and valid range", async () => {
    const source = { videoId: "abcdefghijk", title: "Original title", durationMs: 120000, embedAllowed: true, usedRanges: [] };
    vi.mocked(api.inspectSource).mockResolvedValueOnce(source).mockResolvedValueOnce({ ...source, title: "Refreshed title" });
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    fireEvent.change(screen.getByLabelText("Title"), { target: { value: "My edited title" } });
    fireEvent.change(screen.getByLabelText("End"), { target: { value: "00:25.000" } });
    fireEvent.blur(screen.getByLabelText("End"));
    fireEvent.change(screen.getByLabelText("Start"), { target: { value: "00:05.000" } });
    fireEvent.blur(screen.getByLabelText("Start"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(api.inspectSource).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText("Title")).toHaveValue("My edited title");
    expect(screen.getByLabelText("Start")).toHaveValue("00:05.000");
    expect(screen.getByLabelText("End")).toHaveValue("00:25.000");
  });
  it("preserves a pending start on refresh and only prepares explicitly completed timecodes", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({ videoId: "abcdefghijk", title: "Video", durationMs: 120000, embedAllowed: true, usedRanges: [] });
    vi.mocked(api.createDraft).mockResolvedValue({ lessonId: "lesson-a" } as Awaited<ReturnType<typeof api.createDraft>>);
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), {target:{value:"https://youtu.be/abcdefghijk?t=5s"}});
    await act(async () => { fireEvent.click(screen.getByRole("button", {name:"Search"})); });
    expect(screen.getByRole("button", {name:"Prepare transcript"})).toBeEnabled();
    fireEvent.change(screen.getByLabelText("End"), {target:{value:""}});
    fireEvent.blur(screen.getByLabelText("End"));
    fireEvent.change(screen.getByLabelText("Start"), {target:{value:"00:05.000"}});
    fireEvent.blur(screen.getByLabelText("Start"));
    await act(async () => { fireEvent.click(screen.getByRole("button", {name:"Search"})); });
    expect(screen.getByLabelText("Start")).toHaveValue("00:05.000");
    expect(screen.getByLabelText("End")).toHaveValue("");
    expect(screen.getByRole("button", {name:"Prepare transcript"})).toBeDisabled();
    fireEvent.change(screen.getByLabelText("End"), {target:{value:"00:25.000"}});
    fireEvent.blur(screen.getByLabelText("End"));
    await act(async () => { fireEvent.click(screen.getByRole("button", {name:"Prepare transcript"})); });
    expect(api.createDraft).toHaveBeenCalledWith(expect.objectContaining({startMs:5000,endMs:25000,sourceReference:"https://youtu.be/abcdefghijk?t=5s"}), expect.anything());
  });
  it("keeps the loaded source and shows a translated error when refreshing fails", async () => {
    vi.mocked(api.inspectSource).mockResolvedValueOnce({ videoId: "abcdefghijk", title: "Video", durationMs: 120000, embedAllowed: true, usedRanges: [] })
      .mockRejectedValueOnce(new ApiError(503, "private stderr", "YOUTUBE_MEDIA_FAILED"));
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getByLabelText("Title")).toHaveAttribute("placeholder", "Video");
    expect(screen.getByRole("alert")).not.toHaveTextContent("private stderr");
    expect(screen.getByRole("button", { name: "Search" })).toBeEnabled();
  });
  it("resets an obsolete range after a duration refresh but keeps the edited title", async () => {
    const source = { videoId: "abcdefghijk", title: "Original title", durationMs: 120000, embedAllowed: true, usedRanges: [] };
    vi.mocked(api.inspectSource).mockResolvedValueOnce(source).mockResolvedValueOnce({ ...source, durationMs: 30000 });
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    fireEvent.change(screen.getByLabelText("Start"), { target: { value: "00:05.000" } });
    fireEvent.change(screen.getByLabelText("End"), { target: { value: "01:00.000" } });
    fireEvent.blur(screen.getByLabelText("End"));
    fireEvent.change(screen.getByLabelText("Title"), { target: { value: "My edited title" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getByLabelText("Title")).toHaveValue("My edited title");
    expect(screen.getByLabelText("End")).toHaveValue("00:30.000");
  });
  it("ignores a stale A response after editing A to B and back and blocks duplicate submits", async () => {
    const source = { videoId: "abcdefghijk", title: "Video", durationMs: 120000, embedAllowed: true, usedRanges: [] };
    let resolve!: (value: typeof source) => void;
    vi.mocked(api.inspectSource).mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(source);
    await show("/lessons/new");
    const input = screen.getByLabelText("YouTube URL");
    fireEvent.change(input, { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.submit(input.closest("form")!); });
    await act(async () => { fireEvent.submit(input.closest("form")!); });
    expect(api.inspectSource).toHaveBeenCalledTimes(1);
    fireEvent.change(input, { target: { value: "https://youtu.be/lmnopqrstuv" } });
    fireEvent.change(input, { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { resolve(source); });
    expect(screen.queryByLabelText("Title")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Prepare transcript" })).toBeDisabled();
    await act(async () => { fireEvent.submit(input.closest("form")!); });
    expect(screen.getByLabelText("Title")).toHaveAttribute("placeholder", "Video");
    fireEvent.change(input, { target: { value: "https://youtu.be/lmnopqrstuv" } });
    expect(screen.getByRole("button", { name: "Prepare transcript" })).toBeDisabled();
    expect(api.createDraft).not.toHaveBeenCalled();
  });
  it("shows a translated source error beside the URL and retries without leaking tool details", async () => {
    vi.mocked(api.inspectSource)
      .mockRejectedValueOnce(
        new ApiError(
          503,
          "private downloader stderr",
          "YOUTUBE_MEDIA_TOOL_UNAVAILABLE",
          undefined,
          "request-123",
        ),
      )
      .mockResolvedValueOnce({
        videoId: "abcdefghijk",
        title: "Recovered video",
        durationMs: 120000,
        embedAllowed: true,
        usedRanges: [],
      });
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), {
      target: { value: "https://youtu.be/abcdefghijk" },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Search" }));
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The Core media tools are unavailable",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Request request-123");
    expect(
      screen.queryByText("private downloader stderr"),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("YouTube URL")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Search" }));
    });
    expect(api.inspectSource).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Title")).toHaveAttribute(
      "placeholder", "Recovered video",
    );
  });
  it("clears an earlier preparation error when inspecting another source", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({
      videoId: "abcdefghijk",
      title: "Video",
      durationMs: 120000,
      embedAllowed: true,
      usedRanges: [],
    });
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), {
      target: { value: "https://youtu.be/abcdefghijk" },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Search" }));
    });
    fireEvent.change(screen.getByLabelText("End"), {
      target: { value: "00:00.000" },
    });
    fireEvent.blur(screen.getByLabelText("End"));
    expect(screen.getByRole("alert")).toHaveTextContent("Use valid mm:ss.mmm");
    expect(
      screen.getByRole("button", { name: "Prepare transcript" }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText("YouTube URL"), {
      target: { value: "https://youtu.be/lmnopqrstuv" },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Search" }));
    });
    expect(api.inspectSource).toHaveBeenLastCalledWith(
      "https://youtu.be/lmnopqrstuv",
      expect.anything(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });


  it('saves the reviewed revision and stays an unpublished draft without later steps', async () => {
    current.approvedRevision = 1;
    vi.mocked(api.reviewKaraoke).mockImplementation(async () => ({ ...current, revision: 2, karaokeReviewedRevision: 1 }));
    await show();
    fireEvent.click(screen.getByRole('button', {name:'Ready player'}));
    fireEvent.click(screen.getByRole('checkbox', {name:/I played and checked/}));
    await act(async () => { fireEvent.click(screen.getByRole('button', {name:'Save reviewed draft'})); });
    expect(api.reviewKaraoke).toHaveBeenCalledWith('lesson-a', 1);
    expect(screen.getByText('Transcript and timing reviewed')).toBeVisible();
    expect(screen.getByRole('link', {name:'Back to lessons'})).toHaveAttribute('href', '/lessons');
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', {name:'Publish lesson'})).not.toBeInTheDocument();
  });
  it('does not offer retry for removed historical operations', async () => {
    current.jobs=[{jobId:'old-semantic',operation:'SEMANTIC',status:'FAILED',errorCode:'AUTHORING_OPERATION_REMOVED',usage:[]}];
    await show();
    expect(screen.getByRole('region', {name:'Processing status'})).toHaveTextContent('This operation has been removed');
    expect(screen.queryByRole('button', {name:'Retry step'})).not.toBeInTheDocument();
  });

  it('resumes Text in the same workspace with the saved voice and hides preliminary sentences until audio is ready', async () => {
    current.sourceType='TEXT';current.audioReady=false;current.ttsVoice='af_sarah';current.endMs=0;current.timingStatus='MISSING';
    await show('/lessons/new?draft=lesson-a');
    expect(screen.getByLabelText('English text')).toHaveValue('Hello world.');
    expect(screen.getByRole('button',{name:'Selected voice'})).toHaveTextContent('af_sarah');
    expect(screen.queryByLabelText('Text for sentence 1')).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'Edit words'})).not.toBeInTheDocument();
    vi.mocked(api.synthesizeText).mockImplementation(async () => {current={...current,revision:2,jobs:[{jobId:'speech',operation:'TTS',status:'RUNNING',usage:[]}]};return {lessonId:current.id,jobId:'speech'};});
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Generate speech'}));});
    expect(api.synthesizeText).toHaveBeenCalledWith('lesson-a',1);
    current={...current,revision:3,audioReady:true,endMs:1000,jobs:[{jobId:'speech',operation:'TTS',status:'SUCCEEDED',usage:[]}]};
    await act(async () => {await vi.advanceTimersByTimeAsync(2600);});
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Hello world.');
    expect(screen.getAllByRole('button',{name:'Selected voice'})).toHaveLength(1);
    expect(api.saveVoice).not.toHaveBeenCalled();
  });

  it('keeps source metadata edits from being discarded by a speech action', async () => {
    current.sourceType='TEXT';current.audioReady=false;current.ttsVoice='af_sarah';current.endMs=0;
    client.setQueryData(['catalog','metadata','lesson-a'],{id:'lesson-a',title:'A lesson',categories:[],description:'',imageUrl:''});
    await show('/lessons/new?draft=lesson-a');
    await act(async () => {await vi.advanceTimersByTimeAsync(20);});
    fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Unsaved title'}});
    expect(screen.getByRole('button',{name:'Generate speech'})).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Title'),{target:{value:'A lesson'}});
    expect(screen.getByRole('button',{name:'Generate speech'})).toBeEnabled();
    expect(api.synthesizeText).not.toHaveBeenCalled();
  });

  it('keeps the caret position after batched selection events and splits without crashing', async () => {
    await show();
    const input=screen.getByLabelText('Text for sentence 1') as HTMLTextAreaElement;
    act(() => {
      input.focus();
      input.setSelectionRange(3,3);fireEvent.select(input);
      input.setSelectionRange(6,6);fireEvent.select(input);
    });
    fireEvent.click(screen.getByRole('button',{name:'Split at cursor'}));
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Hello');
    expect(screen.getByLabelText('Text for sentence 2')).toHaveValue('world.');
  });

});
