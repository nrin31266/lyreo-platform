# Yêu cầu Lesson, Dictation và Shadowing

Cross-boundary workflow: [Lesson Build](../features/lesson-build.md).

### FR-LSN-001 — Lesson source

Admin authoring supports YouTube, Text and uploaded Audio through the same review checkpoints.
Mobile playback/practice UI remains deferred. YouTube/Audio sources can be reused across lessons;
each media-based lesson selects one contiguous range of at most 10 minutes before inference; 2–5 minutes is recommended.
A new YouTube source selects the whole video when its duration is at most ten minutes by default.
The public Admin build configuration can lower or disable this auto-selection threshold, capped at ten minutes.
Longer videos start without a selected range and show a short selection hint; full-video selection is hidden above ten minutes.
The playhead starts at the URL offset (otherwise zero), independently of selection. Admin can adjust start/end from playback,
drag/resize or enter timecodes; preparation requires both valid endpoints. An invalid mark preserves the previous selection and shows an error.
Source inspection runs only after Search or Enter; typing, pasting and blur do not inspect.
Search also refreshes/retries. Refreshing the same URL preserves edited metadata and a valid range;
an invalidated range resets to the same two-minute default. Editing the URL invalidates the inspected
source and stale responses cannot restore it.
Metadata exposes the full source timeline; Admin may move/resize the range anywhere. The editor
uses one timeline with a labelled time ruler, resizable range handles and a playhead. Zoom in/out,
horizontal scrolling, full-video fit and selection fit change only the visible time window.
Vertical mouse-wheel over the timeline zooms around the pointer without seeking or changing the
selection; horizontal/Shift-wheel and browser Ctrl/Meta-wheel keep their native behavior.
Clicking or dragging on the ruler seeks without changing the selection; hovering shows the time
at the pointer and keyboard seeking remains available. Without a complete range, the timeline still
allows seeking and displays a pending start marker; loop and selection fit remain unavailable.
Desktop source selection uses a viewport-sized grid: 16:9 video and metadata above a full-width
timeline, with playback and submission controls always visible. Only overflowing metadata scrolls;
narrow screens can scroll naturally. Playback, zoom and selection controls are grouped separately; lesson title/categories stay visible while used ranges and optional metadata
are collapsed by default. Icon controls expose hover/focus help, and longer instructions open on click.
Waveform preparation is deferred.
URL inspection and selection do not acquire audio or run inference. Explicit transcript preparation starts media acquisition.
Overlap warns, not blocks.

### FR-LSN-002 — Independent build dimensions

Source, selected activities, annotations/enrichment và pronunciation strategy là
các chiều độc lập. Preset chỉ pre-fill; request/snapshot cuối mới điều khiển build.

<a id="br-lsn-001--content-annotation-activity-tach-biet"></a>
### BR-LSN-001 — Content, annotation, activity tách biệt

Content chứa source/transcript/sentence/segment/media/timestamp; annotation gắn
hỗ trợ ngữ cảnh; activity là learning loop có attempt/scoring/progress. Contextual lexical/grammar
note không phải Vocabulary/Grammar Practice.

### BR-LSN-002 — Conditional build plan

Planner chỉ thêm capability thật sự cần; không mặc định generate text, TTS, STT,
alignment hoặc mọi enrichment cho mọi Lesson.

### FR-LSN-003 — Canonical audio playback

Lesson ưu tiên một canonical media với sentence start/end offsets. Derived clips
chỉ tạo on-demand khi offline/precision need biện minh; không mặc định cắt hàng trăm MP3.

### FR-LSN-004 — Contextual pronunciation

Sentence IPA là optional theo admin policy `DISABLED|ON_DEMAND|PREGENERATE` và
learner display `OFF|TAP_TO_SHOW|AFTER_ATTEMPT|ALWAYS`; cache key phụ thuộc text hash, accent,
provider và model/version, và phải invalid khi text đổi.

<a id="dictation"></a>
## Dictation

### FR-LSN-005 — Dictation attempt feedback

Learner nghe với repeat/speed, gửi answer text và nhận expected answer, diff,
server score, cùng annotation theo preference. Proper-name/number/acronym hints có thể hiển thị trước.

### BR-LSN-003 — Dictation scoring authority

Server chuẩn hóa và chấm answer; client không gửi score. Punctuation,
capitalization, apostrophe và number policy có thể được cấu hình khi có owner policy rõ.

### BR-LSN-004 — Completion facts riêng biệt

Một sentence attempt, activity completion và lesson completion là các fact khác
nhau. Event chỉ phát khi transition thật sự xảy ra. Passing threshold chưa được quyết định: [OQ-002](open-questions.md#oq-002--dictation-completion-threshold).

<a id="shadowing"></a>
## Shadowing

### FR-LSN-006 — Shadowing practice

Experience gồm reference audio, karaoke word highlight, thought groups/content
emphasis, recording, ASR/alignment, deterministic word accuracy/timing/fluency feedback, và optional
deep judge. Deep judge bổ sung feedback nhưng không thay quyền chấm điểm cuối của server.

### BR-LSN-005 — Speech score authority

`speech-assessment` tạo final score; client không tự chấm. Context notes ưu tiên
`AFTER_ATTEMPT` để practice screen gọn.

## User Stories & Acceptance Criteria

<a id="us-lsn-001--tao-lesson-theo-option"></a>
### US-LSN-001 — Tạo Lesson theo option

Là content manager, tôi muốn chọn source, activities và enrichments độc lập để chỉ chạy xử lý cần thiết.

#### AC-LSN-001 — Build được chấp nhận

Given request hợp lệ và quyền Admin, when gửi build, then Core tạo Lesson/job/step plan durable, trả
`202` cùng location job và snapshot final options.

#### AC-LSN-002 — Conditional plan

Given hai tổ hợp option khác nhau, when planner lập plan, then chỉ capability bắt buộc xuất hiện và
không mặc định generate text/TTS/STT/alignment.

<a id="us-lsn-002--lam-dictation"></a>
### US-LSN-002 — Làm Dictation

Là learner, tôi muốn nghe và gửi câu chép để nhận feedback server-side và tiến độ chính xác.

#### AC-LSN-003 — Dictation result

Given sentence thuộc activity/Lesson, when learner gửi answer text, then server lưu attempt, tính
score, trả expected text/progress và chỉ phát completion event khi transition lần đầu xảy ra.

#### AC-LSN-004 — Dictation input không hợp lệ

Given answer thiếu hoặc sentence không thuộc Lesson/activity, when submit, then request bị từ chối và
không có score/reward từ client được chấp nhận.

<a id="us-lsn-003--nhan-feedback-shadowing"></a>
### US-LSN-003 — Nhận feedback Shadowing

Là learner, tôi muốn record theo reference audio để nhận word accuracy, timing và fluency feedback.

#### AC-LSN-005 — Speech assessment

Given recording hợp lệ và reference, when server assessment hoàn tất, then result liên kết learner
attempt, actual ASR/alignment audit và server scores; context notes tuân display preference.

#### AC-LSN-006 — Cancel/private artifact

Given assessment bị cancel hoặc user không được quyền, when output trở về/access được yêu cầu, then
stale output không commit và private recording không lộ.


## Admin authoring checkpoints

### FR-LSN-007 — Transcript and karaoke review

Admin authoring uses one workspace from source preparation through transcript/timing review, with
no numbered step navigation. All three source modes share metadata and action placement. YouTube/Audio prepare transcripts explicitly; Text saves without AI
and requests Kokoro speech explicitly from the source action. Source mode changes confirm before discarding input.
YouTube/Audio preparation saves transcript/audio and automatically queues alignment; explicit Text
speech generation saves audio and queues alignment without STT. Each preparation checkpoint is fenced and
atomic with its follow-up enqueue; recovery cannot enqueue duplicate alignment. Initial alignment
never approves words or listening review. Valid provider timing may be reused only under the existing
explicit timing-reuse policy. Failed/cancelled alignment retains transcript/audio and retries only alignment.
One sentence list combines direct text editing, split/merge, observed or explicitly approximate timing,
karaoke, sentence playback and the existing manual timing dialog. Editing pauses karaoke and marks
timing for updating. Save and realign explicitly saves the transcript and requests alignment; Text
lexical changes require explicit audio regeneration. Typing, blur, opening the page and selection changes
never invoke AI. Save reviewed draft approves the transcript and records listening review only after
explicit listening confirmation; the lesson stays unpublished. Later edits invalidate approval. Laptop authoring keeps playback and the contextual primary action in a
bottom dock; unchanged valid drafts do not offer a no-op save/realign. Grouped word timing is shown as
compact information, while timing validation errors remain visible.
The complete transcript participates in alignment, validation and draft listening. There is no sentence
visibility control. Legacy hidden fields remain readable; draft rows with these flags stay visible and
require complete alignment and renewed review. Explicit saving or successful alignment normalizes
legacy flags; GET never writes. Published snapshots retain their stored semantics. Exercise sentence
selection remains outside this workflow.
Text shows the saved source and selected voice until canonical audio is ready; preliminary sentence
splitting is not presented as model output. The saved voice remains selected in the source pane.
Text already supplies the transcript, so it is never sent through STT/Groq again.
A counted filter shows saved short/long text warnings; interval and coverage errors belong to timing review.
The sentence list virtualizes above 20 rows, keeps focused editors mounted and preserves local unsaved edits across polls.
The desktop player can collapse and resize; on laptops the sentence list and expanded source details
scroll above the reserved bottom dock.
Primary actions occupy reserved footer space and do not cover metadata. Source trimming stays in step 1.

Processing feedback is prominent above the workspace, with the operation/stage, durable job status,
elapsed duration and real progress when supplied. Unknown progress is indeterminate. Queued, running,
quota/retry wait, cancellation, success and failure remain distinct. Retry invokes the failed supported
operation; cancellation is explicit. Technical usage/history remains in an expandable bottom sheet.
Conflicting writes are disabled while processing, but validated previous media remains playable.
Connection failures do not discard local edits; polling resumes observation when SSE is disconnected.

Opening a draft never starts playback. Sentence/word actions play immediately; default playback stops
at the observed sentence end. Continuous playback runs uninterrupted to the selected clip end,
including all speech and silence. It never seeks at sentence boundaries; sentence
and word emphasis follow the actual media clock, with no highlighted sentence during gaps. Loop and
continuous modes are mutually exclusive. Revision changes, transcript editing and timing editing pause playback.
Active timing review shows plain clickable words with foreground emphasis and a fitted underline
beneath only the currently spoken observed word/group, driven by observed timing and the shared playback clock.
Previous words lose their underline; silence shows none. No word or sentence background is filled.
Detailed per-word timecodes appear only in the timing editor. Missing timing is never simulated.

Timing review exposes observed words/groups and permits revision-checked manual interval edits without
altering lexical coverage or estimating missing word durations. Blocking timing errors and review warnings
are distinguished; these rules do not claim ASR confidence or pedagogical quality. Dedicated alignment may retain zero-duration tokens in an explicit observed phrase group within the same sentence, including at least one positive observed interval, with GROUPED_TIMING requiring human review. It must preserve all text and token coverage; wholly unresolved sentences, reversed intervals, overlaps and out-of-bounds intervals still fail.
Alignment grouping respects approved sentence boundaries. After a partly successful result, Core may
make one additional alignment call for wholly unresolved sentences using their original, non-overlapping
observed source anchors. It keeps resolved timing, never estimates durations, and applies the same complete
coverage/interval checks afterward. A wholly unsuccessful result is not retried automatically.
An apparent repeated suffix is an Admin review hint, not permission to remove transcript text.

### FR-LSN-008 — Contextual generation and candidate review

Content/enrichment generation, IPA authoring, Dictation/Shadowing sentence selection and publication
are deferred. Their authoring UI and write APIs are removed. Existing stored annotations, options,
published snapshots and learner practice/history remain readable. See
[OQ-007](open-questions.md#oq-007--lesson-exercise-selection-and-publication).

### BR-LSN-006 — Atomic immutable publication

Existing published content and selected candidate membership remain immutable. The two-step authoring
workflow has no publication action or API. Old APIs/writers must not destroy existing learner
attempts/progress. Draft revision prevents concurrent overwrites; stale AI/job output cannot commit.

### BR-LSN-007 — Free-only and retirement gates

For local development, operators may select any supported public YouTube source without a mandatory rights-attestation checkbox; channel allowlists and licence-document checks are not required. Only confirmed free external paths or local inference may execute. Quota exhaustion waits durably
or uses a tested configured free alternative, never paid fallback. Source rights/acquisition must
be verified before production. Retire the old authoring tool only after regression coverage and
replacement verification; preserve user artifacts and business data.

### FR-LSN-009 — Admin catalog and classification

Admin lists/searches/filters/pages lessons, resumes drafts after refresh/failure, and views published
content. Categories are flat and many-to-many. Source channels use YouTube channel IDs/metadata;
Audio/Text and legacy lessons may have no channel. Classification metadata is editable independently
of immutable published content and does not invalidate transcript approvals. Used categories cannot
be silently deleted. Presentation metadata (display title, description, HTTP(S) image URL and categories) is editable for
both drafts and published lessons without invalidating learning-content approvals. Display metadata
is separate from the immutable publication snapshot. A source thumbnail is the default image;
missing or broken images have a fallback.

Admin may soft-delete drafts only after active jobs have stopped. Deleted drafts disappear from
catalog, authoring lookup and source-range listings; shared media and sources remain intact.
Published lessons cannot be deleted through this operation. Admin may unlist/relist published
lessons using availability independent of content/build status. Unlisted lessons cannot accept new
practice and must be excluded from future learner discovery; existing attempts/progress remain.
Published transcript, timing, annotations and selected activity membership remain immutable.

Admin manages YouTube source channels independently by channel ID. Channel names/avatar URLs can
be synchronized explicitly for one channel or a selected batch of at most 50 via durable jobs.
Missing metadata or provider errors must preserve previously valid metadata. Credentials stay in
Core configuration; unconfigured synchronization reports a safe actionable conflict. This does not
import videos or create lessons automatically.

### FR-LSN-011 — Text and uploaded Audio authoring

Text starts as an editable draft (1–10,000 characters, up to 1,000 sentences), without AI execution. Admin chooses a
Kokoro US/UK voice from server discovery and explicitly requests speech synthesis. Only local Kokoro
routes execute this operation; no provider fallback is allowed. The configured default voice is
preselected. Text is the canonical transcript: generated speech is never transcribed again.
Sentence boundaries supplied by the synthesizer are measured from actual PCM samples, not estimated
from text. Each generated sentence must fit the existing 115-second alignment window, and canonical
audio must fit ten minutes. Over-limit synthesis fails without truncating the saved text.
Changing sentence text (including punctuation/splits/merges) or voice invalidates generated audio,
timing and approvals. A fresh explicit synthesis and timing review are required before completing review;
late output for the previous text/voice cannot commit.

Audio sources accept MP3/WAV/M4A uploads only, at most 30 MiB and two hours. Core probes and decodes
the file before retaining it privately; filenames/MIME declarations alone are insufficient.
Admin previews and selects one contiguous range of at most ten minutes (default up to two minutes).
Only explicit preparation normalizes the selected segment and calls STT. Source IDs persist across
refresh; arbitrary audio URLs and client storage keys are not accepted. Prepared Text/Audio use the
same transcript and alignment review checkpoints as YouTube, ending at a reviewed draft.

Categories remain flat CRUD without visibility. Admin manages categories and YouTube channels in
one page with separate URL-addressable tabs. A channel's visibility gates every associated lesson,
including future publications, without modifying individual lesson visibility. Relisting the channel
keeps individually unlisted lessons hidden. Learner availability requires publication, no deletion,
individual visibility and channel visibility (when applicable). Text/Audio require no channel.
Historical attempts/progress remain intact, and Admin retains access for management.

### FR-LSN-010 — Evidence-based timing reuse

Groq supplies transcript and draft word/segment evidence. After transcript approval, reuse is allowed
only when lexical tokens still match, validation passes and the centrally configured quality policy
permits it. Missing/stale/invalid timing requires local forced alignment. Benchmark actual 2/5/10-minute
lessons against the same transcript and human-checked word references, including listening/karaoke
review and nonzero source offsets. Without adequate evidence, require local alignment temporarily;
local output also requires validation and explicit karaoke approval before completing review.

### Voice previews, source titles and localized categories

English remains the lesson language; Admin UI locales are Vietnamese and English. Kokoro local
uses library 0.9.4 and Kokoro-82M v1.0. The picker groups all 28 English voices by US/UK and
female/male with friendly names; IDs remain stable. Listening and choosing are separate keyboard
accessible actions, with one preview at a time. A Core job prepares the same fixed English passage
at speed 1.0 for every voice, stores WAVs and a versioned manifest, reports progress/errors and
reuses valid successes on retry. Listening only retrieves stored authenticated Admin media.

All three source modes share a heading, tabs, source-left/metadata-right layout and metadata fields.
Creation title is optional: first normalized Text sentence (120 characters maximum), inspected
YouTube title, or original Audio filename (generic fallback for older uploads). Successful Audio
STT replaces an automatic title once with the first sentence; a manual rename wins over late STT.
Other metadata edits preserve the automatic marker. Title edits do not invalidate content approval;
database titles remain non-null. Categories have one ID/default English name plus locale translations;
missing translations fall back to the default name. Admin edits the default and Vietnamese names.


### External AI preparation prompts

Each creation mode offers a localized, copyable prompt with a short return-to-Lyreo guide.
The external AI asks for topic/goal, CEFR level and maximum length before preparing material.
Text prompts request original English for a single Kokoro narrator within the authoring limits,
and redirect requests for multiple voices to a single-person retelling or real Audio/YouTube sources. YouTube prompts request verified
video links, or search queries when browsing is unavailable. Audio prompts cover existing files,
finding recordings and writing a script, with file-upload constraints and a Text/Kokoro alternative.
Prompts ask only for missing brief details, distinguish verified source facts from estimates and
return a clean passage/script block with instructions outside it. Copying is explicit and sends no requests to Core or an AI provider. Clipboard failure leaves the
prompt available and selected for manual copying; existing draft fields remain intact.

The prompt dialog selects YouTube/Text/Audio independently from the underlying lesson editor.
It contains only this source selector, the fixed prompt preview and copy action. The external AI
uses a guided conversation by default to clarify topic, level, length and quantity; authors fill no
configuration fields in Lyreo. Optional discovery is a short note at the end of the prompt and of
the AI's first reply, rather than an initial Custom/Random choice. Explained titles, idea discovery and source verification remain in
the base prompts. Switching prompt type never changes the editor's source or its draft fields.
