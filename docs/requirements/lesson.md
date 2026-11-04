# Yêu cầu Lesson, Dictation và Shadowing

Cross-boundary workflow: [Lesson Build](../features/lesson-build.md).

### FR-LSN-001 — Lesson source

Product supports Text, Audio and YouTube conceptually. The current phase implements YouTube-first
Admin authoring; Text/Audio UI and mobile playback/practice are deferred. Source identity is shared
across lessons; each V1 lesson selects one contiguous range of at most 10 minutes before inference; 2–5 minutes is recommended.
A new source preselects up to two minutes from the URL offset (otherwise zero), bounded by
source duration. The playhead starts at that offset. Admin can adjust start/end from playback,
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

Admin uses four numbered steps: Source, Words & timing, Content and Publish.
Words & timing has Edit words and Listen & check modes sharing the selected sentence and player.
Save, approve & align performs revision-checked saves, approval and (when required) alignment in order.
A failure stops the chain and preserves its last saved checkpoint; typing, blur and tab changes never invoke AI.
Steps 1–2 expose draft save and dedicated realignment as labeled icon actions.
On desktop the middle review list scrolls within the workspace; the player, toolbar and actions stay visible.
Source context and the final publication checklist have their own tabs.
Admin edits/splits/merges transcript, bulk-selects Learn/Watch only/Skip, and explicitly approves
transcript plus karaoke preview before semantic generation. Only stale dependencies recompute.
Transcript review always derives short/long sentence warnings from current saved text, including older drafts
whose stored issue list contains only a timing failure. A counted problem-sentence filter shows text issues
only; switching the filter clears bulk selection, and select-all applies only to visible sentences. Transcript review shows text/usage issues; timing coverage and interval warnings belong to the Timing step
and do not imply transcript preparation failed. Each row shows observed time bounds or an explicitly approximate preview anchor; no end time is fabricated. Split/merge and timing edits use direct icon buttons with tooltips, preserving the text caret. Bulk usage actions appear only for selected rows. Timing requires real lexical coverage and observed intervals,
not fabricated duration. The review workspace has no range selector; source trimming stays on source selection.
Processing status and job count appear in a compact trigger at the left of the action bar. Clicking opens
a bottom sheet with current tasks, failures, retry/cancel controls and newest-first history. The current error and retry are immediately visible; model usage and persisted timestamps are available in job details.
The compact footer combines processing status and persisted elapsed duration, playback/seek/loop/speed controls, and the current primary action. Connection/freshness detail remains in history. Retry, cancel and inspect use direct icon actions in history. The action bar omits repeated step instructions. Only the sheet body scrolls;
job cards/history do not consume the editor viewport while the sheet is closed.
The player toggle restores the pane; no separate layout-reset action is shown. Transcript review uses
direct sentence text fields without repeated preview text or word-level karaoke. Sentence preview prefers
word timing and falls back to an observed containing-segment start when word coverage is not yet valid.
Segment preview is approximate and does not satisfy timing review or activity eligibility.
Opening a draft never starts playback. In Edit words and Listen & check, a sentence play action or
word click starts that sentence immediately; the default stops at its observed end. Footer Play
resumes within the selected sentence or restarts it after its end. Before alignment, preview remains
bounded to the containing observed segment and is explicitly approximate. Footer continuous-play
and selected-sentence-loop icons are mutually exclusive modes; changing a mode never starts playback.
Continuous playback advances karaoke selection, scrolls to the next playable sentence, skips Skip
rows and stops after the last sentence. Switching review tabs, changing the draft revision or opening
the timing editor pauses playback. Seek buttons stay within the selected sentence in review.
Both review lists virtualize above 20 sentences, with measured row heights and a small overscan.
Focused transcript inputs stay mounted; timing renders word controls only for the selected sentence.
The same player toggle reflects button, keyboard and dragged collapse/expand. Desktop step 1–2
panes retain at least 240px for the player and 360px for the editor while expanded; collapsing
the player removes its gutter and reopening restores its previous width.
The timing list has a compact warning toolbar and a separate manual-confirmation row below it.
A source range editor provides draggable endpoints and whole-region movement, millisecond timecodes,
an integrated seek area above the range, external play/speed/loop controls and one YouTube playback clock. Waveform UI is
currently deferred. Playback updates must not rerender transcript/content forms on each clock tick.
Timing review exposes observed words/groups and permits revision-checked manual interval edits without
altering lexical coverage or estimating missing word durations. Blocking timing errors and review warnings
are distinguished; these rules do not claim ASR confidence or pedagogical quality. Dedicated alignment may retain zero-duration tokens in an explicit observed phrase group within the same sentence, including at least one positive observed interval, with GROUPED_TIMING requiring human review. It must preserve all text and token coverage; wholly unresolved sentences, reversed intervals, overlaps and out-of-bounds intervals still fail.

### FR-LSN-008 — Contextual generation and candidate review

Learn receives Vietnamese translation and sparse contextual vocabulary/grammar/notes. Watch-only
is context; Skip is excluded. Dictation/Shadowing priorities are independent and cannot override
Core technical eligibility. Admin defaults to HIGH and may select HIGH+MEDIUM or Custom. Exact
Lexicon links may remain unresolved; Grammar notes do not require Grammar Bank mapping.

### BR-LSN-006 — Atomic immutable publication

Only current, approved, valid content can publish. Publication and selected candidate membership
commit atomically and are immutable in V1. Old APIs/writers must not destroy existing learner
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

### FR-LSN-010 — Evidence-based timing reuse

Groq supplies transcript and draft word/segment evidence. After transcript approval, reuse is allowed
only when lexical tokens still match, validation passes and the centrally configured quality policy
permits it. Missing/stale/invalid timing requires local forced alignment. Benchmark actual 2/5/10-minute
lessons against the same transcript and human-checked word references, including listening/karaoke
review and nonzero source offsets. Without adequate evidence, require local alignment temporarily;
local output also requires validation and explicit karaoke approval before semantic generation.
