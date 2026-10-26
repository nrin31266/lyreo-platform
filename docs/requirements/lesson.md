# Yêu cầu Lesson, Dictation và Shadowing

Cross-boundary workflow: [Lesson Build](../features/lesson-build.md).

### FR-LSN-001 — Lesson source

Product supports Text, Audio and YouTube conceptually. The current phase implements YouTube-first
Admin authoring; Text/Audio UI and mobile playback/practice are deferred. Source identity is shared
across lessons; each V1 lesson selects one contiguous range of at most 10 minutes before inference; 2–5 minutes is recommended.
Videos up to 10 minutes default to their full duration, longer videos default to 0–5 minutes without
starting acquisition, preview preparation or inference. Metadata exposes the full source timeline;
Admin may move the range anywhere. Only explicit range confirmation starts media preparation.
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

Admin edits/splits/merges transcript, bulk-selects Learn/Watch only/Skip, and explicitly approves
transcript plus karaoke preview before semantic generation. Only stale dependencies recompute.
Timing requires real lexical coverage and observed intervals, not fabricated duration.
A waveform editor provides an overview, zoom, draggable/resizable region, synchronized millisecond
timecodes, seek and loop with one playback clock. Unknown waveform areas stay visibly unprepared.
Long-source preview cannot require downloading the complete source first.

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
