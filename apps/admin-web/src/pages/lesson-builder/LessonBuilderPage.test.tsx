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
}));
vi.mock("@/pages/lessons/catalog.api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/pages/lessons/catalog.api")>()),
  fetchCategories: vi.fn().mockResolvedValue([]),
}));
vi.mock("@/pages/jobs/jobs.api", () => ({ cancelJob: vi.fn() }));
vi.mock("./YouTubePreview", () => ({
  YouTubePreview: ({
    onAvailabilityChange,
  }: {
    onAvailabilityChange?: (ready: boolean) => void;
  }) => (
    <button onClick={() => onAvailabilityChange?.(true)}>Ready player</button>
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
        <I18nextProvider i18n={i18n}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route
                path="/lessons/:lessonId"
                element={<LessonBuilderPage />}
              />
              <Route path="/lessons/new" element={<LessonBuilderPage />} />
            </Routes>
          </MemoryRouter>
        </I18nextProvider>
      </QueryClientProvider>,
    );
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
}

describe("lesson authoring checkpoints", () => {
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
      screen.getByRole("button", { name: "Approve transcript" }),
    ).toBeDisabled();
    vi.mocked(api.saveTranscript).mockRejectedValue(
      new ApiError(409, "Changed"),
    );
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save transcript" }));
    });
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
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(screen.getByText(/“New \/ York” rocks\./)).toBeVisible();
  });

  it("preserves unsaved transcript text when moving between the editor tabs", async () => {
    await show();
    fireEvent.change(screen.getByLabelText("Text for sentence 1"), {
      target: { value: "Unsaved correction." },
    });
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.queryByLabelText("Text for sentence 1"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Generate learning content" }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Transcript" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(screen.getByLabelText("Text for sentence 1")).toHaveValue(
      "Unsaved correction.",
    );
    expect(
      screen.getByRole("button", { name: "Save transcript" }),
    ).toBeEnabled();
  });

  it("does not unlock generation from ASR timing or transcript approval alone", async () => {
    current.approvedRevision = 1;
    await show();
    expect(screen.getByRole("tab", { name: "Transcript" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(
      screen.queryByRole("button", { name: "Generate learning content" }),
    ).not.toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.getByRole("tab", { name: "Learning content" }),
    ).toHaveAttribute("aria-selected", "true");
    expect(
      screen.getByRole("button", { name: "Generate learning content" }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Timing review" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.getByRole("button", { name: "Confirm karaoke review" }),
    ).toBeDisabled();
    expect(
      screen.getByText(
        "Grouped ASR timing needs manual review and may need dedicated alignment.",
        { exact: false },
      ),
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
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
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
    expect(
      screen.getByRole("button", { name: "Publish lesson" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("checkbox", { name: /I reviewed the translations/ }),
    ).toBeDisabled();
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
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
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
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Timing review" }), {
      button: 0,
      ctrlKey: false,
    });
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
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Timing review" }), {
      button: 0,
      ctrlKey: false,
    });
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
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save transcript" }));
    });
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
    expect(screen.getByLabelText("Text for sentence 1")).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Save transcript" }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Timing review" }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.getByRole("button", { name: "Run dedicated alignment" }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Learning content" }), {
      button: 0,
      ctrlKey: false,
    });
    for (const name of [
      "Regenerate learning content",
      "Save activity selection",
      "Publish lesson",
    ]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
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
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Inspect video" })); });
    expect(screen.getAllByRole("status")).toHaveLength(2);
    expect(screen.queryByLabelText("Lesson title")).not.toBeInTheDocument();
  });
  it("trims either edge of a full-video selection before preparing audio", async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({videoId:"abcdefghijk", title:"Video", durationMs:223000, embedAllowed:true, usedRanges:[]});
    await show("/lessons/new");
    fireEvent.change(screen.getByLabelText("YouTube URL"), { target: { value: "https://youtu.be/abcdefghijk" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Inspect video" })); });
    fireEvent.change(screen.getByRole("slider", {name:"Selection end"}), {target:{value:"25000"}});
    fireEvent.change(screen.getByRole("slider", {name:"Selection start"}), {target:{value:"5000"}});
    expect(screen.getByRole("textbox", {name:"Start"})).toHaveValue("00:05.000");
    expect(screen.getByRole("textbox", {name:"End"})).toHaveValue("00:25.000");
    fireEvent.change(screen.getByRole("slider", {name:"Selection start"}), {target:{value:"30000"}});
    expect(screen.getByRole("textbox", {name:"Start"})).toHaveValue("00:24.999");
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
      fireEvent.click(screen.getByRole("button", { name: "Inspect video" }));
    });
    expect(api.inspectSource).toHaveBeenCalledWith(
      "https://youtu.be/abcdefghijk",
      expect.anything(),
    );
    expect(
      screen.getByText(
        "This segment overlaps an existing lesson. You may continue.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("checkbox", { name: /responsibility/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load waveform" })).toBeEnabled();
    expect(
      screen.getByRole("button", { name: "Prepare transcript" }),
    ).toBeEnabled();
  });
  it("inspects a pasted URL automatically without starting acquisition or AI", async () => {
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
    expect(screen.getByLabelText("Lesson title")).toHaveValue("Auto source");
    expect(api.createDraft).not.toHaveBeenCalled();
    expect(
      screen.getByRole("button", { name: "Load waveform" }).closest("footer"),
    ).toBeInTheDocument();
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
      fireEvent.click(screen.getByRole("button", { name: "Inspect video" }));
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
      fireEvent.click(screen.getByRole("button", { name: "Retry inspection" }));
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
      fireEvent.click(screen.getByRole("button", { name: "Inspect video" }));
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
      fireEvent.click(screen.getByRole("button", { name: "Inspect video" }));
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
    expect(screen.getByText("The AI content did not match the transcript. Try generating the content again.")).toBeVisible();
    expect(screen.queryByText("Thought groups must sequentially partition source")).not.toBeInTheDocument();
    expect(document.querySelector('a[href="/settings/ai#routes"]')).not.toBeInTheDocument();
  });
  it("collapses previous attempts while keeping active jobs and the latest failure visible", async () => {
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
    const history = screen.getByRole("button", {
      name: /Job history.*2/,
    });
    expect(history).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Active alignment")).toBeVisible();
    expect(screen.queryByText("Failed attempt stays visible")).not.toBeInTheDocument();
    expect(screen.getByText("Cancelled attempt stays visible")).toBeVisible();
    expect(
      document.querySelector('a[href="/jobs?job=completed-job"]'),
    ).not.toBeInTheDocument();
    fireEvent.click(history);
    expect(history).toHaveAttribute("aria-expanded", "true");
    expect(
      document.querySelector('a[href="/jobs?job=completed-job"]'),
    ).toBeVisible();
    expect(screen.getByText("Active alignment")).toBeVisible();
    fireEvent.click(history);
    expect(history).toHaveAttribute("aria-expanded", "false");
  });
  it("keeps mixed terminal job details in collapsed history after publication", async () => {
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
    const history = screen.getByRole("button", { name: /Job history.*3/ });
    expect(history).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByText("Earlier content attempt failed"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Earlier alignment was cancelled"),
    ).not.toBeInTheDocument();
    fireEvent.click(history);
    expect(history).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Earlier content attempt failed")).toBeVisible();
    expect(screen.getByText("Earlier alignment was cancelled")).toBeVisible();
    expect(
      document.querySelector('a[href="/jobs?job=completed-retry"]'),
    ).toBeVisible();
  });
});
