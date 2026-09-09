# Phân tích yêu cầu Lyreo

Mục đích: actors, thuật ngữ, system boundary và cách phân rã yêu cầu. Scope thuộc
[PRD](../product/prd.md); requirement theo area nằm cùng thư mục này.

## Actors

| Actor | Quyền và trách nhiệm |
|---|---|
| Learner | học và gửi evidence; xem dữ liệu của mình; không quyết định score/reward |
| Admin / Content Manager | quản content/build/runtime policy/provider; không nhận plaintext secret từ server |
| System Worker | claim job, heartbeat, kiểm tra cancel, thực thi step và persist có fencing |
| AI Service | xác thực internal call và thực thi capability; không sở hữu product workflow |
| Keycloak | identity, OIDC token và role authority |
| Object Storage | lưu media/raw artifact; không là workflow database |

## Thuật ngữ chung

- **Content**: source/transcript/sentence/segment/media/timestamp chuẩn hóa.
- **Annotation**: hỗ trợ gắn vào content như translation, lexical/grammar note, IPA, thought group.
- **Activity**: vòng học có attempt/scoring/progress như Dictation, Shadowing, Vocabulary Practice,
  Grammar Practice.
- **Lexicon**: dictionary toàn cục; **Vocabulary**: SRS cá nhân tham chiếu Lexicon.
- **Completion**: fact của owning domain; activity completion khác lesson completion.
- **Projection**: read model từ event; không thay thế detailed progress của producer.
- **Runtime policy**: policy DB/admin áp dụng lúc chạy; khác request admin, lesson snapshot và khả năng adapter.
- **Evidence**: kết quả test/run/quan sát có phạm vi; tên test hoặc code tồn tại chưa đồng nghĩa pass.

## Ranh giới hệ thống

Admin/Mobile giao tiếp Core qua API được version và OIDC. Core là modular monolith sở hữu nghiệp vụ,
PostgreSQL và event contracts. FastAPI là internal AI capability service. R2/local storage đi qua
storage port. Dataset nhập qua tooling riêng; không là source code hay Flyway seed lớn.

## Phân rã requirement

- [Identity/Learner](identity-learner.md)
- [Lesson/Dictation/Shadowing](lesson.md)
- [AI administration/execution](ai.md)
- [Lexicon/Vocabulary](lexicon-vocabulary.md)
- [Grammar/TOEIC](grammar-toeic.md)
- [Curriculum/Gamification](curriculum-gamification.md)
- [Analytics/Notification/Chat](analytics-notification-chat.md)
- [Non-functional](non-functional.md)

Requirement mô tả nghĩa vụ; story/AC mô tả kết quả quan sát; feature spec mô tả flow/state/error;
architecture giải thích solution boundary. Trạng thái implementation chỉ có một owner tại
[traceability](traceability.md).

## Phụ thuộc xuyên module

Completion/review/answer facts đi qua `libs/contracts`, được Curriculum, Gamification và Analytics
consume theo ownership. Thay event phải kiểm tra producer, mọi consumer và idempotency. “Progress”
không phải một domain chung: phải chọn owning module hoặc projection trước khi sửa.
