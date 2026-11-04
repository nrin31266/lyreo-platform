import { TooltipProvider } from '@/components/ui/tooltip';
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
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
  approveTranscript: vi.fn(),
  alignTranscript: vi.fn(),
  reviewKaraoke: vi.fn(),
  generateSemantic: vi.fn(),
  saveCandidates: vi.fn(),
  publishLesson: vi.fn(),
  prepareTranscript: vi.fn(),
  saveOptions: vi.fn(),
  saveContent: vi.fn(),
  saveTiming: vi.fn(),
  generateIpa: vi.fn(),
  replaceGenerated: vi.fn(),
}));
vi.mock("@/pages/lessons/catalog.api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/pages/lessons/catalog.api")>()),
  fetchCategories: vi.fn().mockResolvedValue([]),
}));
vi.mock("@/pages/jobs/jobs.api", () => ({ cancelJob: vi.fn() }));
vi.mock("./YouTubePreview", () => ({
  YouTubePreview: ({
    onAvailabilityChange, seek,
  }: {
    onAvailabilityChange?: (ready: boolean) => void;
    seek?: { atMs: number } | null;
  }) => (
    <><button onClick={() => onAvailabilityChange?.(true)}>Ready player</button><output aria-label="Player seek">{seek?.atMs}</output></>
  ),
}));

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

function selectReviewMode(name: string) {
  if (!screen.queryByRole('button',{name})) fireEvent.mouseDown(screen.getByRole('tab',{name:'2. Words & timing'}),{button:0,ctrlKey:false});
  fireEvent.click(screen.getByRole('button',{name}));
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

  it("keeps timing warnings out of transcript review and retains them in Timing", async () => {
    current.timingStatus = "MISSING";
    current.alignmentRequired = true;
    current.approvedRevision = current.transcriptRevision;
    current.sentences[0].issues = ["TIMING_COVERAGE_MISMATCH", "LONG_SENTENCE"];
    await show();
    expect(screen.getByRole("button", { name: "Edit words" })).toHaveAttribute("aria-pressed", "true");
    selectReviewMode('Listen & check');
    expect(screen.getByText("Timing does not cover the transcript")).toBeVisible();
    selectReviewMode('Edit words');
    expect(screen.queryByText("Timing does not cover the transcript")).not.toBeInTheDocument();
    expect(screen.getByText("Long sentence: consider splitting it")).toBeVisible();
    expect(screen.queryByRole("slider", { name: "Playback position" })).not.toBeInTheDocument();
    expect(screen.queryByText("Build options")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset layout" })).not.toBeInTheDocument();
  });

  it("reopens missing timing in word review and offers one alignment action", async () => {
    current.approvedRevision = current.transcriptRevision;
    current.alignmentRequired = true;
    current.timingStatus = "MISSING";
    current.sentences[0].words = [];
    current.sentences[0].issues = ["TIMING_COVERAGE_MISMATCH"];
    await show();
    expect(screen.getByLabelText("Text for sentence 1")).toBeVisible();
    expect(screen.getAllByRole("button", { name: "Align approved transcript" })).toHaveLength(1);
    selectReviewMode('Listen & check');
    expect(screen.getByText("Words are saved. Timing is not ready.")).toBeVisible();
    expect(screen.queryByText("Timing does not cover the transcript")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirm karaoke review" })).not.toBeInTheDocument();
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

  it("uses one numbered tab row with source context and a gated publication step", async () => {
    await show();
    expect(screen.getAllByRole("tab").map(tab => tab.textContent)).toEqual([
      "1. Source", "2. Words & timing", "3. Content", "4. Publish",
    ]);
    expect(screen.getByRole("tab", { name: "4. Publish" })).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "1. Source" }), { button: 0, ctrlKey: false });
    expect(screen.getByRole("link", { name: original.sourceReference })).toHaveAttribute("href", original.sourceReference);
    expect(screen.queryByRole("button", { name: "Publish lesson" })).not.toBeInTheDocument();
  });

  it("filters problem sentences and bulk-selects only visible rows", async () => {
    current.sentences[0].issues = ["SHORT_SENTENCE", "TIMING_MISSING"];
    current.sentences.push({ ...structuredClone(current.sentences[0]), id: "s2", text: "Another full sentence.", issues: ["TIMING_MISSING"] });
    await show();
    expect(screen.queryByRole("button", { name: "Transcript review help" })).not.toBeInTheDocument();
    const filter = screen.getByRole("button", { name: "Word suggestions (1)" });
    fireEvent.click(filter);
    expect(filter).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("textbox", { name: "Text for sentence 1" })).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "Text for sentence 2" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all sentences" }));
    fireEvent.click(screen.getByRole("button", { name: "Watch only" }));
    fireEvent.click(filter);
    expect(screen.getByRole("combobox", { name: "Usage for sentence 1" })).toHaveTextContent("Watch only");
    expect(screen.getByRole("combobox", { name: "Usage for sentence 2" })).toHaveTextContent("Learn");
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
    expect(trigger).toHaveTextContent("Failed");
    fireEvent.click(trigger);
    expect(screen.getByRole("dialog", { name: "Processing and history" })).toHaveAttribute("data-side", "bottom");
    expect(screen.getAllByText("Alignment output failed validation. Open processing details or retry alignment.").some(element => element.closest("[role=dialog]"))).toBe(true);
    fireEvent.click(screen.getByText("Processing details"));
    expect(screen.getByText("local / whisper")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Details and usage" })).not.toBeInTheDocument();
    expect(screen.queryByText("The AI content did not match the transcript. Try generating the content again.")).not.toBeInTheDocument();
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
    expect(screen.getByRole("alert")).toHaveTextContent("An observed word has no positive duration.");
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Align approved transcript" })); });
    expect(api.alignTranscript).toHaveBeenCalledWith("lesson-a", 1);
    expect(api.saveTranscript).not.toHaveBeenCalled();
    expect(api.prepareTranscript).not.toHaveBeenCalled();
    expect(current.sentences[0].text).toBe("Hello world.");
    expect(screen.getByRole("button", { name: "Align approved transcript" })).toBeDisabled();
  });

  it("saves, approves and aligns serially with each returned revision", async () => {
    current.alignmentRequired=true;
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'),{target:{value:'Hello everybody.'}});
    const saved={...current,revision:2,transcriptRevision:2,sentences:[{...current.sentences[0],text:'Hello everybody.'}]};
    const approved={...saved,revision:3,approvedRevision:2};
    vi.mocked(api.saveTranscript).mockResolvedValue(saved);
    vi.mocked(api.approveTranscript).mockResolvedValue(approved);
    vi.mocked(api.alignTranscript).mockImplementation(async () => {current={...approved,jobs:[{jobId:'align-new',operation:'ALIGN',status:'RUNNING',usage:[]}]};return {lessonId:current.id,jobId:'align-new'};});
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Save, approve & align'}));});
    expect(api.saveTranscript).toHaveBeenCalledWith('lesson-a',1,expect.any(Array));
    expect(api.approveTranscript).toHaveBeenCalledWith('lesson-a',2);
    expect(api.alignTranscript).toHaveBeenCalledWith('lesson-a',3);
    expect(screen.getByRole('button',{name:'Listen & check'})).toHaveAttribute('aria-pressed','true');
    expect(screen.getByRole('button',{name:'Align approved transcript'})).toBeDisabled();
  });
  it("keeps the saved checkpoint when approval fails and does not enqueue alignment", async () => {
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'),{target:{value:'Saved correction.'}});
    vi.mocked(api.saveTranscript).mockResolvedValue({...current,revision:2,transcriptRevision:2,sentences:[{...current.sentences[0],text:'Saved correction.'}]});
    vi.mocked(api.approveTranscript).mockRejectedValue(new ApiError(409,'Changed'));
    await act(async () => {fireEvent.click(screen.getByRole('button',{name:'Save, approve & align'}));});
    expect(api.alignTranscript).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue('Saved correction.');
    expect(screen.getByRole('alert')).toHaveTextContent('changed');
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
      screen.getByRole("button", { name: "Save, approve & align" }),
    ).toBeEnabled();
    vi.mocked(api.saveTranscript).mockRejectedValue(
      new ApiError(409, "Changed"),
    );
    await saveDraft();
    expect(api.saveTranscript).toHaveBeenCalledWith("lesson-a", 1, [
      { id: "s1", text: "My corrected transcript.", usage: "LEARN" },
    ]);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The lesson changed or is locked",
    );
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue(
      "My corrected transcript.",
    );
  });

  it("shows thought groups from transcript tokens when observed timing crosses their boundary", async () => {
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        current.transcriptRevision;
    current.sentences[0] = {
      ...current.sentences[0],
      text: "“New York” rocks.",
      words: [
        {
          text: "New York",
          startMs: 0,
          endMs: 500,
          charStart: 1,
          charEnd: 9,
          tokenStart: 0,
          tokenEnd: 2,
        },
        {
          text: "rocks",
          startMs: 500,
          endMs: 1000,
          charStart: 11,
          charEnd: 16,
          tokenStart: 2,
          tokenEnd: 3,
        },
      ],
      thoughtGroups: [
        { tokenStart: 0, tokenEnd: 1 },
        { tokenStart: 1, tokenEnd: 3 },
      ],
    };
    await show();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(screen.getByText(/“New \/ York” rocks\./)).toBeVisible();
  });

  it("locks downstream tabs while transcript edits are unsaved", async () => {
    await show();
    fireEvent.change(screen.getByLabelText("Text for sentence 1"), { target: { value: "Unsaved correction." } });
    expect(screen.getByRole("tab", { name: "3. Content" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Listen & check" })).toBeDisabled();
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue("Unsaved correction.");
    expect(screen.getByRole("button", { name: "Save, approve & align" })).toBeEnabled();
  });

  it("does not unlock generation from ASR timing or transcript approval alone", async () => {
    current.approvedRevision = 1;
    await show();
    expect(screen.getByRole("button", { name: "Listen & check" })).toHaveAttribute("aria-pressed", "true");
    expect(
      screen.queryByRole("button", { name: "Generate learning content" }),
    ).not.toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.getByRole("tab", { name: "3. Content" }),
    ).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Generate learning content" })).not.toBeInTheDocument();
    selectReviewMode('Listen & check');
    expect(
      screen.getByRole("button", { name: "Confirm karaoke review" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", {name: /Grouped ASR timing needs manual review/}),
    ).toBeInTheDocument();
    expect(api.generateSemantic).not.toHaveBeenCalled();
  });

  it("preserves custom candidate selection across refreshes and requires saving before publication", async () => {
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    current.status = "CONTENT_REVIEW";
    await show();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), {
      button: 0,
      ctrlKey: false,
    });
    const shadowing = screen.getByRole("checkbox", {
      name: /Shadowing · MEDIUM/,
    });
    fireEvent.click(shadowing);
    await act(async () => {
      void client.refetchQueries({ queryKey: ["authoring"] });
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(shadowing).toBeChecked();
    expect(
      screen.getByRole("button", { name: "Save activity selection" }),
    ).toBeEnabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "4. Publish" }), { button: 0, ctrlKey: false });
    expect(
      screen.getByRole("button", { name: "Publish lesson" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("checkbox", { name: /I reviewed the translations/ }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), { button: 0, ctrlKey: false });
    vi.mocked(api.saveCandidates).mockResolvedValue({
      ...current,
      revision: 2,
      sentences: [{ ...current.sentences[0], shadowingSelected: true }],
    });
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", { name: "Save activity selection" }),
      );
    });
    expect(api.saveCandidates).toHaveBeenCalledWith(
      "lesson-a",
      1,
      ["s1"],
      ["s1"],
    );
  });

  it("saves custom candidates against the revision where editing began despite refreshes", async () => {
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    await show();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), {
      button: 0,
      ctrlKey: false,
    });
    fireEvent.click(
      screen.getByRole("checkbox", { name: /Shadowing · MEDIUM/ }),
    );
    current = { ...current, revision: 2 };
    await act(async () => {
      void client.refetchQueries({ queryKey: ["authoring"] });
      await vi.advanceTimersByTimeAsync(1);
    });
    vi.mocked(api.saveCandidates).mockRejectedValue(
      new ApiError(409, "Changed"),
    );
    await act(async () => {
      fireEvent.click(
        screen.getByRole("button", { name: "Save activity selection" }),
      );
    });
    expect(api.saveCandidates).toHaveBeenCalledWith(
      "lesson-a",
      1,
      ["s1"],
      ["s1"],
    );
    expect(
      screen.getByRole("checkbox", { name: /Shadowing · MEDIUM/ }),
    ).toBeChecked();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The lesson changed or is locked",
    );
  });

  it("requires a fresh karaoke confirmation after a refresh receives a changed draft", async () => {
    current.approvedRevision = 1;
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Ready player" }));
    selectReviewMode('Listen & check');
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
    selectReviewMode('Listen & check');
    expect(
      screen.getByRole("button", { name: "Confirm karaoke review" }),
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

  it("locks every content mutation after publication", async () => {
    current.status = "PUBLISHED";
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    await show();
    selectReviewMode('Edit words');
    expect(screen.getByLabelText("Text for sentence 1")).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Save, approve & align" }),
    ).toBeDisabled();
    selectReviewMode('Listen & check');
    expect(screen.getByRole("button", { name: "Run dedicated alignment" })).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "3. Content" }), {
      button: 0,
      ctrlKey: false,
    });
    for (const name of [
      "Regenerate learning content",
      "Save activity selection",
    ]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
    fireEvent.mouseDown(screen.getByRole("tab", { name: "4. Publish" }), { button: 0, ctrlKey: false });
    expect(screen.getByRole("button", { name: "Publish lesson" })).toBeDisabled();
    expect(
      screen.getByText(
        "Published. Content and selected activities are locked.",
      ),
    ).toBeInTheDocument();
  });

  it("hides metadata until a source is ready and renders both loading panes together", async () => {
    vi.mocked(api.inspectSource).mockImplementation(() => new Promise(() => {}));
    await show("/lessons/new");
    expect(screen.getByText("Start with a source video")).toBeInTheDocument();
    expect(screen.queryByLabelText("Lesson title")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getAllByRole("status")).toHaveLength(2);
    expect(screen.queryByLabelText("Lesson title")).not.toBeInTheDocument();
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
    expect(screen.getByLabelText("Lesson title")).toHaveValue("Auto source");
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
    fireEvent.change(screen.getByLabelText("Lesson title"), { target: { value: "My edited title" } });
    fireEvent.change(screen.getByLabelText("End"), { target: { value: "00:25.000" } });
    fireEvent.blur(screen.getByLabelText("End"));
    fireEvent.change(screen.getByLabelText("Start"), { target: { value: "00:05.000" } });
    fireEvent.blur(screen.getByLabelText("Start"));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(api.inspectSource).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText("Lesson title")).toHaveValue("My edited title");
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
    expect(screen.getByLabelText("Lesson title")).toHaveValue("Video");
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
    fireEvent.change(screen.getByLabelText("Lesson title"), { target: { value: "My edited title" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Search" })); });
    expect(screen.getByLabelText("Lesson title")).toHaveValue("My edited title");
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
    expect(screen.queryByLabelText("Lesson title")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Prepare transcript" })).toBeDisabled();
    await act(async () => { fireEvent.submit(input.closest("form")!); });
    expect(screen.getByLabelText("Lesson title")).toHaveValue("Video");
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
    expect(screen.getByLabelText("Lesson title")).toHaveValue(
      "Recovered video",
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
  it("explains validation failures without directing the user to provider settings", async () => {
    current.jobs = [{ jobId: "validation", operation: "SEMANTIC", status: "FAILED", errorCode: "AUTHORING_VALIDATION_FAILED", errorMessage: "Thought groups must sequentially partition source", usage: [] }];
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Processing and history" }));
    expect(screen.getByText("The AI content did not match the transcript. Try generating the content again.")).toBeVisible();
    expect(screen.queryByText("Thought groups must sequentially partition source")).not.toBeInTheDocument();
    expect(document.querySelector('a[href="/settings/ai#routes"]')).not.toBeInTheDocument();
  });
  it("shows current jobs and historical attempts with compact action menus", async () => {
    current.jobs = [
      {
        jobId: "completed-job",
        operation: "PREPARE",
        status: "SUCCEEDED",
        usage: [],
      },
      {
        jobId: "running-job",
        operation: "ALIGN",
        status: "RUNNING",
        currentStep: "Active alignment",
        usage: [],
      },
      {
        jobId: "failed-job",
        operation: "SEMANTIC",
        status: "FAILED",
        errorMessage: "Failed attempt stays visible",
        usage: [],
      },
      {
        jobId: "cancelled-job",
        operation: "ALIGN",
        status: "CANCELLED",
        errorMessage: "Cancelled attempt stays visible",
        usage: [],
      },
    ];
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Processing and history" }));
    expect(screen.getByText("Active alignment")).toBeVisible();
    expect(screen.getByText("Failed attempt stays visible")).toBeVisible();
    expect(screen.getByText("Cancelled attempt stays visible")).toBeVisible();
    expect(document.querySelector('a[href="/jobs?job=completed-job"]')).toBeVisible();
    expect(screen.queryByRole("button", { name: /Job history/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Details and usage" })).not.toBeInTheDocument();
  });
  it("shows mixed terminal job details immediately after opening history after publication", async () => {
    current.status = "PUBLISHED";
    current.jobs = [
      {
        jobId: "old-failure",
        operation: "SEMANTIC",
        status: "FAILED",
        errorMessage: "Earlier content attempt failed",
        usage: [],
      },
      {
        jobId: "completed-retry",
        operation: "SEMANTIC",
        status: "SUCCEEDED",
        usage: [],
      },
      {
        jobId: "old-cancellation",
        operation: "ALIGN",
        status: "CANCELLED",
        errorMessage: "Earlier alignment was cancelled",
        usage: [],
      },
    ];
    await show();
    fireEvent.click(screen.getByRole("button", { name: "Processing and history" }));
    expect(screen.getByText("Earlier content attempt failed")).toBeVisible();
    expect(screen.getByText("Earlier alignment was cancelled")).toBeVisible();
    expect(document.querySelector('a[href="/jobs?job=completed-retry"]')).toBeVisible();
  });
});
