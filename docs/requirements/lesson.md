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
Admin checks valid YouTube video URLs automatically after typing stops and shows caption availability
next to the URL. Inspection identifies a usable publisher-provided or original auto-generated English
track, with preference for the publisher-provided track. The English-caption option is checked
for publisher-provided tracks only. Auto-generated tracks require an explicit selection. An
unchecked option uses configured audio transcription.
A selected track that cannot be loaded or used in the chosen range gives a distinct error with retry
and WhisperX actions. An Admin can also supply an SRT or WebVTT file for the exact video version.
Caption timing anchors each line
relative to the selected range. Local alignment derives individual word timing. Automatic and uploaded
captions stop for transcript review before alignment. The Admin draft shows the transcript source.
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
provider và model/version, và phải invalid khi text đổi. Trong content authoring hiện tại, bật IPA
tạo cho toàn bộ câu bằng eSpeak local, lưu accent/version và cờ cần kiểm tra; không chạy STT.

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

### FR-LSN-006A — Admin pronunciation pilot

Hộp thoại Thử chấm phát âm mở ngay tại câu Shadowing đã chọn trong danh sách câu hoặc Shadowing
Preview. Với bài nháp đã lưu và bài đã xuất bản, Admin có thể nghe mẫu, thu âm hay tải tệp, nghe thử bản thu và xem
feedback từ Azure Pronunciation Assessment. Core tính giới hạn thu từ thời lượng mẫu.
Khi nghe câu mẫu trong hộp thoại, karaoke làm nổi từ đang phát theo mốc của bài học.
Kết quả gắn với revision đã gửi. Cùng một màn hình hiển thị bốn loại điểm, từ thiếu, từ thừa và từ
sai phát âm ngay trên câu đã chấm. Chọn từ để xem âm vị và nghe lại theo mốc khi có. Với âm vị có
trong [bảng IPA của Kho ngôn ngữ](lexicon-vocabulary.md#fr-lex-005--admin-ipa-chart), Admin
có thể nghe riêng âm mẫu, nghe từ ví dụ và đọc hướng dẫn song ngữ để đối chiếu với bản thu.
Khi mở rộng dữ liệu Azure, Admin có thể mở payload thô của lượt chấm để đối chiếu các trường điểm,
mốc và âm vị mà adapter đã nhận. Mốc nghe lại của từ dùng đệm kỹ thuật ngắn để tránh che lấp từ kế bên.
Hàng câu trong Shadowing Preview có tóm tắt kết quả còn hạn của revision hiện tại. Bản thu thiếu
tín hiệu hiển thị hướng dẫn thu lại. Chỉ Admin tạo lượt có quyền truy cập dữ liệu chi tiết.
Bản thu, transcript, điểm và feedback hết hạn sau 24 giờ. Lượt thử nằm ngoài tiến độ và điểm Learner.

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

Admin uses one workspace for YouTube, Audio and Text, with live processing status rather than
mandatory Next screens. Source inspection skeletons match the player/timeline and metadata regions.
Creation and each queued/running/retrying/cancelling step show explicit feedback; completed stages
remain visible. Duplicate submissions and source switching are disabled while creation/upload is pending. One explicit Create lesson starts transcript preparation → alignment → content for media, subject to caption review above, or
TTS → alignment → content for Text. Text never runs STT. Every checkpoint and follow-up enqueue
share a short fenced transaction; recovery cannot create duplicate successors. Cancellation prevents
continuation, and failed alignment/content preserves valid transcript/audio for targeted retry.
The timing-reuse policy remains explicit. Legacy hidden draft rows participate in full alignment;
explicit saves/successful alignment normalize flags, GET never writes, and published data retains its semantics.

One sentence list exposes direct transcript/translation edits, observed time bounds, playback,
independent activity choices, highlighted notes and an optional global IPA display. Each row has
direct actions to split at the transcript caret between words and merge with the next sentence.
Timing uses the existing interval editor. Text/voice changes stale audio;
other lexical edits stale dependent timing/content. Saving, typing, blur, options changes and opening
pages never invoke AI. Update affected parts explains the next capabilities before explicit execution.
Global Save changes and Ctrl/Cmd+S persist the batch atomically. Conflicts/network errors preserve
local input. Dirty fields survive polling and focused rows survive virtualization above 20 sentences.
The natural page scroller owns the list; the laptop player is sticky and playback/main actions stay
in a fixed bottom dock with reserved space. Settings use lesson information, learner support, notes
and activities groups. Counts/duration/pace are read-only observations, separate from editable metadata.

No listening, pronunciation or content confirmation checkbox is required. Successful AI execution
creates a ready draft without fabricating human review. Quality warnings do not block publication;
missing audio, invalid/stale timing and incomplete enabled data do. Conflicting writes are disabled
during processing; playback remains available. A compact processing table exposes concrete backend
failures immediately, and an Admin-only diagnostic panel provides invocation IDs, causes, batch,
usage, retries and safe raw output. Old jobs explicitly report absent records. Diagnostic artifacts
are private, bounded, redacted and retained for seven days; they do not provide arbitrary server-log access.

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
are distinguished; these rules do not claim ASR confidence or pedagogical quality. Dedicated alignment may retain zero-duration tokens in an explicit observed phrase group within the same sentence, including at least one positive observed interval, with GROUPED_TIMING shown as a quality warning. It must preserve all text and token coverage; wholly unresolved sentences, reversed intervals, overlaps and out-of-bounds intervals still fail.
Alignment grouping respects approved sentence boundaries. After a partly successful result, Core may
make one additional alignment call for wholly unresolved sentences using their original, non-overlapping
observed source anchors. It keeps resolved timing, never estimates durations, and applies the same complete
coverage/interval checks afterward. A wholly unsuccessful result is not retried automatically.
An apparent repeated suffix is an Admin review hint, not permission to remove transcript text.

### FR-LSN-008 — Contextual generation and candidate review

Admin configures translation, reference IPA, vocabulary, grammar, independent Dictation/Shadowing
activities and lesson profile; all default on. Create lesson automatically continues into CONTENT;
subsequent generation requires an explicit Update affected parts. Enabled translation/IPA covers
all sentences, even unselected ones. Disabled features retain their stored data and make no calls.
Core combines enabled work in route-budgeted numbered-text batches. Compact JSON carries translations,
notes with exact phrases/occurrence, profile and activity exclusions. Code maps Unicode, whitespace,
quotes and boundary punctuation conservatively to exact original token spans; no fuzzy matching.
Invalid notes become visible warnings and are excluded from learning, while valid features/sentences
checkpoint independently. Missing translations/failed IPA remain incomplete for targeted retry.
A single-object JSON array wrapper can be normalized losslessly; multiple root objects are rejected.
Truncation splits only the failed batch; malformed JSON fails clearly without repair calls or loops.
Usage includes failed, truncated and fallback attempts. Manual edits are protected; stale manual
values need explicit reconfirmation or editing. Timing-only edits keep translation/IPA/note results.
AUTO note budgets use ceil(words/75) for vocabulary and ceil(words/175) for grammar. CUSTOM maxima
are 0–100 vocabulary and 0–30 grammar. Later batches can use unused earlier capacity within the
whole-lesson cap. Admin shows generated and manually edited counts. Manual edits do not consume the cap.

Spoken lines with valid timing are selected by default. Dictation needs words a learner can transcribe,
and Shadowing needs a usable spoken phrase to repeat. The LLM returns only clear exceptions for each
activity, using PROMOTION, NON_SPEECH, BROKEN_FRAGMENT or CODE. Keep short replies, lyrics, repetition,
simple statements, transitions and uncertain cases. Text-only inference never claims audio quality.
The server excludes explicit sound cues in existing drafts for both activities. Admin can select or
deselect eligible lines, restore suggestions, undo before saving or override an AI suggestion. Unselected
sentences remain visible in view mode.

Dictation preview has fewer blanks (~20%), more blanks (~40%) and full sentence levels, independent
of CEFR. Deterministic code prioritizes validated notes then suitable content tokens, retains context,
limits repeated targets and excludes punctuation, URLs, unclear/function tokens. Easy is a subset of
medium; short/unsuitable sentences fall back to full dictation. Manual token targets override automatic
ones, store spans/text hash/version and become stale when transcript changes. Answers come from the transcript.
No learner attempt or score is implemented in this phase.

Local eSpeak uses a prepared copy of the transcript for reference pronunciation. The original text
and timing stay available for review. It stores engine output, learner display, accent and versions
separately. The display uses English dictionary conventions and keeps meaningful vowel and stress
differences. Admin sees review reasons at the source word or phrase and can open the engine output.
Existing manual IPA and published snapshots retain their saved values.

Lesson profile uses fixed enums: CEFR `A1|A2|B1|B2|C1|C2|UNKNOWN` and type
`CONVERSATION|MONOLOGUE|LECTURE|STORY|NEWS|MIXED|OTHER|UNKNOWN`. Suggestions are editable.
CEFR describes transcript vocabulary/syntax, not certified listening difficulty. Type labels describe
the dominant content form; uncertain evidence uses UNKNOWN. Topics remain existing categories and
description stays manually edited. Code computes word/sentence counts, clip duration, words per
minute over the clip and practice counts. No multilingual metadata generation is introduced.

This phase ends with Admin preview, publication and management. Learner presentation, scoring/rewards, missions
and subscription entitlement policies remain separate; see
[OQ-007](open-questions.md#oq-007--lesson-exercise-selection-and-publication).

### BR-LSN-006 — Atomic immutable publication

Publication checks revision, active jobs, current audio/transcript/timing, enabled required data and
nonempty enabled activities on the server. Warnings/UNKNOWN CEFR do not block. One atomic transaction
snapshots sentences, timing, content/IPA, options and activity membership, with publisher/time. Retrying
publication cannot duplicate snapshots. Published learning content and membership remain immutable;
metadata remains editable. An editable duplicate has a new ID and sentence identities, reuses media
and valid results without inference, and never replaces the original lesson or learner history.

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

An inactive draft with no learner history may be permanently deleted after a named consequence
confirmation. Delete only draft-owned rows; retain shared media/source records. Published lessons
are soft-deleted, stop accepting new practice and retain learner history. The Deleted list filter
supports restoration as hidden. Processing lessons cannot be deleted/published. Management writes
use draft revision or metadata version; stale actions fail without losing local input. Catalog filters
cover status, source, category, CEFR and activity, distinguishing individual/channel hiding, deletion
and AI failure. Admin may unlist/relist published
lessons using availability independent of content/build status. Unlisted lessons cannot accept new
practice and must be excluded from future learner discovery; existing attempts/progress remain.
Published transcript, timing, annotations and selected activity membership remain immutable.

Admin manages YouTube source channels independently by channel ID. Channel names/avatar URLs can
be synchronized explicitly for one channel or a selected batch of at most 50 via durable jobs.
Missing metadata or provider errors must preserve previously valid metadata. Credentials stay in
Core configuration; unconfigured synchronization reports a safe actionable conflict. This does not
import videos or create lessons automatically.
Admin may delete a source channel only when it has no linked lessons, including soft-deleted lessons,
and no active synchronization job. A named confirmation explains that deletion removes channel
metadata only; cached sources/media and job history remain. Channels with lessons use Hide instead.
The channel list filters by visibility and whether there are current lessons. It shows any remaining
soft-deleted lesson count separately and links to those lessons, which are still available for recovery.
URL inspection caches the video metadata. The channel enters the managed list on lesson creation.
The backend serializes deletion with synchronization and new lesson creation. Explicitly creating a
lesson from a cached source may recreate its missing channel metadata.

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
The lesson list displays the Free/Plus badge. Admin changes Plus access in the lesson editor or plan console.

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

Admin của bài học đã công bố chỉ hiển thị thao tác xem nội dung, không có nút công bố lại hoặc
nhãn “xem trước và công bố”. Metadata và ẩn/hiện tiếp tục theo luồng quản trị hiện có.
