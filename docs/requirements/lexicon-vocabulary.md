# Yêu cầu Lexicon và Vocabulary

Data contract: [Data Pipelines](../DATA_PIPELINES.md).

### FR-LEX-001 — Global dictionary

Lexicon hỗ trợ search word, phrase, phrasal verb, idiom và collocation; entries có
forms/lemma, senses, pronunciation, source/license và Vietnamese translation status.

### BR-LEX-001 — Lexicon không phải Vocabulary

Lexicon là global knowledge; Vocabulary là learner-owned SRS card tham chiếu
`headwordId` trường tồn qua các release. Vocabulary không sở hữu dictionary definition.

### FR-LEX-002 — Contextual lexical resolution

Lesson detector có thể link Lexicon entry; nếu chưa có, giữ surface/context
meaning và `UNRESOLVED` để Lesson vẫn dùng được, rồi lazy-resolve sau.

### BR-LEX-002 — Provenance and coverage

Dictionary seed dùng structured Wiktionary/Wiktextract/Kaikki sources, giữ
attribution theo source/license. Corpus Lyreo xếp relevance, không giới hạn dictionary vào corpus;
LLM không generate toàn bộ dictionary.

### FR-LEX-003 — Pronunciation audio strategy

Ưu tiên recording thật; first use có thể fetch/normalize/cache object storage.
Device/server TTS là fallback on-demand, không pre-generate hàng chục nghìn từ.

### FR-LEX-004 — Admin dictionary workspace

Admin tìm headword/form, lọc loại mục, từ loại, nội dung Việt và trạng thái biên tập.
Một identity mục từ chứa nghĩa có ID và nguồn riêng; mặc định mở nghĩa Anh–Việt theo nhóm từ loại.
Tra thử dùng Anh–Việt mặc định cho giao diện tiếng Việt và Anh–Anh cho các locale khác.
Chuyển giữa hai chế độ độc lập với locale và giữ nguyên `headwordId`. Learner API trả `readingModes`
cùng hai nguồn nghĩa khi có, để caller hiện lựa chọn và thông báo thiếu dữ liệu.
Nghĩa Việt không cần định nghĩa Anh giả. Nghĩa Anh chưa có quan hệ tương ứng đã xác minh nằm ở
“Tham khảo Anh–Anh”; mục chỉ có tiếng Anh vẫn tra được và ghi rõ chưa có nghĩa Việt. Không ghép
hai nguồn theo thứ tự, POS hoặc độ giống chữ, không gọi AI khi mở mục từ.

Giữ ví dụ/bản dịch, biến thể, nhóm từ loại/ngữ nguyên và attribution từ từng nguồn. Nội dung giải
nghĩa dùng mã ngôn ngữ; đợt này chỉ biên tập EN/VI. Candidate cũ giữ provenance và lịch sử, không
được chuyển thành verified tự động; đây không phải điều kiện để dùng các nghĩa Việt từ nguồn gốc.
Nếu Admin liên kết một candidate, việc xác nhận tương ứng là thao tác riêng có kiểm phạm vi;
AI chỉ tạo đề xuất/nháp, không xác minh nguồn.

Phát âm nhóm theo cách đọc/trường hợp sử dụng → vùng/giọng → recording. Mỗi bản có dạng chữ,
ký hiệu và hệ ký hiệu (IPA hoặc ký hiệu nguồn), phạm vi có căn cứ và attribution. Nhãn vùng được
ánh xạ chính xác, giữ nhiều vùng và nhãn nguồn. Các bản ghi có cùng phiên âm và phạm vi được gom
theo cách đọc, với vùng của từng bản ghi. Bản ghi thiếu IPA hiển thị riêng. Ghép IPA với audio
dựa trên bản ghi nguồn cung cấp cả hai. Phạm vi từ loại dùng thông tin nguồn tường minh hoặc
nhãn từ loại trong tên audio khớp mục nguồn. TTS chỉ theo yêu cầu có phạm vi rõ và nhãn tổng hợp.
Learner API cung cấp nhóm để caller chọn đúng cách đọc; không xây Mobile UI trong đợt này.

Identity phân biệt hoa/thường; `headwordId` và liên kết Lesson/SRS trường tồn qua release.
CLI cài gói từ điển đã xác minh. Admin biên tập mục đã có và có thể xuất một mục để đối chiếu.

### FR-LEX-005 — Admin IPA chart

Kho ngôn ngữ có bảng 44 âm theo [IELTS Fighter](https://ielts-fighter.com/tin-tuc/bang-phien-am-tieng-anh-ipa_mt1567386908.html),
đối chiếu với [bảng IPA tương tác chính thức](https://www.internationalphoneticassociation.org/IPAcharts/IPA_charts_TI/IPA_charts_TI.html).
Âm mẫu phát trực tiếp từ 43 MP3 của IELTS Fighter. Từ ví dụ dùng bản ghi Commons đã cắt khoảng
im lặng, có ghi giấy phép và tác giả. Admin có thể phát riêng âm, riêng từ, hoặc âm rồi đến từ.
Mục /ʊə/ dùng [âm mẫu PolyU](https://elc.polyu.edu.hk/sounds/lesson4-2.htm)
và [từ tour /tʊə/ từ Oxford](https://www.oxfordlearnersdictionaries.com/definition/english/tour_1)
vì tệp /ʊə/ của IELTS Fighter trùng với tệp /uː/.
Hướng dẫn khẩu hình hiển thị tiếng Việt hoặc tiếng Anh theo ngôn ngữ hệ thống. Ký hiệu mẫu theo bảng Anh–Anh.
Dialog chấm phát âm giữ phần hướng dẫn gọn và mở bảng IPA đầy đủ ở Kho ngôn ngữ. Ký hiệu trong kết quả và điểm của Azure vẫn theo `en-US`.

### BR-LEX-003 — Editorial publication

Nguồn import bất biến. Admin biên tập mục đã có, lưu bản sửa thưa và tách nháp khỏi publication
bất biến. Mutation dùng revision và trả 409 khi stale. Công bố/phục hồi có actor,
thời điểm; ghi chú lý do không bắt buộc. UI không yêu cầu nhập lý do khi công bố.
Nội dung đã công bố không có nút công bố lặp; khi có sửa khác bản đang dùng thì hiện “Áp dụng thay đổi”.
Trạng thái thay đổi được so nội dung, không suy từ số revision; reload vẫn giữ đúng trạng thái.
Admin dùng Ẩn/Hiện trực tiếp tại dòng danh sách; không có thao tác Lưu trữ mới. Trạng thái lưu trữ
cũ vẫn được giữ để khôi phục, không xóa dữ liệu. Thanh Lưu/Áp dụng cùng lỗi thao tác luôn bám đáy workspace.
Áp dụng cập nhật giữ trạng thái ẩn/lưu trữ cũ, thao tác hiện lại độc lập. Gọi công bố lặp với cùng nội dung
không tạo publication/audit mới. Phục hồi nội dung khác tạo revision mới. Ẩn/lưu trữ có thể phục hồi. Chỉ xóa nháp chưa từng
công bố và không có tham chiếu. Learner chỉ thấy bản công bố hoặc nguồn chưa sửa, không thấy nháp.
Đổi release phải đối soát nguồn và bản sửa theo [Data Pipelines](../DATA_PIPELINES.md).

### FR-VOC-001 — Save and review card

Learner lưu lexical unit cùng optional context, review và nhận next scheduling
state/history/mastery.

### BR-VOC-001 — Scheduler abstraction

Domain dùng `SpacedRepetitionScheduler` port để có thể benchmark/migrate.
Implementation hiện tại là starter FSRS-compatible, không được mô tả là FSRS đầy đủ.

### BR-VOC-002 — Review fact

Review state thuộc Vocabulary; successful review fact có thể được Analytics và
Gamification consume qua public event, không truy cập repository nội bộ.

## User Stories & Acceptance Criteria

<a id="us-lex-001--tra-và-hiểu-lexical-unit"></a>
### US-LEX-001 — Tra và hiểu lexical unit

Là learner, tôi muốn tra word/phrase và xem nghĩa/phát âm/provenance để hiểu cả ngoài Lesson.

#### AC-LEX-001 — Search result

Given query hợp lệ, when search, then kết quả trả summary có entry ID, headword,
entry type, các part of speech, loại/text match, trạng thái còn kết quả và attribution
theo release. Khi mở detail của entry thuộc active release, response trả forms/senses/
pronunciation/source/license hiện có và không bịa Vietnamese translation khi source thiếu.

Search ưu tiên headword exact cùng case, form exact cùng case, headword exact đã
normalize, form exact đã normalize, prefix headword, rồi prefix form. Kết quả có
thứ tự deterministic và dedup theo entry ID; các entry khác ID không bị gộp vì cùng
text. Limit mặc định 20, tối đa 50; `hasMore` được xác định bằng một kết quả unique
bổ sung. Lookup dùng NFKC và full casefold theo clean release.

Trong từng nhóm exact form, xếp sau các match có tag trực tiếp `archaic`, `obsolete`,
`dialectal`, `nonstandard`, `misspelling` hoặc `dated`; cũng áp dụng nếu matching item
có senses và mọi sense đều có ít nhất một tag này. Item không có sense hoặc có bất kỳ
sense không gắn các tag này không bị xếp sau theo rule item. Không suy ra popularity
hay xác nhận standard usage từ việc thiếu tag, không lan nhãn sang item khác hoặc toàn entry.

Detail giữ item/sense references, raw glosses, examples, usage tags, matched qualifier
và pronunciation tags để client trình bày đúng context. Translation candidates giữ
source/scope/link status; `AVAILABLE` không đồng nghĩa đã được curator xác minh.
Nghĩa từ nguồn Việt được nhập với identity riêng, `definitions.vi`, ví dụ và metadata phát âm gốc,
kể cả mục không có trong nguồn Anh. `definitions.en` có thể vắng. Candidate từ nguồn Anh không có
sense relation được giữ chưa xác minh; không lấy candidate đó làm bản gốc của nghĩa Việt.
DTO công khai có nguồn nội dung theo nghĩa và pronunciation variants; không trả storage key hoặc
credential. Metadata nội bộ phục vụ đối soát giữ trong nguồn và bản nháp.
Detail HTTP trả `rawGlosses` và `tags` dưới dạng array string, `examples` dưới dạng
array object; giữ mọi thuộc tính example nguồn, kể cả null và JSON nested. Canonical JSON
sai cú pháp hoặc sai kiểu trả lỗi server chuẩn, không được thay bằng array rỗng hay ép kiểu.
Đây là clean cutover của pre-production detail contract từ các field `*Json`, không có
compatibility layer. `sourceMetadataJson` vẫn là opaque release provenance string và
`licenseText` giữ nguyên trong search/detail response.
Cached audio object keys thuộc nội bộ server, không xuất hiện trong detail response.

#### AC-LEX-002 — Unresolved lesson phrase

Given Lesson phát hiện phrase chưa có entry, when enrichment hoàn tất, then Lesson giữ contextual
meaning với trạng thái unresolved và vẫn usable; resolver có thể link sau.

<a id="us-voc-001--on-tu-theo-lich"></a>
### US-VOC-001 — Ôn từ theo lịch

Là learner, tôi muốn lưu lexical unit và review để nhận lịch tiếp theo cùng history cá nhân.

#### AC-VOC-001 — Save/review

Given Lexicon entry và authenticated learner, when save/review , then
card/history/scheduling state thuộc learner được cập nhật và review fact  được publish một lần theo contract.

#### AC-VOC-002 — Scheduler replaceability

Given scheduler implementation thay đổi có migration được duyệt, when application schedules, then
domain port/contract  giữ ổn định và existing learner state được xử lý theo migration plan.
