# Master plan backend Learner và bàn giao Mobile

> **Lưu ý cập nhật:** Đây là bản ghi quy hoạch backend ngày 25/11/2026. Đối với tài liệu bàn giao kỹ thuật hiện hành cho team Mobile (gồm kiểm toán `apps/mobile`, quyền sửa backend/DB, chiến lược Mobile-First vs Learner Web tương lai và bảng backlog phân rã task), xem [Mobile Handoff & Backlog](../mobile/HANDOFF_AND_BACKLOG.md).

**Phạm vi ngày 25/11/2026.** Mobile cần đi hết vòng chọn nội dung → học → thoát → tiếp tục → xem kết quả bằng token Learner. Thứ tự bên dưới là ưu tiên theo phụ thuộc. Khi triển khai, đối chiếu từng luồng với [OpenAPI hiện hành](../API_DOCS.md), giữ route đang có và ghi riêng quyết định sản phẩm cần chủ dự án duyệt.

## Trình tự giao việc

| Gói | Phụ thuộc | Đầu ra để đóng gói việc |
|---|---|---|
| A · Import, seed và định danh | Bắt đầu ngay | Setup từ cache rỗng, nâng cấp không mất nội dung Admin, phục hồi media theo checklist mục 1. Có bộ seed nhỏ để nhóm API chạy độc lập với Drive. |
| B · Entitlement và hợp đồng retry | Song song A | Ma trận operation → feature → cách tính usage. Test cạnh tranh trên PostgreSQL. Cho phép cấp grant thủ công trong dev. |
| C · Vocabulary và bộ từ cá nhân | Seed A, policy B cho thao tác bị giới hạn | Một learner mở unit, học sáu dạng, lưu đúng nghĩa vào bộ tự tạo và mở lại bộ sau khi đổi release. |
| D · Lesson, Grammar và TOEIC | Song song C sau khi có seed và hợp đồng chung | Học bài published, làm bài kiểm tra, khôi phục theo revision, nhận kết quả và phát sự kiện đúng cấp. |
| E · Curriculum và gamification | Facts từ C/D | Hai consumer có thể chạy song song. Replay hoàn thành không mở khóa, cộng tiến độ hoặc thưởng thêm lần thứ hai. |
| F · Handoff | C/D/E và kiểm quyền B | Luồng xuyên module bằng hai tài khoản, OpenAPI đối chiếu response thật, dữ liệu debug và danh sách chính sách đã duyệt. |

**Ranh giới triển khai.** Flyway thêm schema theo thứ tự merge. PostgreSQL giữ trạng thái phiên, quota và tiến độ. Domain nguồn sở hữu kết quả chi tiết. Curriculum, Analytics và Gamification nhận sự kiện qua hợp đồng module. Với import hoặc thay đổi schema, triển khai theo thứ tự thêm trường → backfill → so số lượng và tham chiếu → đổi đường đọc/ghi. Hoãn xóa trường cũ tới khi chứng minh được khả năng phục hồi.

## 1. Setup dữ liệu và seed debug — chạy song song với API

Đã liệt kê thư mục Drive `lyreo:` và đọc mục lục, cộng kích thước file trong ba archive local cùng tên. Dung lượng archive là sau nén, số byte bên trong là trước nén.

| Gói trên Drive | Kết quả kiểm tra | Việc cần làm |
|---|---|---|
| Grammar/TOEIC 2.0.12 · 2,5 GiB | Riêng thư mục media chiếm 3.150.108.811 byte. Các bảng câu hỏi, placement và tham chiếu asset nhỏ hơn nhiều. | Ưu tiên tách catalog cài DB và archive media phục hồi trong cùng folder Drive. Giữ ID/release lineage để session và bản sửa Admin vẫn tham chiếu đúng. |
| Lexicon 2.0.1 · 645 MiB | Riêng senses.jsonl là 1.244.033.519 byte. Pronunciations là JSONL, importer giữ URL âm thanh nguồn. | Giữ gói tra cứu. Tách media ở đây không giải quyết dung lượng từ điển. Nếu setup chậm, đo thời gian tải, giải nén và insert riêng. |
| Vocabulary 1.3.0 · 7,3 MiB | Đã có catalog, inventory asset và manifest. Có 20 bộ, 305 unit, 7.917 mục. Folder Drive liệt kê ba archive, chưa thấy archive media Vocabulary riêng. | Giữ luồng cài nhẹ. Tạo backup các object được manifest tham chiếu, thử phục hồi và kiểm checksum. |

- **Cài máy mới:** một lệnh setup-data, chạy Flyway trước importer. Catalog chứa text, ID, asset use, MIME, byte size, SHA-256 và object key. Media nằm trên R2. Maintainer kiểm toàn bộ media khi phát hành. Setup lại cùng release phải giữ nguyên chỉnh sửa, không tải audio và không upload lại.
- **Chuyển release:** kiểm DB mới, DB đã có tiến độ và chỉnh sửa Admin, import bị ngắt, chạy lại, object thiếu và phục hồi vào prefix thử. Đổi link mặc định sau khi đạt các ca này. Giữ gói cũ để rollback. [Owner của gói Drive](../../tools/data-import/DRIVE_HANDOFF.md)
- **Seed dev độc lập với dataset lớn:** hai learner khác quyền, một pack ẩn, một unit đủ sáu dạng bài, hai nghĩa cùng chữ, thẻ đến hạn, lesson có bản publish và draft, session đang làm, grant hết hạn, mission gần đạt. ID ổn định để dùng lại trong test và Swagger.
- **Nguồn bổ sung:** đo số mục dùng được theo dạng bài trước khi lấy thêm data. Ưu tiên ảnh Word Atlas cùng bộ. Khi thiếu ảnh cụ thể, chọn file trên Commons và lưu tác giả, nguồn, giấy phép theo từng ảnh. Ưu tiên câu ví dụ khớp đúng nghĩa và bản ghi khớp câu hơn việc tăng tổng số từ. [Hướng dẫn tái sử dụng ảnh](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia)

## 2. API học tập — thứ tự bàn giao

| Ưu tiên / owner | Đã có | Cần hoàn thiện và nghiệm thu |
|---|---|---|
| 1 · Vocabulary | Admin pack/unit/entry. Learner có add card, due, review. | Browse bộ → unit → từ, progress từng cấp, nghĩa/IPA/audio/ảnh/ví dụ/collocation/family/synonym/antonym. Kiểm quyền từ bộ đến từng file. Sáu dạng gồm chọn nghĩa, chọn từ, sắp chữ, điền chữ, nghe–viết, điền ngữ cảnh. Server chọn câu đủ điều kiện, giữ đáp án, cho resume và chấm một lần khi retry. |
| 2 · Saved Vocabulary + Lexicon | Từ điển đã có search/detail/audio. Card hiện bắt buộc headwordId. | Lưu nghĩa và câu ngữ cảnh từ Lesson, Vocabulary hoặc Lexicon. Liên kết headword khi xác minh được. Tạo/đổi tên/xóa bộ, chọn bộ khi lưu, xem/tìm/gỡ từ theo chủ bộ. Due/history thuộc luồng Học. Migration giữ ID và lịch ôn cũ. Test hai nghĩa của cùng từ, nguồn đổi revision, retry review. Chỉ review trong luồng Học mới tác động lịch SRS. [Anki mô tả thẻ mới, lượt ôn và lịch FSRS](https://docs.ankiweb.net/manual/deck-options) |
| 3 · Lesson + Speech Assessment | Có submit dictation và pilot chấm phát âm riêng của Admin. | Catalog published, activity/sentence, caption/audio cùng revision, resume, attempt history, hoàn thành. Chặn lộ transcript ở chế độ dictation theo chính sách hint. Shadowing cần quota, kết quả thiếu tín hiệu, retention audio và quyền chủ lượt. Test Admin publish giữa phiên và mic im lặng. |
| 4 · Grammar + TOEIC + Assessment | Catalog/detail/start và session chung có get/save answers/submit/history/media. | Kiểm hành trình tìm lại phiên, mất mạng, stale revision, submit trùng, timeout, kết quả từng phần, giải thích sau nộp và truy cập media chéo learner. Dùng raw score tới khi duyệt bảng quy đổi TOEIC. Assessment sở hữu câu hỏi, phiên và điểm. |
| 5 · Curriculum | Có listener completion cho Lesson và TOEIC drill/test. | Path → Section → Item, enrollment, mục bắt buộc/tùy chọn, checkpoint, unlock và học tiếp. Nối completion đúng cấp của Grammar/Vocabulary. Test cùng content trong hai path, learner chưa enroll, event trùng, nội dung bị ẩn. Admin cần tạo hoặc seed được một path hoàn chỉnh. |

## 3. Entitlement — chốt cùng API có giới hạn

- **Điểm xuất phát:** DefaultEntitlementService kiểm feature đang active và grant của user tại thời điểm hiện tại. Giữ feature key ổn định khi đổi tên/gộp gói. Lập ma trận Free/Plus theo hành động thực tế như mở pack, bắt đầu test, chấm Shadowing và lưu thẻ. Giá, hạn mức và thời hạn còn cần duyệt. [Mô hình product → feature → active entitlement](https://docs.stripe.com/billing/entitlements)
- **Ba loại giới hạn:** quyền mở nội dung theo grant, dung lượng hiện tại như số thẻ đang lưu, và usage theo kỳ như lượt chấm. Chốt mốc reset, lúc ghi nhận, xử lý cancel/lỗi provider, cách xử lý dữ liệu đã có khi hạ gói.
- **Contract:** Learner đọc quyền hiện tại, lý do khóa, remaining/resetAt khi có quota. Core kiểm lại ở thao tác tiêu lượt và đường media. Admin cấp/thu hồi có hiệu lực từ–đến và lý do. Audit lưu feature, quyết định, phiên bản policy và operation ID.
- **Nghiệm thu:** còn một lượt, hai thiết bị gửi đồng thời chỉ một thao tác được nhận. Retry cùng operation dùng lại kết quả. Kiểm biên hết hạn, thu hồi giữa phiên và chuyển kỳ. Grant thủ công đủ để debug trước khi chốt thanh toán.

## 4. Nhiệm vụ, thành tích, streak và phần thưởng

| Khái niệm | Quy tắc cần đưa vào master plan |
|---|---|
| Nhiệm vụ | Code có daily/weekly/one-time, kỳ UTC và auto-claim Diamond trong transaction. Listener hiện đếm lesson, vocab review, grammar answer và riêng TOEIC drill. Chốt metric, target, kỳ và phần thưởng. Thêm API danh sách kỳ hiện tại, số đã đạt, trạng thái và thời điểm kết thúc. |
| Thành tích | Mốc lâu dài có definition, điều kiện, tiến độ và unlockedAt. Ví dụ hoàn thành 5 lesson khác nhau, khác với ôn 5 thẻ hôm nay. Seed một mốc đơn và một mốc tích lũy. Khóa duy nhất theo learner + achievement. [Tham khảo achievement và milestone](https://developer.android.com/games/pgs/achievements) |
| Streak | Chốt hoạt động hợp lệ và múi giờ ngày học. Lưu ngày có hoạt động, tính chuỗi hiện tại/dài nhất. Test hai hoạt động cùng ngày, qua nửa đêm, event đến muộn và bỏ một ngày. |
| XP, Level, Diamond | Theo yêu cầu repo, XP dùng nội bộ tính Level, Diamond có ledger bất biến. API trả Level, tiến độ đến cấp sau, số dư và lịch sử. Tách đạt thành tích khỏi phát thưởng để một badge có thể không kèm Diamond. |
| Chống cộng lặp | MissionEventListener truyền metric, thời gian và amount xuống increment, chưa truyền ID của fact ở ranh giới đó. Ledger chống trả thưởng trùng chưa đủ để bảo vệ bộ đếm. Test phát lại cùng event khi mission mới đạt 1/5, hai event khác nhau đến đồng thời, và replay sau khi đạt. |

**Chính sách đề xuất cho MVP.** Hoạt động học phát fact về kết quả. Gamification tự trao XP/Diamond khi đạt nhiệm vụ hoặc mở thành tích có cấu hình thưởng. Streak là chỉ số có thể làm điều kiện mở thành tích. Lưu từ, đăng nhập và sửa hồ sơ không tạo thưởng. Entitlement sở hữu quyền premium và quota. Badge có thể chỉ mang giá trị ghi nhận.

- Một nhiệm vụ có một lần trao mỗi learner/kỳ. Một thành tích có một lần trao mỗi learner trong suốt vòng đời. Một buổi học có thể hoàn thành cả hai. Nếu cả hai có thưởng, lịch sử hiển thị hai nguồn rõ ràng. Đề xuất MVP dùng XP cho nhiệm vụ thường xuyên, dành Diamond cho mốc ít gặp.
- Dùng giao dịch tự nhận hiện có. Ghi định danh lần đạt, loại và lượng tài nguyên, phiên bản rule tại lúc đạt. Replay hoặc đổi định mức sau này phải giữ kết quả lần trao cũ. Điều chỉnh Admin là bút toán riêng có lý do.
- Cutover cần xử lý đường ghi Diamond trực tiếp ở LessonRewardListener. Chuyển consumer này sang chính sách mới tại một mốc hiệu lực, giữ nguyên lịch sử và số dư cũ. Seed và test cả sự kiện cũ đến muộn, học lại sau cutover và một buổi đạt hai mục tiêu. Trước khi triển khai, duyệt thay đổi so với FR-GAM-002 hiện hành. [Listener hiện tại](../../modules/gamification/src/main/java/com/lyreo/gamification/application/LessonRewardListener.java)

Chốt chính sách đạt bài trước khi nối thưởng. RewardPolicy hiện có mốc 60 cho một số activity. Ngưỡng hoàn thành dictation vẫn là quyết định mở. [Curriculum và Gamification](../requirements/curriculum-gamification.md) · [OQ-002, OQ-004, OQ-007](../requirements/open-questions.md)

## 5. Hoàn tất bàn giao Mobile

- **Auth đã chốt:** Keycloak, OIDC Authorization Code + PKCE, Core map JWT subject sang app user khi truy cập lần đầu. Kiểm bằng client Mobile thật: login → khởi động lại → refresh → logout → đổi tài khoản. Mất mạng khi refresh phải cho retry, token bị thu hồi phải yêu cầu đăng nhập. Hai request hết hạn đồng thời dùng chung một lần refresh. Đối chiếu test có sẵn trước khi thêm test. [Session manager tests](../../apps/mobile/src/tests/session-manager.test.ts)
- **Profile đã chốt:** /me, onboarding ba bước, hồ sơ tự sửa, avatar riêng tư và preferences đồng bộ qua thiết bị. Test onboarding gửi lại, đổi tên vẫn giữ progress, token Learner không gọi được Admin, đổi tài khoản không đọc được avatar hoặc cache của chủ cũ. Profile screen có thể đọc Level/streak từ Gamification và quyền hiện tại từ Entitlement. API sửa hồ sơ chỉ nhận trường do learner quản lý.
- **Quyết định còn liên quan tới profile:** mission hiện tính kỳ UTC. Nếu sản phẩm chọn ngày theo nơi người học sinh sống, cần một timezone IANA có hiệu lực từ kỳ tiếp theo và kiểm biên ngày. Nếu giữ UTC, Mobile hiển thị thời điểm reset theo giờ máy. [Contract Identity/Learner](../requirements/identity-learner.md)
- **Analytics:** màn hình hôm nay cần mục học tiếp, thẻ đến hạn, kết quả gần đây, streak và điểm yếu có dữ liệu hỗ trợ. Projection đọc completion/review facts. Với learner mới, trả trạng thái chưa đủ dữ liệu. **Notification/Chat:** kiểm quyền xem job, reconnect/poll khi rớt mạng, ownership cuộc hội thoại và quota tutor nếu nằm trong gói. [Yêu cầu ba module](../requirements/analytics-notification-chat.md)
- **Bộ bàn giao:** OpenAPI thực chạy, bảng màn hình → operation → owner, seed ID, ví dụ response thành công/rỗng/bị khóa/xung đột revision và bộ integration test bằng hai tài khoản. Mỗi hành động ghi phải có quy tắc retry. Kiểm DTO trước/sau nộp, phân trang ổn định, media và quyền ở backend.
- **Điều kiện giao việc:** từ DB mới, cài catalog một lần rồi chạy được luồng học → mất mạng → resume → submit → mở khóa mục kế tiếp → nhận thưởng đúng một lần. Mỗi module ghi kết quả test riêng. Chỉ thêm migration khi schema cần đổi. Chạy make check trước khi chốt contract.

**Quyết định chủ dự án cần chốt:** ngưỡng hoàn thành Lesson, chính sách hiển thị đáp án, điều kiện cập nhật SRS, quota từng gói, múi giờ streak/mission, metric thành tích và nguồn quy đổi điểm TOEIC. Các con số trong seed chỉ phục vụ debug.

## 6. Các quyết định kỹ thuật khi code

| Bằng chứng trong repo | Quyết định và test bắt lỗi |
|---|---|
| `prepare_rebalanced_upgrade` xóa entry/unit cũ, lưu hai cờ hidden và needsReview. Tên unit và nội dung sửa tay có thể mất. | Trước khi tạo FK tiến độ, lập mapping identity ổn định theo nguồn và nghĩa. So bản nguồn cũ, bản Admin và bản nguồn mới. Giữ sửa tay khi chỉ một phía đổi, xuất danh sách xung đột khi hai phía cùng đổi. Seed một mục đổi unit, một mục bị bỏ và một nghĩa Admin đã sửa. Lịch sử phiên cũ phải đọc lại được. [Importer](../../tools/data-import/import_clean_vocabulary.py) |
| `VocabularyCommandService.review` nhận card + rating, ghi lịch rồi phát event có headword và thời gian. Hai lượt ôn thật có thể cùng từ, cùng rating. | Sinh review ID bền vững, lưu request và schedule trong một transaction. Khóa theo learner + thao tác + operation ID. Retry cùng payload trả kết quả cũ, đổi payload cùng khóa trả conflict. Hai thao tác thật trên cùng revision chỉ một thao tác được đổi lịch. Dùng review ID cho consumer. [Vocabulary service](../../modules/vocabulary/src/main/java/com/lyreo/vocabulary/application/VocabularyCommandService.java) · [Mẫu hợp đồng idempotency](https://docs.stripe.com/api/idempotent_requests) |
| Assessment đã lưu snapshot, revision và trả lại view khi session đã submit. Grammar phát từng answer với ID từ session/item, TOEIC phát completion ở cấp attempt. | Giữ contract hiện hành và test submit song song. Thêm fact cấp hoàn thành phiên Grammar khi Curriculum cần mở khóa cả bài. Với Vocabulary, tái sử dụng vòng đời phiên và quy tắc lỗi khi phù hợp. Phần chấm chọn đáp án của Assessment hiện tại cần so với yêu cầu chấm text/audio trước khi dùng chung. [AssessmentSessions](../../modules/assessment/src/main/java/com/lyreo/assessment/api/AssessmentSessions.java) |
| Curriculum truy vấn mọi item cùng content reference rồi upsert progress. Bản SQL này không lọc enrollment. | Đề xuất chỉ tính cho enrollment đang hoạt động khi fact xảy ra. Nếu cho công nhận bài đã học trước khi enroll, thực hiện một lần khi enroll. Test hai path dùng cùng lesson nhưng learner chỉ đăng ký một path. [Repository](../../modules/curriculum/src/main/java/com/lyreo/curriculum/infrastructure/persistence/JdbcCurriculumProgressRepository.java) |
| Consumer nhiệm vụ cập nhật bộ đếm trước khi auto-claim. Event có thể được giao lại sau sự cố. | Ghi receipt duy nhất (consumer, fact ID) cùng transaction với progress và ledger. Consumer mới có namespace riêng. Test lỗi sau update trước commit, replay sau commit, event đến sai thứ tự. Spring Modulith cung cấp publication registry để theo dõi delivery. [Spring Modulith events](https://docs.spring.io/spring-modulith/reference/events.html) |
| Hạn mức có thể bị tranh bởi hai request độc lập. | Dùng update có điều kiện trên cùng hàng usage hoặc khóa hàng trong transaction. Với Azure, reserve một lượt khi nhận job, settle khi có kết quả, release theo policy khi lỗi. Retry job dùng reservation cũ. Không giữ transaction mở lúc gọi provider. [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html) |

### Hợp đồng tối thiểu cho Mobile

Các tên dưới đây là thao tác đề xuất. Đường dẫn đã public tiếp tục theo OpenAPI của Core.

- **Catalog và media:** trả ID, revision, availability, progress và cursor/page theo convention hiện có. Dùng asset reference gắn với content được phép đọc. API chi tiết mới trả danh sách câu/ví dụ/media. Cache theo account và revision. Xóa cache riêng tư khi logout.
- **Phiên luyện:** start trả session ID, revision, tập câu đã chọn và thời hạn nếu có. Save gửi revision + answer. Resume trả trạng thái server. Submit trả kết quả bền vững. Khi mất response, tra session hoặc retry cùng operation. Khi revision xung đột, tải lại trước khi áp dụng đáp án đang chờ. [Android offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first)
- **Quy tắc chấm Vocabulary:** trắc nghiệm loại phương án trùng nghĩa và trường hợp nhiều đáp án đúng. Sắp chữ giữ đúng số lần xuất hiện từng chữ. Điền chữ có ít nhất một vị trí khuyết. Nghe–viết phải khớp bản ghi với từ đích. Cloze khóa span và dạng biến hình trong câu gốc. Đề xuất chuẩn hóa Unicode, khoảng trắng và hoa/thường cho câu trả lời chữ. Đáp án thay thế phải đến từ nội dung đã xác minh. Thiếu dữ liệu thì báo số câu khả dụng trước khi bắt đầu.
- **Lý do để UI hành động:** dùng Problem Details của repo. Phân biệt cần đăng nhập, thiếu quyền, hết quota theo kỳ, stale revision và phiên hết hạn bằng mã máy đọc được. UI có thể hiện đăng nhập, thời điểm reset, tải lại phiên hoặc bắt đầu lượt mới. GET progress ghi rõ phạm vi và mẫu số để Mobile không tự suy ra tỷ lệ từ số trang đã tải.
- **Dữ liệu vận hành:** đếm request replay, conflict, reservation treo, job lỗi, event tồn đọng và độ trễ projection. Dùng correlation ID để lần từ HTTP đến job/fact. Tránh ghi audio, token, câu trả lời cá nhân vào log. Đo p95 catalog/detail bằng dataset thật trước khi chọn index hoặc đặt SLO.
- **Phát hành:** kiểm Testcontainers với hai transaction độc lập, E2E với token Learner thật, rồi thử nâng cấp một bản sao DB có chỉnh sửa. Nếu cutover lỗi, giữ release cũ và khôi phục theo manifest đã kiểm chứng. Bàn giao bằng request/response đã chạy, kèm phiên bản backend và seed tương ứng.

### Những việc có thể triển khai trước khi chốt chính sách

Catalog chỉ đọc, media có kiểm quyền, seed, mapping release, snapshot phiên, retry và receipt có thể bắt đầu ngay. Với dictation, lưu riêng attempt score và trạng thái completion để chủ dự án chốt ngưỡng sau. Với TOEIC, trả raw counts trong lúc chờ nguồn quy đổi. Với gamification, dùng bộ rule dev đánh dấu rõ phạm vi seed. Mở gate phát thưởng và áp quota thật sau khi duyệt ma trận feature, metric, kỳ và điều kiện nhận. Các lựa chọn sản phẩm chưa duyệt tiếp tục theo [open questions](../requirements/open-questions.md).

## 7. Bộ cá nhân, tài nguyên và fixture bàn giao

**Trải nghiệm.** Trong Lesson, Lexicon hoặc unit, nhấn Lưu → chọn nghĩa và bộ đích (có thể tạo bộ ngay) → mở “Bộ của tôi” để tìm lại. Màn hình bộ gồm tên, số mục, tìm từ, chuyển/gỡ mục và quản lý bộ. Sáu dạng luyện nằm ở mục Học. Việc tổ chức bộ không phát completion, review, XP hay mission event.

**API và dữ liệu đề xuất.** CRUD bộ, phân trang mục trong bộ, lưu theo nguồn/nghĩa, gỡ liên kết, chuyển bộ trong một transaction. Lấy owner từ token ở mọi thao tác. Nếu duyệt nhiều bộ cho cùng mục, dùng quan hệ nhiều–nhiều với khóa duy nhất (collection_id, saved_item_id). Hai nghĩa của một chữ có thể cùng xuất hiện trong một bộ. Sửa hoặc ẩn nguồn vẫn đọc được snapshot đã lưu, còn quyền phát media được kiểm tại thời điểm đọc. Hành vi xóa và bộ mặc định cần chốt ở [OQ-008](../requirements/open-questions.md#oq-008).

**Ảnh, data và icon khi thiếu.**

| Loại | Cách tìm hoặc tạo | Tiêu chí đưa vào dùng |
|---|---|---|
| Ảnh từ và bìa | Báo cáo mục thiếu theo pack/sense → tìm Word Atlas hoặc Commons theo nghĩa và từ loại → nếu thiếu, sinh minh họa từ mô tả nghĩa + câu ví dụ. Ưu tiên bìa màu/icon có sẵn cho bộ tự tạo. | Duyệt ảnh cạnh nghĩa để tránh minh họa sai từ đa nghĩa. Chuẩn hóa kích thước, WebP, loại metadata riêng tư, ghi nguồn/giấy phép hoặc model + prompt, checksum và phiên bản. Dùng asset pipeline R2/Drive ở mục 1. |
| Ví dụ và audio | Lọc unit thiếu câu có đáp án duy nhất hoặc audio khớp từ. Ưu tiên tư liệu có quyền dùng, viết hoặc sinh phần thiếu theo sense. | Kiểm trùng sau chuẩn hóa, đối chiếu EN–VI, nghe đầu/cuối file và kiểm span cloze. Nội dung qua duyệt rồi mới tính vào số câu khả dụng. |
| Icon chức năng và chim Lyreo | Dùng bộ icon hiện có cho lưu, bộ sưu tập, chuyển, xóa. Repo có avatar toucan/penguin ở Admin. Nếu chọn chim làm nhận diện, vẽ/sinh mẫu chim nguyên bản, duyệt một silhouette, lưu master cùng nguồn và quyền dùng. | Mobile xuất launcher iOS và lớp foreground/background Android từ master, kiểm mask và độ rõ ở kích thước nhỏ trên máy thật. Dùng text/accessibility label cho icon thao tác. Asset thương hiệu đóng gói với app, độc lập với import DB. |

**Migration và seed đề xuất cho phase này.**

1. Flyway thêm bảng bộ/membership sau khi chốt cardinality. Backfill card cũ theo owner vào bộ mặc định nếu duyệt. Đối soát số card, số membership, cùng ID, ngày đến hạn và history trước/sau. Chạy seed lại hai lần phải cho cùng kết quả.
2. Seed dev với 3 learner (mới, có dữ liệu cũ, tài khoản khác quyền), bộ rỗng, bộ trùng tên giữa hai owner, một bộ 35 mục để qua biên trang 20. Có ba nguồn, hai nghĩa của cùng chữ, mục thiếu ảnh/audio và nguồn đổi revision.
3. Test lưu hai lần do mất mạng, lưu đồng thời từ hai máy, chuyển bị lỗi giữa chừng, xóa một bộ có từ xuất hiện ở bộ khác, đọc/sửa bộ bằng token khác. Với mỗi ca, so cả membership, history và reward trước/sau. Dùng fixture nhỏ trong integration test và bộ seed nhiều mục cho tay QA.

**Câu hỏi cần chủ dự án chốt.** (1) Có bộ “Đã lưu” để lưu nhanh? (2) Một nghĩa có thể thuộc nhiều bộ? (3) Khi xóa bộ cuối chứa từ, chuyển từ sang “Đã lưu” hay gỡ khỏi thư viện? (4) Bìa bộ cá nhân dùng màu/icon dựng sẵn hay cần ảnh tải lên? (5) Chim Lyreo là launcher icon hay chỉ dùng cho avatar/mascot? Đề xuất cho bản đầu: có bộ mặc định, cho nhiều bộ, bảo toàn từ khi xóa bộ, bìa dựng sẵn. Nhận diện chim có thể chốt độc lập với API.


## 8. Tài liệu tham chiếu API & dữ liệu mẫu cho Mobile

Mọi route chi tiết và hợp đồng lỗi được tổng hợp tại [Core HTTP API contract](../architecture/http-api-contract.md) và OpenAPI runtime tại `/v3/api-docs`.

### Bảng màn hình → Thao tác → Route → Module sở hữu → Quy tắc retry

| Màn hình / Luồng Mobile | Thao tác chính | Endpoint HTTP | Module | Quy tắc Retry / Idempotency |
|---|---|---|---|---|
| Profile & Entitlements | Đọc quyền & hạn mức | `GET /api/v1/learner/entitlements` | `entitlement` | Read-only an toàn để tải lại |
| Vocabulary Browser | Duyệt bộ, unit, mục & stream media | `GET /api/v1/vocabulary/packs`, `/packs/{packId}/units`, `/units/{unitId}`, `/entries/{entryId}/media/{assetId}` | `vocabulary` | Read-only; kiểm entitlement `FEATURE` ở server (`403` nếu thiếu) |
| Vocabulary 6-Mode Session | Bắt đầu, lưu đáp án, nộp bài | `POST /api/v1/vocabulary/units/{unitId}/sessions`, `PUT /api/v1/vocabulary/sessions/{id}/answers`, `POST /api/v1/vocabulary/sessions/{id}/submit` | `vocabulary` | Dùng header `Idempotency-Key` khi start/submit; gửi `revision` khi save/submit; nếu `409 STALE_REVISION` thì tải lại `GET /sessions/{id}` |
| Saved Vocabulary & Collections | Lưu nghĩa/ngữ cảnh, tạo/sửa/xóa bộ, chuyển từ, ôn SRS | `POST /api/v1/vocabulary/saved-items`, `GET/POST /api/v1/vocabulary/collections`, `POST /api/v1/vocabulary/collections/{id}/move`, `POST /api/v1/vocabulary/cards/{cardId}/review` | `vocabulary` | Dùng `Idempotency-Key` (và `expectedReviewCount` khi review); nếu `409 IDEMPOTENCY_CONFLICT` không tự retry bằng key cũ |
| Lesson Practice & Dictation | Tải catalog published, ẩn/hiện transcript theo hint, nộp dictation, hoàn tất WATCH | `GET /api/v1/lessons`, `GET /api/v1/lessons/{id}/activities/{activityId}`, `POST .../dictation/attempts`, `POST .../complete` | `lesson` | Dùng `Idempotency-Key` khi nộp attempt / complete `WATCH` |
| Lesson Shadowing | Kiểm quota, gửi audio, poll kết quả | `GET .../shadowing/sentences/{sentenceId}/constraints`, `POST .../attempts`, `GET /api/v1/shadowing-attempts/{id}` | `speech-assessment` | Bắt buộc header `Idempotency-Key` (UUID) và `revision`; retry cùng UUID trả lại lượt đã xếp hàng; `403 QUOTA_EXCEEDED` hiện `resetAt` |
| Grammar & TOEIC | Duyệt danh mục, chia Part TOEIC, mở/tìm lại phiên làm bài | `GET /api/v1/grammar/catalogs/{kind}/page`, `GET /api/v1/toeic/catalog/{id}`, `POST .../sessions`, `GET /api/v1/assessment-sessions` | `grammar`, `toeic`, `assessment` | Dùng `Idempotency-Key` khi start; `PUT /answers` + `POST /submit` mang `revision`; `409 SESSION_EXPIRED` mở phiên mới |
| Curriculum | Duyệt lộ trình, đăng ký học, tiếp tục học | `POST /api/v1/curriculum/paths/{id}/enroll`, `GET /api/v1/curriculum/continue` | `curriculum` | Replay completion event không cộng trùng tiến độ; chỉ lộ trình `ACTIVE` mới mở khóa |
| Missions, Streak, Achievements & Diamond | Xem tóm tắt, nhiệm vụ theo kỳ UTC, huy hiệu, lịch sử | `GET /api/v1/gamification/summary`, `/missions`, `/achievements`, `/ledger` | `gamification` | Read-only; mốc `resetAt`/`endsAt` tính theo UTC |
| Today Overview & Tutor Chat | Xem tổng quan học tập, hỏi gia sư AI | `GET /api/v1/analytics/overview`, `POST /api/v1/chat/ask`, `GET /api/v1/chat/conversations` | `analytics`, `chat` | Learner mới nhận `hasHistory: false`; Chat dùng `Idempotency-Key` tránh trừ quota hai lần |

### Dữ liệu mẫu cố định (`make seed` — [demo.sql](../../infra/postgres/seed/demo.sql))

| Tài khoản / Thực thể | Giá trị / UUID | Ghi chú trạng thái |
|---|---|---|
| Plus Learner (`demo-learner-minh-anh` / `Password123!`) | `30000000-0000-0000-0000-000000000001` | Đã onboarding; có đủ 6 grant `ACTIVE`; đã lưu 35 thẻ (`Đã lưu`, `TOEIC Cốt lõi`); `DAILY_GRAMMAR_5` ở mức `4/5`; streak `2` ngày; đã mở `FIRST_LESSON`; enrolled `FOUNDATION_PATH` |
| Free Learner (`demo-learner-gia-huy` / `Password123!`) | `30000000-0000-0000-0000-000000000002` | Đã onboarding; có grant `toeic.full-access` **đã hết hạn** (`now() - 1 day`); sở hữu bộ rỗng và bộ trùng tên `TOEIC Cốt lõi` |
| New Learner (`demo-learner-new` / `Password123!`) | `30000000-0000-0000-0000-000000000003` | Chưa onboarding (`onboarding_completed_at IS NULL`); `analytics/overview` trả `hasHistory: false` |
| Admin (`admin` / `Password123!`) | Keycloak Admin | Truy cập toàn bộ Admin Web (`localhost:5173`) & `/api/v1/admin/**` |
| Vocabulary Packs / Units | Public Pack `50000000-0000-0000-0000-000000000211` (Unit `...0221`, gồm từ `bank` đa nghĩa `...0237` và từ thiếu media `...0238`); Plus Pack `...0212`; Hidden Pack `...0213` | `VOCABULARY` release `demo-1.0.0` |
| Lessons | Published `50000000-0000-0000-0000-000000000401` (`WATCH` `...0411`, `DICTATION` `...0412`, `SHADOWING` `...0413`, sentences `...0421`/`...0422`); Draft `...0402` | Bài published kèm audio WAV cố định |
| Grammar & TOEIC | Topic `60000000-0000-0000-0000-000000000101` (Public); Bank `...0121` (`grammar.advanced`); TOEIC 200 câu `60000000-0000-0000-0000-000000000201`; Phiên `OPEN` `...0501` | Có đủ câu từ Part 1 đến Part 7 |
| Curriculum Path | `70000000-0000-0000-0000-000000000601`..`0605` (`REAL_WORLD_ENGLISH_A2_B1`, `WORKPLACE_FLUENCY_B1_B2`, `TOEIC_500_FOUNDATION`, `TOEIC_800_MASTERY`, `TOEIC_A1_STARTER`) | Đủ `DRAFT`/`PUBLISHED` với `LESSON`, `VOCABULARY_REVIEW`, `GRAMMAR_PRACTICE`, `TOEIC_TEST` |
| Học viên mở rộng & Media mẫu | `30000000-...-0004` (`Trần Thảo Vy`) .. `0008` (`Vũ Mai Phương`); ảnh WebP & audio WAV tổng hợp (`0103..0112`) | Gồm 3 bài audio đã xuất bản (`0403..0405`), 2 gói từ vựng (`0214..0215`), mission/achievement và analytics 7 ngày |

### Bảng điều khiển Admin Web (`apps/admin-web`) & Gói dữ liệu Drive

- **Admin Web Console (`:5173`)**: Hoàn thiện toàn bộ trang vận hành gồm `Overview` (`/`), `Activities` (`/activities`), `Curriculum` (`/curriculum`, kèm chọn nội dung và xóa lộ trình), `Learner Progress` (`/learners/progress` + chi tiết học tập từng học viên), `Rewards` (`/rewards` với tab Nhiệm vụ, Thành tích, Chính sách thưởng, Hạn mức FREE/PLUS, Nhật ký kiểm toán), `Notifications` (`/notifications` kèm trạng thái SSE), `Conversations` (`/conversations` chỉ thống kê siêu dữ liệu bảo vệ riêng tư), và `Media Library` (`/media`).
- **Kho lưu trữ Google Drive (`1XrAmgAaSDtar8UAeKT9URq3KbRGN2UH4`)**: Đã lưu đầy đủ 3 bản gốc rollback-safe cùng 4 gói mới (`grammar-toeic-catalog-2.0.12.tar.gz`, `grammar-toeic-media-2.0.12.tar.gz`, `vocabulary-media-1.3.0.tar.gz`, `release-checksums-manifest.json`); chi tiết link & SHA-256 xem tại [DRIVE_HANDOFF.md](../../tools/data-import/DRIVE_HANDOFF.md).

### Ví dụ Response mẫu (Success, Empty State & Error Contract)

- **Learner mới chưa có lịch sử (`GET /api/v1/analytics/overview`)**:

```json
{"hasHistory":false,"today":{"fromDate":"2026-11-26","toDate":"2026-11-26","activeDays":0,"studySeconds":0,"lessonCount":0,"vocabularyReviewCount":0,"grammarAnswerCount":0,"toeicQuestionCount":0},"week":{"fromDate":"2026-11-20","toDate":"2026-11-26","activeDays":0,"studySeconds":0,"lessonCount":0,"vocabularyReviewCount":0,"grammarAnswerCount":0,"toeicQuestionCount":0},"skills":[],"weaknesses":[]}
```

- **Thiếu quyền gói Plus (`403 FEATURE_ENTITLEMENT_REQUIRED`)**:

```json
{"type":"urn:lyreo:problem:access-denied","title":"Access denied","status":403,"detail":"Access is denied to this resource.","instance":"/api/v1/vocabulary/packs/50000000-0000-0000-0000-000000000212/units","code":"FEATURE_ENTITLEMENT_REQUIRED","featureKey":"vocabulary.plus-packs"}
```

- **Hết hạn mức theo ngày (`403 QUOTA_EXCEEDED`)**:

```json
{"type":"urn:lyreo:problem:access-denied","title":"Quota exceeded","status":403,"detail":"Usage quota or capacity limit exceeded for this feature.","instance":"/api/v1/lessons/.../attempts","code":"QUOTA_EXCEEDED","featureKey":"speech.shadowing","limitType":"PERIOD_USAGE","limit":5,"remaining":0,"resetAt":"2026-11-26T00:00:00Z"}
```

- **Xung đột revision (`409 STALE_REVISION`) & Hết hạn phiên (`409 SESSION_EXPIRED`)**:

```json
{"type":"urn:lyreo:problem:stale-revision","title":"Stale revision","status":409,"detail":"Session revision mismatch.","code":"STALE_REVISION","currentRevision":3}
```

### Danh mục quyết định sản phẩm cần chủ dự án chốt trước Production

- [OQ-002](../requirements/open-questions.md): Ngưỡng điểm hoàn thành Dictation (dev mặc định `70`, tách riêng `serverScore` và trạng thái hoàn thành).
- [OQ-004](../requirements/open-questions.md): Bảng quy đổi điểm TOEIC (hiện trả raw count, giữ `listeningScaledScore` và `readingScaledScore` là `null`).
- [OQ-007](../requirements/open-questions.md): Chính sách phần thưởng cutover & bảng định mức XP/Diamond/hạn mức gói Free vs Plus chính thức (dev dùng rule version `dev-v1`).
- [OQ-008](../requirements/open-questions.md#oq-008): Xác nhận chính thức bộ mặc định `"Đã lưu"`, quan hệ nhiều–nhiều và bảo toàn thẻ khi xóa bộ tự tạo.
