-- Offline deterministic dev/QA demo seed for Packages A-E.
-- 100% idempotent; safe to run repeatedly via DatabaseSetup --seed-dev.
BEGIN;
SET LOCAL lock_timeout = '10s';

-- 1. Fallback demo learners (no-op if scripts/seed-learners.sh already linked Keycloak subjects)
INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES
    ('30000000-0000-0000-0000-000000000001', 'offline-demo-minh-anh', 'minh-anh@example.test'),
    ('30000000-0000-0000-0000-000000000002', 'offline-demo-gia-huy', 'gia-huy@example.test'),
    ('30000000-0000-0000-0000-000000000003', 'offline-demo-new', 'learner-moi@example.test')
ON CONFLICT DO NOTHING;

INSERT INTO learner_profile (
    learner_id, display_name, goal, current_level, daily_minutes, focus_area, first_seen_at, onboarding_completed_at
)
SELECT v.id, v.display_name, v.goal, v.current_level, v.daily_minutes, v.focus_area, v.first_seen_at, v.onboarding_completed_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, 'Minh Anh', 'CONVERSATION', 'A2', 10, 'LISTENING', now() - interval '2 days', now() - interval '2 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000002'::uuid, 'Gia Huy', 'TOEIC', 'B1', 20, 'VOCABULARY', now() - interval '1 day', now() - interval '1 day' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000003'::uuid, NULL, NULL, NULL, NULL, NULL, now() - interval '30 minutes', NULL)
) AS v(id, display_name, goal, current_level, daily_minutes, focus_area, first_seen_at, onboarding_completed_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.id)
ON CONFLICT (learner_id) DO NOTHING;

-- 2. Entitlement grants: Minh Anh active PLUS; Gia Huy expired toeic.full-access only; New Learner none
INSERT INTO user_entitlement_grant (
    id, user_id, feature_key, status, valid_from, valid_until, source_type, source_reference, created_by
) VALUES
    ('50000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000001', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000012', '30000000-0000-0000-0000-000000000001', 'vocabulary.saved-cards', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000001', 'speech.shadowing', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000014', '30000000-0000-0000-0000-000000000001', 'toeic.full-access', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000015', '30000000-0000-0000-0000-000000000001', 'grammar.advanced', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000016', '30000000-0000-0000-0000-000000000001', 'chat.tutor', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000021', '30000000-0000-0000-0000-000000000002', 'toeic.full-access', 'ACTIVE', now() - interval '30 days', now() - interval '1 day', 'DEV_SEED', 'demo-expired', 'seed-demo')
ON CONFLICT (id) DO NOTHING;

-- 3. Deterministic content-addressed media blobs (matching scripts/seed-demo.sh files)
INSERT INTO media_blob (sha256, mime_type, size_bytes, storage_object_key) VALUES
    ('2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'audio/wav', 3244, 'media/sha256/29/2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd'),
    ('a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'image/png', 79, 'media/sha256/a4/a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f')
ON CONFLICT (sha256) DO NOTHING;

INSERT INTO content_asset (id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by) VALUES
    ('50000000-0000-0000-0000-000000000101', '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'media/sha256/29/2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'audio/wav', 3244, 'demo-audio.wav', true, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000102', 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'media/sha256/a4/a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'image/png', 79, 'demo-image.png', true, 'seed-demo')
ON CONFLICT (id) DO NOTHING;

-- 4. Vocabulary release, packs (PUBLIC / FEATURE / hidden), units, entries, lineage, cards & collections
INSERT INTO dataset_release (id, domain, package_version, schema_version, checksum_sha256, manifest_json, license_text) VALUES (
    '50000000-0000-0000-0000-000000000201',
    'vocabulary',
    'demo-1.0.0',
    '1.3.0',
    '1111111111111111111111111111111111111111111111111111111111111111',
    '{"format":"lyreo.dataset-release","manifest_schema_version":1,"package":{"domain":"vocabulary","version":"demo-1.0.0","schema_version":"1.3.0"}}'::jsonb,
    'CC BY 4.0 Demo Seed'
) ON CONFLICT DO NOTHING;

INSERT INTO release_media_asset (id, release_id, package_asset_id, blob_sha256, package_path, source_paths) VALUES
    ('50000000-0000-0000-0000-000000000203', '50000000-0000-0000-0000-000000000201', '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'media/29/demo-audio.wav', '["demo/audio.wav"]'::jsonb),
    ('50000000-0000-0000-0000-000000000204', '50000000-0000-0000-0000-000000000201', 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'media/a4/demo-image.png', '["demo/image.png"]'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_pack (id, release_id, source_topic, position, title_en, title_vi, hidden, access_mode, required_feature_key) VALUES
    ('50000000-0000-0000-0000-000000000211', '50000000-0000-0000-0000-000000000201', 'demo_daily', 1, 'Everyday Basics', 'Giao tiếp Cơ bản', false, 'PUBLIC', NULL),
    ('50000000-0000-0000-0000-000000000212', '50000000-0000-0000-0000-000000000201', 'demo_plus', 2, 'Business Plus Pack', 'Bộ Từ Vựng Plus', false, 'FEATURE', 'vocabulary.plus-packs'),
    ('50000000-0000-0000-0000-000000000213', '50000000-0000-0000-0000-000000000201', 'demo_hidden', 3, 'Hidden Archive Pack', 'Bộ Từ Vựng Ẩn', true, 'PUBLIC', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_unit (id, pack_id, position, level, title, hidden) VALUES
    ('50000000-0000-0000-0000-000000000221', '50000000-0000-0000-0000-000000000211', 1, 'A2', 'A2 · Demo Core', false),
    ('50000000-0000-0000-0000-000000000222', '50000000-0000-0000-0000-000000000212', 1, 'B1', 'B1 · Plus Strategy', false),
    ('50000000-0000-0000-0000-000000000223', '50000000-0000-0000-0000-000000000213', 1, 'A2', 'A2 · Hidden Unit', true)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_unit_entry (
    id, unit_id, position, source_id, term, content,
    image_id, uk_audio_id, us_audio_id, example_audio_id, second_example_audio_id, hidden
) VALUES
    (
        '50000000-0000-0000-0000-000000000231', '50000000-0000-0000-0000-000000000221', 1, 'demo-schedule', 'schedule',
        '{
          "ipaUk": "/ˈʃedjuːl/",
          "ipaUs": "/ˈskedʒuːl/",
          "senses": [{"pos": "n", "definitionEn": "a plan of things to be done and when they will happen", "meaningVi": "lịch trình, thời gian biểu"}],
          "examples": ["Please check the meeting schedule before noon.", "Our flight schedule changed because of rain."],
          "examplesVi": ["Vui lòng kiểm tra lịch trình cuộc họp trước buổi trưa.", "Lịch bay của chúng tôi đã thay đổi vì trời mưa."],
          "collocations": ["tight schedule", "on schedule"],
          "collocationsVi": ["lịch trình dày đặc", "đúng lịch trình"],
          "synonyms": ["timetable", "plan"],
          "antonyms": ["chaos"],
          "family": ["scheduled", "reschedule"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000232', '50000000-0000-0000-0000-000000000221', 2, 'demo-budget', 'budget',
        '{
          "ipaUk": "/ˈbʌdʒɪt/",
          "ipaUs": "/ˈbʌdʒɪt/",
          "senses": [{"pos": "n", "definitionEn": "the money available for a particular purpose", "meaningVi": "ngân sách"}],
          "examples": ["The team approved the travel budget yesterday.", "We stayed under budget this quarter."],
          "examplesVi": ["Nhóm đã phê duyệt ngân sách công tác ngày hôm qua.", "Chúng tôi đã chi tiêu dưới ngân sách quý này."],
          "collocations": ["annual budget", "under budget"],
          "collocationsVi": ["ngân sách hằng năm", "dưới ngân sách"],
          "synonyms": ["allowance", "funds"],
          "antonyms": ["deficit"],
          "family": ["budgetary"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000233', '50000000-0000-0000-0000-000000000221', 3, 'demo-deliver', 'deliver',
        '{
          "ipaUk": "/dɪˈlɪvə/",
          "ipaUs": "/dɪˈlɪvər/",
          "senses": [{"pos": "v", "definitionEn": "to take goods or letters to the person or place addressed", "meaningVi": "giao hàng, chuyển phát"}],
          "examples": ["The courier will deliver the package at five.", "They deliver fresh bread every morning."],
          "examplesVi": ["Nhân viên giao hàng sẽ giao kiện hàng lúc năm giờ.", "Họ giao bánh mì tươi mỗi sáng."],
          "collocations": ["deliver a package", "deliver on time"],
          "collocationsVi": ["giao một kiện hàng", "giao đúng giờ"],
          "synonyms": ["send", "transport"],
          "antonyms": ["withhold"],
          "family": ["delivery", "deliverable"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000234', '50000000-0000-0000-0000-000000000221', 4, 'demo-confirm', 'confirm',
        '{
          "ipaUk": "/kənˈfɜːm/",
          "ipaUs": "/kənˈfɜːrm/",
          "senses": [{"pos": "v", "definitionEn": "to state or show that something is definitely true or arranged", "meaningVi": "xác nhận"}],
          "examples": ["Please confirm your seat by Friday afternoon.", "The hotel called to confirm our booking."],
          "examplesVi": ["Vui lòng xác nhận chỗ ngồi của bạn trước chiều thứ Sáu.", "Khách sạn đã gọi để xác nhận đặt phòng của chúng tôi."],
          "collocations": ["confirm a booking", "confirm receipt"],
          "collocationsVi": ["xác nhận đặt chỗ", "xác nhận đã nhận"],
          "synonyms": ["verify", "affirm"],
          "antonyms": ["deny"],
          "family": ["confirmation", "confirmed"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000235', '50000000-0000-0000-0000-000000000221', 5, 'demo-airport', 'airport',
        '{
          "ipaUk": "/ˈeəpɔːt/",
          "ipaUs": "/ˈerpɔːrt/",
          "senses": [{"pos": "n", "definitionEn": "a place where planes take off and land", "meaningVi": "sân bay"}],
          "examples": ["We arrived at the airport two hours early.", "A shuttle bus connects the station and airport."],
          "examplesVi": ["Chúng tôi đến sân bay sớm hai tiếng.", "Xe buýt trung chuyển nối nhà ga và sân bay."],
          "collocations": ["international airport", "airport shuttle"],
          "collocationsVi": ["sân bay quốc tế", "xe trung chuyển sân bay"],
          "synonyms": ["airfield"],
          "antonyms": ["seaport"],
          "family": ["airports"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000236', '50000000-0000-0000-0000-000000000221', 6, 'demo-invoice', 'invoice',
        '{
          "ipaUk": "/ˈɪnvɔɪs/",
          "ipaUs": "/ˈɪnvɔɪs/",
          "senses": [{"pos": "n", "definitionEn": "a list of goods sent or services provided with a bill", "meaningVi": "hóa đơn"}],
          "examples": ["Accounting paid the supplier invoice on Monday.", "Each invoice shows the tax amount clearly."],
          "examplesVi": ["Bộ phận kế toán đã thanh toán hóa đơn nhà cung cấp vào thứ Hai.", "Mỗi hóa đơn hiển thị rõ số tiền thuế."],
          "collocations": ["issue an invoice", "pay an invoice"],
          "collocationsVi": ["xuất hóa đơn", "thanh toán hóa đơn"],
          "synonyms": ["bill", "statement"],
          "antonyms": ["refund"],
          "family": ["invoiced"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000237', '50000000-0000-0000-0000-000000000221', 7, 'demo-bank', 'bank',
        '{
          "ipaUk": "/bæŋk/",
          "ipaUs": "/bæŋk/",
          "senses": [
            {"pos": "n", "definitionEn": "an organization where people keep and borrow money", "meaningVi": "ngân hàng"},
            {"pos": "n", "definitionEn": "the sloping land beside a river or stream", "meaningVi": "bờ sông"}
          ],
          "examples": ["I opened a savings account at the bank today.", "We had lunch on the grassy river bank."],
          "examplesVi": ["Hôm nay tôi đã mở tài khoản tiết kiệm tại ngân hàng.", "Chúng tôi đã ăn trưa trên bờ sông đầy cỏ."],
          "collocations": ["bank account", "river bank"],
          "collocationsVi": ["tài khoản ngân hàng", "bờ sông"],
          "synonyms": ["lender", "shore"],
          "antonyms": ["midstream"],
          "family": ["banking", "banker"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000238', '50000000-0000-0000-0000-000000000221', 8, 'demo-notebook', 'notebook',
        '{
          "ipaUk": "/ˈnəʊtbʊk/",
          "ipaUs": "/ˈnoʊtbʊk/",
          "senses": [{"pos": "n", "definitionEn": "a small book for writing notes in", "meaningVi": "sổ tay"}],
          "examples": ["Write new idioms in your study notebook.", "She left her blue notebook on the desk."],
          "examplesVi": ["Hãy ghi các thành ngữ mới vào sổ tay học tập của bạn.", "Cô ấy để quên quyển sổ tay màu xanh trên bàn."],
          "collocations": ["study notebook"],
          "collocationsVi": ["sổ tay học tập"],
          "synonyms": ["journal"],
          "antonyms": [],
          "family": ["notebooks"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        NULL, NULL, NULL, NULL, NULL, false
    ),
    (
        '50000000-0000-0000-0000-000000000239', '50000000-0000-0000-0000-000000000222', 1, 'demo-strategy', 'strategy',
        '{
          "ipaUk": "/ˈstrætədʒi/",
          "ipaUs": "/ˈstrætədʒi/",
          "senses": [{"pos": "n", "definitionEn": "a plan intended to achieve a long-term goal", "meaningVi": "chiến lược"}],
          "examples": ["Our growth strategy focuses on mobile learners.", "Review your exam strategy before Saturday."],
          "examplesVi": ["Chiến lược tăng trưởng của chúng tôi tập trung vào người học trên di động.", "Hãy xem lại chiến lược thi trước thứ Bảy."],
          "collocations": ["business strategy"],
          "collocationsVi": ["chiến lược kinh doanh"],
          "synonyms": ["approach", "plan"],
          "antonyms": ["improvisation"],
          "family": ["strategic"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        '50000000-0000-0000-0000-000000000102', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false
    ),
    (
        '50000000-0000-0000-0000-000000000240', '50000000-0000-0000-0000-000000000223', 1, 'demo-archive', 'archive',
        '{
          "ipaUk": "/ˈɑːkaɪv/",
          "ipaUs": "/ˈɑːrkaɪv/",
          "senses": [{"pos": "n", "definitionEn": "a collection of historical records", "meaningVi": "kho lưu trữ"}],
          "examples": ["Old reports stay in the digital archive.", "Check the archive for earlier versions."],
          "examplesVi": ["Các báo cáo cũ được giữ trong kho lưu trữ số.", "Kiểm tra kho lưu trữ để xem các phiên bản trước."],
          "collocations": ["digital archive"],
          "collocationsVi": ["kho lưu trữ số"],
          "synonyms": ["repository"],
          "antonyms": [],
          "family": ["archival"],
          "contextNotes": [],
          "needsReview": false
        }'::jsonb,
        NULL, NULL, NULL, NULL, NULL, true
    )
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0001-0000-' || lpad(to_hex(p.position), 12, '0'))::uuid,
       'PACK', p.id, p.release_id, p.source_topic,
       jsonb_build_object('source_topic', p.source_topic, 'title_en', p.title_en, 'title_vi', p.title_vi, 'hidden', p.hidden),
       false
FROM vocabulary_pack p
WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0002-0000-' || lpad(to_hex(row_number() OVER (ORDER BY p.position, u.position)), 12, '0'))::uuid,
       'UNIT', u.id, p.release_id, p.source_topic || ':' || u.level || ':' || u.position,
       jsonb_build_object('pack_source_topic', p.source_topic, 'level', u.level, 'title', u.title, 'hidden', u.hidden),
       false
FROM vocabulary_unit u
JOIN vocabulary_pack p ON p.id = u.pack_id
WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0003-0000-' || lpad(to_hex(row_number() OVER (ORDER BY p.position, u.position, e.position)), 12, '0'))::uuid,
       'ENTRY', e.id, p.release_id, e.source_id,
       jsonb_build_object('source_id', e.source_id, 'term', e.term, 'content', e.content, 'hidden', e.hidden),
       false
FROM vocabulary_unit_entry e
JOIN vocabulary_unit u ON u.id = e.unit_id
JOIN vocabulary_pack p ON p.id = u.pack_id
WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO dataset_import_run (id, release_id, run_type, status, records_read, records_inserted, details_json, completed_at) VALUES (
    '50000000-0000-0000-0000-000000000202',
    '50000000-0000-0000-0000-000000000201',
    'APPLY',
    'SUCCEEDED',
    18,
    18,
    '{"source":"seed-demo"}'::jsonb,
    now()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO dataset_active_release (domain, release_id, activated_by)
VALUES ('vocabulary', '50000000-0000-0000-0000-000000000201', 'seed-demo')
ON CONFLICT (domain) DO NOTHING;

-- Personal collections (Minh Anh: default + custom + empty; Gia Huy: default + same-name custom)
INSERT INTO vocabulary_collection (id, learner_id, name, is_default, cover_preset) VALUES
    ('50000000-0000-0000-0000-000000000251', '30000000-0000-0000-0000-000000000001', 'Đã lưu', true, 'BLUE_BOOK'),
    ('50000000-0000-0000-0000-000000000252', '30000000-0000-0000-0000-000000000001', 'TOEIC Cốt lõi', false, 'EMERALD_NOTE'),
    ('50000000-0000-0000-0000-000000000253', '30000000-0000-0000-0000-000000000001', 'Ôn tập cuối tuần', false, 'AMBER_CARD'),
    ('50000000-0000-0000-0000-000000000261', '30000000-0000-0000-0000-000000000002', 'Đã lưu', true, 'BLUE_BOOK'),
    ('50000000-0000-0000-0000-000000000262', '30000000-0000-0000-0000-000000000002', 'TOEIC Cốt lõi', false, 'ROSE_DECK')
ON CONFLICT DO NOTHING;

-- 35 saved vocabulary cards for Minh Anh (pagination > 20, due SRS cards, 2 senses of "bank")
INSERT INTO vocabulary_card (
    id, learner_id, headword_id, source_context_type, source_context_id,
    next_review_at, stability, difficulty, lapse_count, review_count,
    dedup_key, term, part_of_speech, meaning_vi, definition_en, ipa,
    context_sentence, context_translation_vi, source_entry_id, sense_index, source_revision,
    audio_asset_id, image_asset_id, snapshot_json
)
SELECT
    ('50000000-0000-0004-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
    '30000000-0000-0000-0000-000000000001',
    NULL,
    'VOCABULARY_UNIT',
    '50000000-0000-0000-0000-000000000221',
    CASE WHEN n <= 10 THEN now() - (n || ' minutes')::interval ELSE now() + ((n - 10) || ' days')::interval END,
    1.5,
    4.5,
    0,
    CASE WHEN n <= 10 THEN 1 ELSE 0 END,
    CASE
        WHEN n = 1 THEN 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000237:SENSE:0'
        WHEN n = 2 THEN 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000237:SENSE:1'
        ELSE 'DEMO_CARD:' || n::text
    END,
    CASE
        WHEN n IN (1, 2) THEN 'bank'
        WHEN n = 3 THEN 'schedule'
        WHEN n = 4 THEN 'budget'
        WHEN n = 5 THEN 'deliver'
        WHEN n = 6 THEN 'confirm'
        WHEN n = 7 THEN 'airport'
        WHEN n = 8 THEN 'invoice'
        ELSE 'demo-word-' || lpad(n::text, 2, '0')
    END,
    'n',
    CASE
        WHEN n = 1 THEN 'ngân hàng'
        WHEN n = 2 THEN 'bờ sông'
        ELSE 'từ vựng ôn tập số ' || n::text
    END,
    CASE
        WHEN n = 1 THEN 'an organization where people keep and borrow money'
        WHEN n = 2 THEN 'the sloping land beside a river or stream'
        ELSE 'demo review item ' || n::text
    END,
    '/bæŋk/',
    CASE
        WHEN n = 1 THEN 'I opened a savings account at the bank today.'
        WHEN n = 2 THEN 'We had lunch on the grassy river bank.'
        ELSE 'Practice sentence for demo card ' || n::text || '.'
    END,
    CASE
        WHEN n = 1 THEN 'Hôm nay tôi đã mở tài khoản tiết kiệm tại ngân hàng.'
        WHEN n = 2 THEN 'Chúng tôi đã ăn trưa trên bờ sông đầy cỏ.'
        ELSE 'Câu ví dụ ôn tập số ' || n::text || '.'
    END,
    CASE WHEN n IN (1, 2) THEN '50000000-0000-0000-0000-000000000237'::uuid ELSE '50000000-0000-0000-0000-000000000231'::uuid END,
    CASE WHEN n = 2 THEN 1 ELSE 0 END,
    0,
    '50000000-0000-0000-0000-000000000101',
    '50000000-0000-0000-0000-000000000102',
    jsonb_build_object('seed', 'demo', 'index', n)
FROM generate_series(1, 35) AS n
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_collection_item (collection_id, card_id)
SELECT col.id, c.id
FROM vocabulary_card c
JOIN vocabulary_collection col ON col.learner_id = c.learner_id AND col.is_default = true
WHERE c.learner_id = '30000000-0000-0000-0000-000000000001'
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_collection_item (collection_id, card_id)
SELECT '50000000-0000-0000-0000-000000000252'::uuid,
       ('50000000-0000-0004-0000-' || lpad(to_hex(n), 12, '0'))::uuid
FROM generate_series(1, 10) AS n
WHERE EXISTS (
    SELECT 1 FROM vocabulary_card
    WHERE id = ('50000000-0000-0004-0000-' || lpad(to_hex(n), 12, '0'))::uuid
)
ON CONFLICT DO NOTHING;

-- 5. Lesson: 1 PUBLISHED lesson (WATCH, DICTATION, SHADOWING, sentences, timestamps, CONTENT+cloze, audio) + 1 DRAFT
INSERT INTO lesson_category (id, name, translations)
VALUES ('20000000-0000-0000-0000-000000000001', 'Daily conversation', '{"vi":"Giao tiếp hằng ngày"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO lesson (id, title, source_type, source_text, source_reference, canonical_audio_object_key, status, created_at, updated_at) VALUES
    ('50000000-0000-0000-0000-000000000401', 'Demo Published Listening', 'AUDIO', 'Welcome to Lyreo. Let us practice listening every day.', 'demo:published:1', 'media/sha256/29/2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'PUBLISHED', now() - interval '2 days', now() - interval '1 day'),
    ('50000000-0000-0000-0000-000000000402', 'Demo Draft Lesson', 'TEXT', 'This draft lesson is not yet published.', 'demo:draft:2', NULL, 'DRAFT', now() - interval '1 day', now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

UPDATE lesson_metadata
SET description = 'Deterministic published demo lesson with watch, dictation, and shadowing.',
    visible = true,
    updated_at = now() - interval '1 day'
WHERE lesson_id = '50000000-0000-0000-0000-000000000401'
  AND NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL);

INSERT INTO lesson_category_membership (lesson_id, category_id)
SELECT '50000000-0000-0000-0000-000000000401', '20000000-0000-0000-0000-000000000001'
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_sentence (id, lesson_id, position, text, audio_start_ms, audio_end_ms)
SELECT v.id, '50000000-0000-0000-0000-000000000401', v.pos, v.txt, v.start_ms, v.end_ms
FROM (VALUES
    ('50000000-0000-0000-0000-000000000421'::uuid, 0, 'Welcome to Lyreo.', 0::bigint, 1200::bigint),
    ('50000000-0000-0000-0000-000000000422'::uuid, 1, 'Let us practice listening every day.', 1200::bigint, 3000::bigint)
) AS v(id, pos, txt, start_ms, end_ms)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_word_timestamp (id, sentence_id, position, surface_text, start_ms, end_ms)
SELECT v.id, v.sentence_id, v.pos, v.surface, v.start_ms, v.end_ms
FROM (VALUES
    ('50000000-0000-0000-0000-000000000431'::uuid, '50000000-0000-0000-0000-000000000421'::uuid, 0, 'Welcome', 0::bigint, 400::bigint),
    ('50000000-0000-0000-0000-000000000432'::uuid, '50000000-0000-0000-0000-000000000421'::uuid, 1, 'to', 400::bigint, 650::bigint),
    ('50000000-0000-0000-0000-000000000433'::uuid, '50000000-0000-0000-0000-000000000421'::uuid, 2, 'Lyreo.', 650::bigint, 1200::bigint),
    ('50000000-0000-0000-0000-000000000434'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 0, 'Let', 1200::bigint, 1500::bigint),
    ('50000000-0000-0000-0000-000000000435'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 1, 'us', 1500::bigint, 1700::bigint),
    ('50000000-0000-0000-0000-000000000436'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 2, 'practice', 1700::bigint, 2100::bigint),
    ('50000000-0000-0000-0000-000000000437'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 3, 'listening', 2100::bigint, 2500::bigint),
    ('50000000-0000-0000-0000-000000000438'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 4, 'every', 2500::bigint, 2750::bigint),
    ('50000000-0000-0000-0000-000000000439'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 5, 'day.', 2750::bigint, 3000::bigint)
) AS v(id, sentence_id, pos, surface, start_ms, end_ms)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO sentence_pronunciation (id, sentence_id, accent, ipa, provider, model, text_hash)
SELECT v.id, v.sentence_id, 'US', v.ipa, 'LOCAL', 'espeak-ng:1.52.0', encode(sha256(convert_to(v.txt, 'UTF8')), 'hex')
FROM (VALUES
    ('50000000-0000-0000-0000-000000000441'::uuid, '50000000-0000-0000-0000-000000000421'::uuid, 'wˈɛlkʌm tə lˈaɪrioʊ', 'Welcome to Lyreo.'),
    ('50000000-0000-0000-0000-000000000442'::uuid, '50000000-0000-0000-0000-000000000422'::uuid, 'lˈɛt ʌs prˈæktɪs lˈɪsənɪŋ ˈɛvri dˈeɪ', 'Let us practice listening every day.')
) AS v(id, sentence_id, ipa, txt)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_annotation (id, lesson_id, sentence_id, annotation_type, payload_json)
SELECT v.id, '50000000-0000-0000-0000-000000000401', v.sentence_id, 'CONTENT', v.payload
FROM (VALUES
    (
        '50000000-0000-0000-0000-000000000451'::uuid,
        '50000000-0000-0000-0000-000000000421'::uuid,
        '{
          "id": "50000000-0000-0000-0000-000000000421",
          "text": "Welcome to Lyreo.",
          "translation": "Chào mừng đến với Lyreo.",
          "ipa": "wˈɛlkʌm tə lˈaɪrioʊ",
          "ipaAccent": "US",
          "dictationSelected": true,
          "shadowingSelected": true,
          "cloze": {"easy": [0], "medium": [0, 2], "text": "Welcome to Lyreo.", "version": "cloze-v2"},
          "words": [
            {"text": "Welcome", "startMs": 0, "endMs": 400, "charStart": 0, "charEnd": 7, "tokenStart": 0, "tokenEnd": 1},
            {"text": "to", "startMs": 400, "endMs": 650, "charStart": 8, "charEnd": 10, "tokenStart": 1, "tokenEnd": 2},
            {"text": "Lyreo", "startMs": 650, "endMs": 1200, "charStart": 11, "charEnd": 16, "tokenStart": 2, "tokenEnd": 3}
          ]
        }'::jsonb
    ),
    (
        '50000000-0000-0000-0000-000000000452'::uuid,
        '50000000-0000-0000-0000-000000000422'::uuid,
        '{
          "id": "50000000-0000-0000-0000-000000000422",
          "text": "Let us practice listening every day.",
          "translation": "Hãy cùng luyện nghe mỗi ngày.",
          "ipa": "lˈɛt ʌs prˈæktɪs lˈɪsənɪŋ ˈɛvri dˈeɪ",
          "ipaAccent": "US",
          "dictationSelected": true,
          "shadowingSelected": true,
          "cloze": {"easy": [2], "medium": [2, 3], "text": "Let us practice listening every day.", "version": "cloze-v2"},
          "words": [
            {"text": "Let", "startMs": 1200, "endMs": 1500, "charStart": 0, "charEnd": 3, "tokenStart": 0, "tokenEnd": 1},
            {"text": "us", "startMs": 1500, "endMs": 1700, "charStart": 4, "charEnd": 6, "tokenStart": 1, "tokenEnd": 2},
            {"text": "practice", "startMs": 1700, "endMs": 2100, "charStart": 7, "charEnd": 15, "tokenStart": 2, "tokenEnd": 3},
            {"text": "listening", "startMs": 2100, "endMs": 2500, "charStart": 16, "charEnd": 25, "tokenStart": 3, "tokenEnd": 4},
            {"text": "every", "startMs": 2500, "endMs": 2750, "charStart": 26, "charEnd": 31, "tokenStart": 4, "tokenEnd": 5},
            {"text": "day", "startMs": 2750, "endMs": 3000, "charStart": 32, "charEnd": 35, "tokenStart": 5, "tokenEnd": 6}
          ]
        }'::jsonb
    )
) AS v(id, sentence_id, payload)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_activity (id, lesson_id, activity_type, position, enabled, config_json)
SELECT v.id, '50000000-0000-0000-0000-000000000401', v.activity_type, v.pos, true, '{"clozeVersion":"cloze-v2"}'::jsonb
FROM (VALUES
    ('50000000-0000-0000-0000-000000000411'::uuid, 'WATCH', 0),
    ('50000000-0000-0000-0000-000000000412'::uuid, 'DICTATION', 1),
    ('50000000-0000-0000-0000-000000000413'::uuid, 'SHADOWING', 2)
) AS v(id, activity_type, pos)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_activity_sentence (activity_id, sentence_id)
SELECT a.id, s.id
FROM lesson_activity a
JOIN lesson_sentence s ON s.lesson_id = a.lesson_id
WHERE a.lesson_id = '50000000-0000-0000-0000-000000000401'
  AND NOT EXISTS (SELECT 1 FROM lesson_authoring WHERE lesson_id = '50000000-0000-0000-0000-000000000401' AND published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_authoring (
    lesson_id, source_start_ms, source_end_ms, revision, transcript_revision, timing_status, document, published_at
) VALUES (
    '50000000-0000-0000-0000-000000000401',
    0,
    3000,
    1,
    1,
    'VALID',
    '[
      {"id":"50000000-0000-0000-0000-000000000421","text":"Welcome to Lyreo.","usage":"LEARN","coarseStartMs":0,"coarseEndMs":1200,"translation":"Chào mừng đến với Lyreo.","dictationSelected":true,"shadowingSelected":true},
      {"id":"50000000-0000-0000-0000-000000000422","text":"Let us practice listening every day.","usage":"LEARN","coarseStartMs":1200,"coarseEndMs":3000,"translation":"Hãy cùng luyện nghe mỗi ngày.","dictationSelected":true,"shadowingSelected":true}
    ]'::jsonb,
    now() - interval '1 day'
) ON CONFLICT (lesson_id) DO NOTHING;

INSERT INTO lesson_authoring (
    lesson_id, source_start_ms, source_end_ms, revision, transcript_revision, timing_status, document, published_at
) VALUES (
    '50000000-0000-0000-0000-000000000402',
    0,
    NULL,
    1,
    0,
    'MISSING',
    '[]'::jsonb,
    NULL
) ON CONFLICT (lesson_id) DO NOTHING;

-- 6. Grammar & TOEIC release, public topic/subtopic, Plus bank, 200-question TOEIC test & OPEN session
INSERT INTO dataset_release (id, domain, package_version, schema_version, checksum_sha256, manifest_json, license_text) VALUES (
    '60000000-0000-0000-0000-000000000001',
    'grammar-toeic',
    'demo-2.0.12',
    '2.0.12',
    '2222222222222222222222222222222222222222222222222222222222222222',
    '{"format":"lyreo.dataset-release","manifest_schema_version":1,"package":{"domain":"grammar-toeic","version":"demo-2.0.12","schema_version":"2.0.12"}}'::jsonb,
    'Demo Grammar & TOEIC Seed'
) ON CONFLICT DO NOTHING;

INSERT INTO release_media_asset (id, release_id, package_asset_id, blob_sha256, package_path, source_paths) VALUES
    ('60000000-0000-0000-0000-000000000011', '60000000-0000-0000-0000-000000000001', '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd', 'media/29/demo-audio.wav', '["demo/audio.wav"]'::jsonb),
    ('60000000-0000-0000-0000-000000000012', '60000000-0000-0000-0000-000000000001', 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f', 'media/a4/demo-image.png', '["demo/image.png"]'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO grammar_difficulty_level_version (id, release_id, level, package_level_id, name_vi, description_vi)
SELECT ('60000000-0000-0000-0000-00000000003' || lvl::text)::uuid,
       '60000000-0000-0000-0000-000000000001',
       lvl,
       'demo-lvl-' || lvl::text,
       'Mức ' || lvl::text,
       'Độ khó demo ' || lvl::text
FROM generate_series(1, 5) AS lvl
ON CONFLICT DO NOTHING;

INSERT INTO grammar_topic_catalog (id, publication_status, access_mode, required_feature_key, reconciliation_status) VALUES
    ('60000000-0000-0000-0000-000000000101', 'PUBLISHED', 'PUBLIC', NULL, 'CONFIRMED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO grammar_topic_version (
    id, release_id, catalog_id, package_topic_id, title_en, title_vi, description_en, description_vi, icon, order_index, source_access_level, source_is_hidden
) VALUES (
    '60000000-0000-0000-0000-000000000102', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000101', '60000000-0000-0000-0000-000000000103',
    'Verb Tenses', 'Các thì động từ', 'Essential English verb tenses', 'Các thì cơ bản trong tiếng Anh', 'clock', 1, 'free', false
) ON CONFLICT DO NOTHING;

INSERT INTO grammar_subtopic_catalog (id, topic_catalog_id, publication_status, access_mode, required_feature_key, reconciliation_status) VALUES
    ('60000000-0000-0000-0000-000000000111', '60000000-0000-0000-0000-000000000101', 'PUBLISHED', 'PUBLIC', NULL, 'CONFIRMED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO grammar_subtopic_version (
    id, release_id, catalog_id, topic_catalog_id, package_subtopic_id, package_topic_id, topic_version_id,
    title_en, title_vi, description_en, description_vi, difficulty_level, order_index, source_access_level, source_is_hidden
) VALUES (
    '60000000-0000-0000-0000-000000000112', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000111',
    '60000000-0000-0000-0000-000000000101', '60000000-0000-0000-0000-000000000113', '60000000-0000-0000-0000-000000000103',
    '60000000-0000-0000-0000-000000000102', 'Present Simple', 'Thì hiện tại đơn',
    'Habits and general truths', 'Thói quen và sự thật hiển nhiên', 1, 1, 'free', false
) ON CONFLICT DO NOTHING;

INSERT INTO grammar_bank_catalog (id, year, publication_status, access_mode, required_feature_key, reconciliation_status) VALUES
    ('60000000-0000-0000-0000-000000000121', 2026, 'PUBLISHED', 'FEATURE', 'grammar.advanced', 'CONFIRMED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO grammar_bank_version (
    id, release_id, catalog_id, package_bank_id, name, year, order_index, source_access_level, source_counts
) VALUES (
    '60000000-0000-0000-0000-000000000122', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000121',
    '60000000-0000-0000-0000-000000000123', 'Advanced Grammar Bank 2026', 2026, 1, 'premium', '{"questions": 2}'::jsonb
) ON CONFLICT DO NOTHING;

INSERT INTO assessment_item (
    id, release_id, package_item_id, kind, stem_en, transcript_en, options, correct_option, difficulty_level, explanation_preference, annotations, source_domains, provenance
) VALUES
    (
        '60000000-0000-0000-0000-000000000131', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000141',
        'MULTIPLE_CHOICE', 'She ___ to work by train every morning.', NULL,
        '[{"key":"A","text":"go"},{"key":"B","text":"goes"},{"key":"C","text":"going"},{"key":"D","text":"gone"}]'::jsonb,
        'B', 1, 'VI', '{"explanation_vi":"Chủ ngữ số ít She dùng động từ thêm -es ở thì hiện tại đơn.","translation_vi":"Cô ấy đi làm bằng tàu hỏa mỗi sáng."}'::jsonb,
        '["grammar"]'::jsonb, '[]'::jsonb
    ),
    (
        '60000000-0000-0000-0000-000000000132', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000142',
        'MULTIPLE_CHOICE', 'Water ___ at 100 degrees Celsius.', NULL,
        '[{"key":"A","text":"boils"},{"key":"B","text":"boil"},{"key":"C","text":"boiled"},{"key":"D","text":"boiling"}]'::jsonb,
        'A', 1, 'VI', '{"explanation_vi":"Sự thật hiển nhiên dùng thì hiện tại đơn.","translation_vi":"Nước sôi ở 100 độ C."}'::jsonb,
        '["grammar"]'::jsonb, '[]'::jsonb
    ),
    (
        '60000000-0000-0000-0000-000000000133', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000143',
        'MULTIPLE_CHOICE', 'Had the report arrived earlier, we ___ revised the forecast.', NULL,
        '[{"key":"A","text":"would have"},{"key":"B","text":"will have"},{"key":"C","text":"had"},{"key":"D","text":"have"}]'::jsonb,
        'A', 4, 'VI', '{"explanation_vi":"Đảo ngữ câu điều kiện loại 3 dùng would have + V3.","translation_vi":"Nếu báo cáo đến sớm hơn, chúng tôi đã chỉnh sửa dự báo."}'::jsonb,
        '["grammar"]'::jsonb, '[]'::jsonb
    ),
    (
        '60000000-0000-0000-0000-000000000134', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000144',
        'MULTIPLE_CHOICE', 'Not only ___ the deadline, but the team also cut costs.', NULL,
        '[{"key":"A","text":"did they meet"},{"key":"B","text":"they met"},{"key":"C","text":"met they"},{"key":"D","text":"do they met"}]'::jsonb,
        'A', 4, 'VI', '{"explanation_vi":"Cấu trúc đảo ngữ với Not only đứng đầu câu.","translation_vi":"Nhóm không chỉ kịp hạn mà còn cắt giảm chi phí."}'::jsonb,
        '["grammar"]'::jsonb, '[]'::jsonb
    )
ON CONFLICT DO NOTHING;

INSERT INTO grammar_membership (
    id, release_id, package_membership_id, item_id, mode, topic_version_id, subtopic_version_id, bank_version_id, difficulty_level, order_index, source_url
) VALUES
    ('60000000-0000-0000-0000-000000000151', '60000000-0000-0000-0000-000000000001', 'demo-gm-1', '60000000-0000-0000-0000-000000000131', 'topic', '60000000-0000-0000-0000-000000000102', '60000000-0000-0000-0000-000000000112', NULL, 1, 1, 'demo://grammar/1'),
    ('60000000-0000-0000-0000-000000000152', '60000000-0000-0000-0000-000000000001', 'demo-gm-2', '60000000-0000-0000-0000-000000000132', 'topic', '60000000-0000-0000-0000-000000000102', '60000000-0000-0000-0000-000000000112', NULL, 1, 2, 'demo://grammar/2'),
    ('60000000-0000-0000-0000-000000000153', '60000000-0000-0000-0000-000000000001', 'demo-gm-3', '60000000-0000-0000-0000-000000000133', 'bank', NULL, NULL, '60000000-0000-0000-0000-000000000122', 4, 1, 'demo://grammar/3'),
    ('60000000-0000-0000-0000-000000000154', '60000000-0000-0000-0000-000000000001', 'demo-gm-4', '60000000-0000-0000-0000-000000000134', 'bank', NULL, NULL, '60000000-0000-0000-0000-000000000122', 4, 2, 'demo://grammar/4')
ON CONFLICT DO NOTHING;

-- Published 200-question TOEIC test across Parts 1..7 with stimulus groups
INSERT INTO toeic_test_catalog (id, year, test_number, publication_status, access_mode, required_feature_key, reconciliation_status) VALUES
    ('60000000-0000-0000-0000-000000000201', 2026, 99, 'PUBLISHED', 'PUBLIC', NULL, 'CONFIRMED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO toeic_test_version (
    id, release_id, catalog_id, package_test_id, name, year, test_number, set_id, order_index,
    source_label, listening_duration_seconds, reading_duration_seconds, total_questions, difficulty_level,
    source_is_free, source_is_hidden, media_version
) VALUES (
    '60000000-0000-0000-0000-000000000202', '60000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000201',
    '60000000-0000-0000-0000-000000000203', 'Demo TOEIC Practice Test 2026', 2026, 99,
    '60000000-0000-0000-0000-000000000204', 1, 'demo', 2700, 4500, 200, 2, true, false, 1
) ON CONFLICT DO NOTHING;

-- Stimulus groups for Part 3 (13 groups Q32..70), Part 4 (10 groups Q71..100), Part 6 (4 groups Q131..146), Part 7 (18 groups Q147..200)
INSERT INTO toeic_stimulus_group (
    id, release_id, test_version_id, package_group_id, part, kind, title, order_index, difficulty_level,
    question_numbers, transcript_en, content_translation_vi, vocabulary_note_vi, render_html, source_html,
    source_html_sha256, document_parse_status, document_count, source_issues
)
SELECT
    ('60000000-0000-0002-0000-' || lpad(to_hex(g.gid), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000202',
    ('60000000-0000-0003-0000-' || lpad(to_hex(g.gid), 12, '0'))::uuid,
    g.part,
    CASE WHEN g.part IN (3, 4) THEN 'AUDIO' ELSE 'TEXT' END,
    'Part ' || g.part || ' Group ' || g.gid,
    g.gid,
    2,
    g.qnums,
    CASE WHEN g.part IN (3, 4) THEN 'Audio conversation transcript for group ' || g.gid ELSE NULL END,
    'Bản dịch mẫu nhóm ' || g.gid,
    NULL,
    CASE WHEN g.part IN (6, 7) THEN '<p>Reading passage for TOEIC Part ' || g.part || ' group ' || g.gid || '.</p>' ELSE NULL END,
    CASE WHEN g.part IN (6, 7) THEN '<p>Reading passage for TOEIC Part ' || g.part || ' group ' || g.gid || '.</p>' ELSE NULL END,
    CASE WHEN g.part IN (6, 7) THEN encode(sha256(convert_to('<p>Reading passage for TOEIC Part ' || g.part || ' group ' || g.gid || '.</p>', 'UTF8')), 'hex') ELSE NULL END,
    CASE WHEN g.part IN (6, 7) THEN 'SINGLE' ELSE 'NOT_APPLICABLE' END,
    CASE WHEN g.part IN (6, 7) THEN 1 ELSE 0 END,
    '[]'::jsonb
FROM (
    SELECT idx AS gid, 3 AS part, ARRAY[32 + (idx - 1) * 3, 32 + (idx - 1) * 3 + 1, 32 + (idx - 1) * 3 + 2] AS qnums FROM generate_series(1, 13) AS idx
    UNION ALL
    SELECT 13 + idx, 4, ARRAY[71 + (idx - 1) * 3, 71 + (idx - 1) * 3 + 1, 71 + (idx - 1) * 3 + 2] FROM generate_series(1, 10) AS idx
    UNION ALL
    SELECT 23 + idx, 6, ARRAY[131 + (idx - 1) * 4, 131 + (idx - 1) * 4 + 1, 131 + (idx - 1) * 4 + 2, 131 + (idx - 1) * 4 + 3] FROM generate_series(1, 4) AS idx
    UNION ALL
    SELECT 27 + idx, 7, ARRAY[147 + (idx - 1) * 3, 147 + (idx - 1) * 3 + 1, 147 + (idx - 1) * 3 + 2] FROM generate_series(1, 18) AS idx
) AS g
ON CONFLICT DO NOTHING;

INSERT INTO toeic_document (id, release_id, group_id, package_doc_id, ordinal, document_type, html, parse_status)
SELECT
    ('60000000-0000-0004-0000-' || lpad(to_hex(gid), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    ('60000000-0000-0002-0000-' || lpad(to_hex(gid), 12, '0'))::uuid,
    'demo-doc-' || gid::text,
    1,
    'PASSAGE',
    '<p>Reading passage for TOEIC group ' || gid::text || '.</p>',
    'SINGLE'
FROM generate_series(24, 45) AS gid
ON CONFLICT DO NOTHING;

INSERT INTO assessment_item (
    id, release_id, package_item_id, kind, stem_en, transcript_en, options, correct_option, difficulty_level, explanation_preference, annotations, source_domains, provenance
)
SELECT
    ('60000000-0000-0001-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    ('60000000-0000-0005-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    'MULTIPLE_CHOICE',
    CASE
        WHEN q <= 6 THEN NULL
        WHEN q BETWEEN 7 AND 31 THEN 'Audio prompt question ' || q::text
        ELSE 'Demo TOEIC question ' || q::text || ': choose the best answer.'
    END,
    CASE WHEN q <= 31 THEN 'Audio transcript for question ' || q::text ELSE NULL END,
    CASE
        WHEN q BETWEEN 7 AND 31 THEN '[{"key":"A","text":"Option A"},{"key":"B","text":"Option B"},{"key":"C","text":"Option C"}]'::jsonb
        ELSE '[{"key":"A","text":"Option A"},{"key":"B","text":"Option B"},{"key":"C","text":"Option C"},{"key":"D","text":"Option D"}]'::jsonb
    END,
    'A',
    2,
    'VI',
    jsonb_build_object('explanation_vi', 'Giải thích mẫu câu ' || q::text, 'translation_vi', 'Bản dịch câu ' || q::text),
    '["toeic"]'::jsonb,
    '[]'::jsonb
FROM generate_series(1, 200) AS q
ON CONFLICT DO NOTHING;

INSERT INTO toeic_placement (
    id, release_id, package_placement_id, test_version_id, item_id, group_id, section, part, question_number, gap_number, order_index
)
SELECT
    ('60000000-0000-0006-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    ('60000000-0000-0007-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000202',
    ('60000000-0000-0001-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    CASE
        WHEN q BETWEEN 32 AND 70 THEN ('60000000-0000-0002-0000-' || lpad(to_hex(1 + (q - 32) / 3), 12, '0'))::uuid
        WHEN q BETWEEN 71 AND 100 THEN ('60000000-0000-0002-0000-' || lpad(to_hex(14 + (q - 71) / 3), 12, '0'))::uuid
        WHEN q BETWEEN 131 AND 146 THEN ('60000000-0000-0002-0000-' || lpad(to_hex(24 + (q - 131) / 4), 12, '0'))::uuid
        WHEN q BETWEEN 147 AND 200 THEN ('60000000-0000-0002-0000-' || lpad(to_hex(28 + (q - 147) / 3), 12, '0'))::uuid
        ELSE NULL
    END,
    CASE WHEN q <= 100 THEN 'listening' ELSE 'reading' END,
    CASE
        WHEN q <= 6 THEN 1
        WHEN q <= 31 THEN 2
        WHEN q <= 70 THEN 3
        WHEN q <= 100 THEN 4
        WHEN q <= 130 THEN 5
        WHEN q <= 146 THEN 6
        ELSE 7
    END,
    q,
    CASE WHEN q BETWEEN 131 AND 146 THEN ((q - 131) % 4) + 1 ELSE NULL END,
    q
FROM generate_series(1, 200) AS q
ON CONFLICT DO NOTHING;

INSERT INTO media_asset_use (id, release_id, package_use_id, asset_id, role, group_id, item_id, media_version)
SELECT
    ('60000000-0000-0008-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    'demo-item-media-' || q::text,
    CASE WHEN q <= 6 THEN 'a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f' ELSE '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd' END,
    CASE WHEN q <= 6 THEN 'image' ELSE 'audio' END,
    NULL,
    ('60000000-0000-0001-0000-' || lpad(to_hex(q), 12, '0'))::uuid,
    1
FROM generate_series(1, 31) AS q
ON CONFLICT DO NOTHING;

INSERT INTO media_asset_use (id, release_id, package_use_id, asset_id, role, group_id, item_id, media_version)
SELECT
    ('60000000-0000-0009-0000-' || lpad(to_hex(gid), 12, '0'))::uuid,
    '60000000-0000-0000-0000-000000000001',
    'demo-group-audio-' || gid::text,
    '2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd',
    'audio',
    ('60000000-0000-0002-0000-' || lpad(to_hex(gid), 12, '0'))::uuid,
    NULL,
    1
FROM generate_series(1, 23) AS gid
ON CONFLICT DO NOTHING;

INSERT INTO dataset_import_run (id, release_id, run_type, status, records_read, records_inserted, details_json, completed_at) VALUES (
    '60000000-0000-0000-0000-000000000002',
    '60000000-0000-0000-0000-000000000001',
    'APPLY',
    'SUCCEEDED',
    450,
    450,
    '{"source":"seed-demo"}'::jsonb,
    now()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO dataset_active_release (domain, release_id, activated_by)
VALUES ('grammar-toeic', '60000000-0000-0000-0000-000000000001', 'seed-demo')
ON CONFLICT (domain) DO NOTHING;

-- 1 OPEN TOEIC assessment_session for Minh Anh
INSERT INTO assessment_session (id, learner_id, domain, catalog_id, mode, snapshot, answers, result, revision, created_at, expires_at, submitted_at)
VALUES (
    '60000000-0000-0000-0000-000000000501',
    '30000000-0000-0000-0000-000000000001',
    'toeic',
    '60000000-0000-0000-0000-000000000201',
    'DRILL',
    '{
      "title": "Demo TOEIC Practice Test 2026",
      "publicationId": null,
      "groups": [],
      "items": [
        {
          "id": "60000000-0000-0006-0000-000000000065",
          "itemId": "60000000-0000-0001-0000-000000000065",
          "publicationId": null,
          "part": 5,
          "number": 101,
          "topicId": null,
          "subtopicId": null,
          "groupId": null,
          "gapNumber": null,
          "content": {
            "title": "Demo TOEIC question 101: choose the best answer.",
            "kind": "MULTIPLE_CHOICE",
            "options": [
              {"key": "A", "text": "Option A"},
              {"key": "B", "text": "Option B"},
              {"key": "C", "text": "Option C"},
              {"key": "D", "text": "Option D"}
            ],
            "correctOption": "A",
            "difficultyLevel": 2,
            "explanationVi": "Giải thích mẫu câu 101",
            "translationVi": "Bản dịch câu 101",
            "answerTranslationVi": null,
            "vocabularyNote": null,
            "transcriptEn": null,
            "media": []
          }
        }
      ]
    }'::jsonb,
    '{}'::jsonb,
    NULL,
    1,
    now() - interval '15 minutes',
    now() + interval '24 hours',
    NULL
) ON CONFLICT (id) DO NOTHING;

-- 7. Curriculum path FOUNDATION_PATH (2 sections, required + optional items, Minh Anh ACTIVE enrollment)
INSERT INTO curriculum_path (id, code, name, level, description, status) VALUES (
    '50000000-0000-0000-0000-000000000601',
    'FOUNDATION_PATH',
    'Lộ trình Nền tảng',
    'A2',
    'Lộ trình mẫu gồm Lesson, Vocabulary, Grammar và TOEIC.',
    'PUBLISHED'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_section (id, path_id, title, position) VALUES
    ('50000000-0000-0000-0000-000000000611', '50000000-0000-0000-0000-000000000601', 'Nền tảng Nghe & Từ vựng', 1),
    ('50000000-0000-0000-0000-000000000612', '50000000-0000-0000-0000-000000000601', 'Ngữ pháp & Kiểm tra TOEIC', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule) VALUES
    ('50000000-0000-0000-0000-000000000621', '50000000-0000-0000-0000-000000000611', 1, 'LESSON', '50000000-0000-0000-0000-000000000401', true, NULL),
    ('50000000-0000-0000-0000-000000000622', '50000000-0000-0000-0000-000000000611', 2, 'VOCABULARY_REVIEW', '50000000-0000-0000-0000-000000000221', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000623', '50000000-0000-0000-0000-000000000612', 1, 'GRAMMAR_PRACTICE', '60000000-0000-0000-0000-000000000101', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000624', '50000000-0000-0000-0000-000000000612', 2, 'TOEIC_TEST', '60000000-0000-0000-0000-000000000201', false, NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_enrollment (learner_id, path_id, status, started_at)
VALUES ('30000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000601', 'ACTIVE', now() - interval '2 days')
ON CONFLICT (learner_id, path_id) DO NOTHING;

-- 8. Gamification: DAILY_GRAMMAR_5 at 4/5, 2-day streak, FIRST_LESSON unlocked
INSERT INTO mission_progress (learner_id, mission_id, period_key, progress, completed_at, claimed_at)
VALUES (
    '30000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000004',
    to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD'),
    4,
    NULL,
    NULL
) ON CONFLICT (learner_id, mission_id, period_key) DO NOTHING;

INSERT INTO learner_streak_day (learner_id, study_date, first_fact_id) VALUES
    ('30000000-0000-0000-0000-000000000001', ((now() AT TIME ZONE 'UTC')::date - 1), '50000000-0000-0000-0000-000000000701'),
    ('30000000-0000-0000-0000-000000000001', (now() AT TIME ZONE 'UTC')::date, '50000000-0000-0000-0000-000000000702')
ON CONFLICT (learner_id, study_date) DO NOTHING;

INSERT INTO learner_streak (learner_id, current_streak, longest_streak, last_study_date)
VALUES ('30000000-0000-0000-0000-000000000001', 2, 2, (now() AT TIME ZONE 'UTC')::date)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO learner_achievement (learner_id, achievement_id, progress, unlocked_at, reward_snapshot_json)
VALUES (
    '30000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    1,
    now() - interval '1 day',
    '{"xp":20,"diamonds":0,"ruleVersion":"dev-v1"}'::jsonb
) ON CONFLICT (learner_id, achievement_id) DO NOTHING;

INSERT INTO learner_achievement_item (learner_id, metric, item_id)
VALUES ('30000000-0000-0000-0000-000000000001', 'DISTINCT_LESSON_COMPLETED', '50000000-0000-0000-0000-000000000401')
ON CONFLICT (learner_id, metric, item_id) DO NOTHING;

INSERT INTO xp_transaction (id, learner_id, source_type, amount, idempotency_key, rule_version, reference_id)
VALUES (
    '50000000-0000-0000-0000-000000000711',
    '30000000-0000-0000-0000-000000000001',
    'ACHIEVEMENT_REWARD',
    20,
    'achievement:40000000-0000-0000-0000-000000000001:30000000-0000-0000-0000-000000000001',
    'dev-v1',
    '40000000-0000-0000-0000-000000000001'
) ON CONFLICT (idempotency_key) DO NOTHING;

INSERT INTO learner_level (learner_id, xp, level)
VALUES ('30000000-0000-0000-0000-000000000001', 20, 1)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_wallet (learner_id, cached_balance)
VALUES ('30000000-0000-0000-0000-000000000001', 0)
ON CONFLICT (learner_id) DO NOTHING;

-- ============================================================
-- Extended Rich Demo Data (Media, Learners, Lessons, Vocabulary, Paths, Analytics)
-- ============================================================

-- 9. Rich image (WebP) and real synthesized speech (WAV) assets from infra/postgres/seed/assets/
INSERT INTO media_blob (sha256, mime_type, size_bytes, storage_object_key) VALUES
    ('6162a4f9d4e8570be83fe7b49a3f8681989538fbd5f2756ed872e6cf59293a2c', 'image/webp', 10728, 'media/sha256/61/6162a4f9d4e8570be83fe7b49a3f8681989538fbd5f2756ed872e6cf59293a2c'),
    ('64852a9f73e79bd6f8e13e5c0228bc3d6c68419d549c258245d5311a9a63473c', 'image/webp', 5342,  'media/sha256/64/64852a9f73e79bd6f8e13e5c0228bc3d6c68419d549c258245d5311a9a63473c'),
    ('c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04'),
    ('bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78'),
    ('cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6'),
    ('3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9'),
    ('48ec682780d1ceb8cd00460c41d605d082b4edda6d0b84c1d2469b57b082a0f2', 'audio/wav',  235412,'media/sha256/48/48ec682780d1ceb8cd00460c41d605d082b4edda6d0b84c1d2469b57b082a0f2'),
    ('3c347b145b3a2f598792310057bffd492aa899260367c1e67fda8334038f7b85', 'audio/wav',  257394,'media/sha256/3c/3c347b145b3a2f598792310057bffd492aa899260367c1e67fda8334038f7b85'),
    ('4bb64654e0741c7f654e41fe78c8aa9db4c26a4ab8c290cf8c44728be4afc581', 'audio/wav',  216856,'media/sha256/4b/4bb64654e0741c7f654e41fe78c8aa9db4c26a4ab8c290cf8c44728be4afc581'),
    ('e57ec905995362f70672cd0c694b620f8a7aef2684429a00017a51e62e7afd13', 'audio/wav',  178610,'media/sha256/e5/e57ec905995362f70672cd0c694b620f8a7aef2684429a00017a51e62e7afd13')
ON CONFLICT (sha256) DO NOTHING;

INSERT INTO content_asset (id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by) VALUES
    ('50000000-0000-0000-0000-000000000103', '6162a4f9d4e8570be83fe7b49a3f8681989538fbd5f2756ed872e6cf59293a2c', 'media/sha256/61/6162a4f9d4e8570be83fe7b49a3f8681989538fbd5f2756ed872e6cf59293a2c', 'image/webp', 10728, 'vocab-finance-bank.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000104', '64852a9f73e79bd6f8e13e5c0228bc3d6c68419d549c258245d5311a9a63473c', 'media/sha256/64/64852a9f73e79bd6f8e13e5c0228bc3d6c68419d549c258245d5311a9a63473c', 'image/webp', 5342,  'vocab-river-bank.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000105', 'c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'vocab-airport-boarding.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000106', 'bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'vocab-coffee-ordering.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000107', 'cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'vocab-office-standup.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000108', '3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'vocab-hotel-concierge.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000109', '48ec682780d1ceb8cd00460c41d605d082b4edda6d0b84c1d2469b57b082a0f2', 'media/sha256/48/48ec682780d1ceb8cd00460c41d605d082b4edda6d0b84c1d2469b57b082a0f2', 'audio/wav',  235412,'lesson-coffee-shop.wav', true, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000110', '3c347b145b3a2f598792310057bffd492aa899260367c1e67fda8334038f7b85', 'media/sha256/3c/3c347b145b3a2f598792310057bffd492aa899260367c1e67fda8334038f7b85', 'audio/wav',  257394,'lesson-standup-update.wav', true, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000111', '4bb64654e0741c7f654e41fe78c8aa9db4c26a4ab8c290cf8c44728be4afc581', 'media/sha256/4b/4bb64654e0741c7f654e41fe78c8aa9db4c26a4ab8c290cf8c44728be4afc581', 'audio/wav',  216856,'lesson-airport-checkin.wav', true, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000112', 'e57ec905995362f70672cd0c694b620f8a7aef2684429a00017a51e62e7afd13', 'media/sha256/e5/e57ec905995362f70672cd0c694b620f8a7aef2684429a00017a51e62e7afd13', 'audio/wav',  178610,'vocab-business-terms.wav', true, 'seed-demo')
ON CONFLICT (id) DO NOTHING;

-- 10. Additional demo learner directory records & grants
INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES
    ('30000000-0000-0000-0000-000000000004', 'offline-demo-thao-vy', 'thao-vy@example.test'),
    ('30000000-0000-0000-0000-000000000005', 'offline-demo-hoang-nam', 'hoang-nam@example.test'),
    ('30000000-0000-0000-0000-000000000006', 'offline-demo-khanh-linh', 'khanh-linh@example.test'),
    ('30000000-0000-0000-0000-000000000007', 'offline-demo-quoc-bao', 'quoc-bao@example.test'),
    ('30000000-0000-0000-0000-000000000008', 'offline-demo-mai-phuong', 'mai-phuong@example.test')
ON CONFLICT DO NOTHING;

INSERT INTO learner_profile (
    learner_id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at
)
SELECT v.id, v.display_name, v.goal, v.current_level, v.daily_minutes, v.focus_area, v.avatar_preset, v.first_seen_at, v.onboarding_completed_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000004'::uuid, 'Trần Thảo Vy', 'WORK', 'B2', 30, 'SPEAKING', 'COMIC_LUNA', now() - interval '14 days', now() - interval '14 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000005'::uuid, 'Lê Hoàng Nam', 'TRAVEL', 'A1', 15, 'VOCABULARY', 'TOUCAN', now() - interval '9 days', now() - interval '9 days' + interval '3 minutes'),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'Phạm Khánh Linh', 'EXAM', 'C1', 45, 'READING', 'FLOWER_IRISES', now() - interval '21 days', now() - interval '21 days' + interval '6 minutes'),
    ('30000000-0000-0000-0000-000000000007'::uuid, 'Đỗ Quốc Bảo', 'CONVERSATION', 'B1', 20, 'GRAMMAR', 'RED_PANDA', now() - interval '5 days', now() - interval '5 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000008'::uuid, 'Vũ Mai Phương', NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', NULL)
) AS v(id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.id)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO user_entitlement_grant (
    id, user_id, feature_key, status, valid_from, valid_until, source_type, source_reference, created_by, revoked_at, revoked_by, revocation_reason
) VALUES
    ('50000000-0000-0000-0000-000000000031', '30000000-0000-0000-0000-000000000004', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000032', '30000000-0000-0000-0000-000000000004', 'speech.shadowing', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000033', '30000000-0000-0000-0000-000000000004', 'chat.tutor', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000034', '30000000-0000-0000-0000-000000000006', 'toeic.full-access', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000035', '30000000-0000-0000-0000-000000000006', 'grammar.advanced', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000036', '30000000-0000-0000-0000-000000000006', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'seed-demo', NULL, NULL, NULL),
    ('50000000-0000-0000-0000-000000000037', '30000000-0000-0000-0000-000000000007', 'speech.shadowing', 'REVOKED', now() - interval '10 days', now() + interval '20 days', 'ADMIN_MANUAL', 'trial-pass', 'seed-demo', now() - interval '6 hours', 'seed-demo', 'Hết thời gian trải nghiệm thử')
ON CONFLICT (id) DO NOTHING;

-- 11. Three additional PUBLISHED audio lessons with spoken WAVs & calibrated timings
INSERT INTO lesson_category (id, name, translations) VALUES
    ('20000000-0000-0000-0000-000000000002', 'Workplace & Standups', '{"vi":"Công sở & Họp nhóm"}'::jsonb),
    ('20000000-0000-0000-0000-000000000003', 'Travel & Airport', '{"vi":"Du lịch & Sân bay"}'::jsonb),
    ('20000000-0000-0000-0000-000000000004', 'Daily Cafes & Dining', '{"vi":"Quán cà phê & Ẩm thực"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO lesson (id, title, source_type, source_text, source_reference, canonical_audio_object_key, status, created_at, updated_at) VALUES
    ('50000000-0000-0000-0000-000000000403', 'Ordering Coffee at a Local Cafe', 'AUDIO',
     'Good morning! Can I get a hot oat latte to go? Sure, your order will be ready at the counter.',
     'demo:published:3', 'media/sha256/48/48ec682780d1ceb8cd00460c41d605d082b4edda6d0b84c1d2469b57b082a0f2', 'PUBLISHED',
     now() - interval '6 days', now() - interval '5 days'),
    ('50000000-0000-0000-0000-000000000404', 'Daily Engineering Standup', 'AUDIO',
     'Yesterday our team shipped the vocabulary catalog. Today we are reviewing shadowing audio and learner progress.',
     'demo:published:4', 'media/sha256/3c/3c347b145b3a2f598792310057bffd492aa899260367c1e67fda8334038f7b85', 'PUBLISHED',
     now() - interval '5 days', now() - interval '4 days'),
    ('50000000-0000-0000-0000-000000000405', 'Airport Check-in and Boarding', 'AUDIO',
     'May I see your passport and boarding pass? Gate twelve begins boarding in twenty minutes.',
     'demo:published:5', 'media/sha256/4b/4bb64654e0741c7f654e41fe78c8aa9db4c26a4ab8c290cf8c44728be4afc581', 'PUBLISHED',
     now() - interval '4 days', now() - interval '3 days')
ON CONFLICT (id) DO NOTHING;

UPDATE lesson_metadata m
SET description = v.descr,
    visible = true,
    updated_at = now() - interval '3 days'
FROM (VALUES
    ('50000000-0000-0000-0000-000000000403'::uuid, 'Real spoken A1-A2 dialogue ordering espresso and pastry at a cafe counter.'),
    ('50000000-0000-0000-0000-000000000404'::uuid, 'Concise B1 software standup update practicing past deliverables and current focus.'),
    ('50000000-0000-0000-0000-000000000405'::uuid, 'Airport desk check-in phrases covering passports, boarding passes, and gate times.')
) AS v(id, descr)
WHERE m.lesson_id = v.id
  AND NOT EXISTS (SELECT 1 FROM lesson_authoring a WHERE a.lesson_id = v.id AND a.published_at IS NOT NULL);

INSERT INTO lesson_category_membership (lesson_id, category_id)
SELECT v.lesson_id, v.category_id
FROM (VALUES
    ('50000000-0000-0000-0000-000000000403'::uuid, '20000000-0000-0000-0000-000000000004'::uuid),
    ('50000000-0000-0000-0000-000000000404'::uuid, '20000000-0000-0000-0000-000000000002'::uuid),
    ('50000000-0000-0000-0000-000000000405'::uuid, '20000000-0000-0000-0000-000000000003'::uuid)
) AS v(lesson_id, category_id)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring a WHERE a.lesson_id = v.lesson_id AND a.published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_sentence (id, lesson_id, position, text, audio_start_ms, audio_end_ms)
SELECT v.id, v.lesson_id, v.pos, v.txt, v.start_ms, v.end_ms
FROM (VALUES
    ('50000000-0000-0000-0000-000000000461'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, 0, 'Good morning! Can I get a hot oat latte to go?', 0::bigint, 3865::bigint),
    ('50000000-0000-0000-0000-000000000462'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, 1, 'Sure, your order will be ready at the counter.', 4115::bigint, 7354::bigint),
    ('50000000-0000-0000-0000-000000000463'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, 0, 'Yesterday our team shipped the vocabulary catalog.', 0::bigint, 3794::bigint),
    ('50000000-0000-0000-0000-000000000464'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, 1, 'Today we are reviewing shadowing audio and learner progress.', 4044::bigint, 8041::bigint),
    ('50000000-0000-0000-0000-000000000465'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, 0, 'May I see your passport and boarding pass?', 0::bigint, 3090::bigint),
    ('50000000-0000-0000-0000-000000000466'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, 1, 'Gate twelve begins boarding in twenty minutes.', 3340::bigint, 6774::bigint)
) AS v(id, lesson_id, pos, txt, start_ms, end_ms)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring a WHERE a.lesson_id = v.lesson_id AND a.published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_annotation (id, lesson_id, sentence_id, annotation_type, payload_json)
SELECT v.id, v.lesson_id, v.sentence_id, 'CONTENT', v.payload
FROM (VALUES
    ('50000000-0000-0000-0000-000000000471'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, '50000000-0000-0000-0000-000000000461'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000461","text":"Good morning! Can I get a hot oat latte to go?","translation":"Chào buổi sáng! Cho tôi gọi một ly latte yến mạch nóng mang đi nhé?","dictationSelected":true,"shadowingSelected":true}'::jsonb),
    ('50000000-0000-0000-0000-000000000472'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, '50000000-0000-0000-0000-000000000462'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000462","text":"Sure, your order will be ready at the counter.","translation":"Vâng, đồ uống của bạn sẽ sẵn sàng tại quầy.","dictationSelected":true,"shadowingSelected":true}'::jsonb),
    ('50000000-0000-0000-0000-000000000473'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, '50000000-0000-0000-0000-000000000463'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000463","text":"Yesterday our team shipped the vocabulary catalog.","translation":"Hôm qua nhóm chúng tôi đã phát hành danh mục từ vựng.","dictationSelected":true,"shadowingSelected":true}'::jsonb),
    ('50000000-0000-0000-0000-000000000474'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, '50000000-0000-0000-0000-000000000464'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000464","text":"Today we are reviewing shadowing audio and learner progress.","translation":"Hôm nay chúng tôi rà soát âm thanh shadowing và tiến độ người học.","dictationSelected":true,"shadowingSelected":true}'::jsonb),
    ('50000000-0000-0000-0000-000000000475'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, '50000000-0000-0000-0000-000000000465'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000465","text":"May I see your passport and boarding pass?","translation":"Vui lòng cho tôi xem hộ chiếu và thẻ lên máy bay của bạn?","dictationSelected":true,"shadowingSelected":true}'::jsonb),
    ('50000000-0000-0000-0000-000000000476'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, '50000000-0000-0000-0000-000000000466'::uuid,
     '{"id":"50000000-0000-0000-0000-000000000466","text":"Gate twelve begins boarding in twenty minutes.","translation":"Cửa số 12 sẽ bắt đầu lên máy bay sau 20 phút nữa.","dictationSelected":true,"shadowingSelected":true}'::jsonb)
) AS v(id, lesson_id, sentence_id, payload)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring a WHERE a.lesson_id = v.lesson_id AND a.published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_activity (id, lesson_id, activity_type, position, enabled, config_json)
SELECT v.id, v.lesson_id, v.activity_type, v.pos, true, '{"clozeVersion":"cloze-v2"}'::jsonb
FROM (VALUES
    ('50000000-0000-0000-0000-000000000481'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, 'WATCH', 0),
    ('50000000-0000-0000-0000-000000000482'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, 'DICTATION', 1),
    ('50000000-0000-0000-0000-000000000483'::uuid, '50000000-0000-0000-0000-000000000403'::uuid, 'SHADOWING', 2),
    ('50000000-0000-0000-0000-000000000484'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, 'WATCH', 0),
    ('50000000-0000-0000-0000-000000000485'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, 'DICTATION', 1),
    ('50000000-0000-0000-0000-000000000486'::uuid, '50000000-0000-0000-0000-000000000404'::uuid, 'SHADOWING', 2),
    ('50000000-0000-0000-0000-000000000487'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, 'WATCH', 0),
    ('50000000-0000-0000-0000-000000000488'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, 'DICTATION', 1),
    ('50000000-0000-0000-0000-000000000489'::uuid, '50000000-0000-0000-0000-000000000405'::uuid, 'SHADOWING', 2)
) AS v(id, lesson_id, activity_type, pos)
WHERE NOT EXISTS (SELECT 1 FROM lesson_authoring a WHERE a.lesson_id = v.lesson_id AND a.published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_activity_sentence (activity_id, sentence_id)
SELECT a.id, s.id
FROM lesson_activity a
JOIN lesson_sentence s ON s.lesson_id = a.lesson_id
WHERE a.lesson_id IN (
    '50000000-0000-0000-0000-000000000403'::uuid,
    '50000000-0000-0000-0000-000000000404'::uuid,
    '50000000-0000-0000-0000-000000000405'::uuid
)
  AND NOT EXISTS (SELECT 1 FROM lesson_authoring au WHERE au.lesson_id = a.lesson_id AND au.published_at IS NOT NULL)
ON CONFLICT DO NOTHING;

INSERT INTO lesson_authoring (
    lesson_id, source_start_ms, source_end_ms, revision, transcript_revision, timing_status, document, published_at
) VALUES
    ('50000000-0000-0000-0000-000000000403', 0, 7354, 1, 1, 'VALID', '[
      {"id":"50000000-0000-0000-0000-000000000461","text":"Good morning! Can I get a hot oat latte to go?","usage":"LEARN","coarseStartMs":0,"coarseEndMs":3865,"translation":"Chào buổi sáng! Cho tôi gọi một ly latte yến mạch nóng mang đi nhé?","dictationSelected":true,"shadowingSelected":true},
      {"id":"50000000-0000-0000-0000-000000000462","text":"Sure, your order will be ready at the counter.","usage":"LEARN","coarseStartMs":4115,"coarseEndMs":7354,"translation":"Vâng, đồ uống của bạn sẽ sẵn sàng tại quầy.","dictationSelected":true,"shadowingSelected":true}
    ]'::jsonb, now() - interval '5 days'),
    ('50000000-0000-0000-0000-000000000404', 0, 8041, 1, 1, 'VALID', '[
      {"id":"50000000-0000-0000-0000-000000000463","text":"Yesterday our team shipped the vocabulary catalog.","usage":"LEARN","coarseStartMs":0,"coarseEndMs":3794,"translation":"Hôm qua nhóm chúng tôi đã phát hành danh mục từ vựng.","dictationSelected":true,"shadowingSelected":true},
      {"id":"50000000-0000-0000-0000-000000000464","text":"Today we are reviewing shadowing audio and learner progress.","usage":"LEARN","coarseStartMs":4044,"coarseEndMs":8041,"translation":"Hôm nay chúng tôi rà soát âm thanh shadowing và tiến độ người học.","dictationSelected":true,"shadowingSelected":true}
    ]'::jsonb, now() - interval '4 days'),
    ('50000000-0000-0000-0000-000000000405', 0, 6774, 1, 1, 'VALID', '[
      {"id":"50000000-0000-0000-0000-000000000465","text":"May I see your passport and boarding pass?","usage":"LEARN","coarseStartMs":0,"coarseEndMs":3090,"translation":"Vui lòng cho tôi xem hộ chiếu và thẻ lên máy bay của bạn?","dictationSelected":true,"shadowingSelected":true},
      {"id":"50000000-0000-0000-0000-000000000466","text":"Gate twelve begins boarding in twenty minutes.","usage":"LEARN","coarseStartMs":3340,"coarseEndMs":6774,"translation":"Cửa số 12 sẽ bắt đầu lên máy bay sau 20 phút nữa.","dictationSelected":true,"shadowingSelected":true}
    ]'::jsonb, now() - interval '3 days')
ON CONFLICT (lesson_id) DO NOTHING;

-- 12. Two additional Vocabulary packs with sense-matched WebP illustrations & real WAV audio
INSERT INTO vocabulary_pack (id, release_id, source_topic, position, title_en, title_vi, hidden, access_mode, required_feature_key) VALUES
    ('50000000-0000-0000-0000-000000000214', '50000000-0000-0000-0000-000000000201', 'demo_travel', 4, 'Travel & Hospitality', 'Du lịch & Khách sạn', false, 'PUBLIC', NULL),
    ('50000000-0000-0000-0000-000000000215', '50000000-0000-0000-0000-000000000201', 'demo_executive', 5, 'Workplace & Finance', 'Công sở & Tài chính', false, 'FEATURE', 'vocabulary.plus-packs')
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_unit (id, pack_id, position, level, title, hidden) VALUES
    ('50000000-0000-0000-0000-000000000224', '50000000-0000-0000-0000-000000000214', 1, 'A1', 'A1 · Airport & Boarding', false),
    ('50000000-0000-0000-0000-000000000225', '50000000-0000-0000-0000-000000000214', 2, 'A2', 'A2 · Cafe & Concierge', false),
    ('50000000-0000-0000-0000-000000000226', '50000000-0000-0000-0000-000000000215', 1, 'B2', 'B2 · Finance & Standups', false)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_unit_entry (
    id, unit_id, position, source_id, term, content,
    image_id, uk_audio_id, us_audio_id, example_audio_id, second_example_audio_id, hidden
) VALUES
    ('50000000-0000-0000-0000-000000000271', '50000000-0000-0000-0000-000000000224', 1, 'demo-boarding-pass', 'boarding pass',
     '{"ipaUk":"/ˈbɔːdɪŋ pɑːs/","ipaUs":"/ˈbɔːrdɪŋ pæs/","senses":[{"pos":"n","definitionEn":"an official card that allows a passenger to board an aircraft","meaningVi":"thẻ lên máy bay"}],"examples":["Show your boarding pass at gate twelve.","I saved a digital boarding pass on my phone."],"examplesVi":["Hãy xuất trình thẻ lên máy bay tại cửa số 12.","Tôi đã lưu thẻ lên máy bay điện tử trên điện thoại."],"collocations":["mobile boarding pass","scan a boarding pass"],"collocationsVi":["thẻ lên máy bay điện tử","quét thẻ lên máy bay"],"synonyms":["boarding card"],"antonyms":[],"family":["board"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000105', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', false),
    ('50000000-0000-0000-0000-000000000272', '50000000-0000-0000-0000-000000000224', 2, 'demo-itinerary', 'itinerary',
     '{"ipaUk":"/aɪˈtɪnərəri/","ipaUs":"/aɪˈtɪnəreri/","senses":[{"pos":"n","definitionEn":"a detailed plan of a journey including routes and times","meaningVi":"lịch trình chuyến đi"}],"examples":["Our tour itinerary starts with Kyoto.","Send the updated flight itinerary to the team."],"examplesVi":["Lịch trình chuyến tham quan bắt đầu tại Kyoto.","Gửi lịch trình bay cập nhật cho cả nhóm."],"collocations":["travel itinerary","tight itinerary"],"collocationsVi":["lịch trình du lịch","lịch trình dày đặc"],"synonyms":["schedule","route"],"antonyms":[],"family":["itineraries"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000105', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', false),
    ('50000000-0000-0000-0000-000000000273', '50000000-0000-0000-0000-000000000224', 3, 'demo-departure', 'departure',
     '{"ipaUk":"/dɪˈpɑːtʃə/","ipaUs":"/dɪˈpɑːrtʃər/","senses":[{"pos":"n","definitionEn":"the act of leaving a place especially to start a journey","meaningVi":"sự khởi hành"}],"examples":["Check the departure screen for gate updates.","Our morning departure was delayed by fog."],"examplesVi":["Hãy xem bảng giờ khởi hành để cập nhật cửa ra máy bay.","Chuyến khởi hành buổi sáng bị hoãn vì sương mù."],"collocations":["departure gate","departure lounge"],"collocationsVi":["cửa khởi hành","phòng chờ khởi hành"],"synonyms":["exit","takeoff"],"antonyms":["arrival"],"family":["depart"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000105', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000111', false),
    ('50000000-0000-0000-0000-000000000274', '50000000-0000-0000-0000-000000000225', 1, 'demo-espresso', 'latte',
     '{"ipaUk":"/ˈlæteɪ/","ipaUs":"/ˈlɑːteɪ/","senses":[{"pos":"n","definitionEn":"coffee made with espresso and steamed milk","meaningVi":"cà phê latte"}],"examples":["Can I get a hot oat latte to go?","She ordered an iced latte after lunch."],"examplesVi":["Cho tôi gọi một ly latte yến mạch nóng mang đi nhé?","Cô ấy gọi một ly latte đá sau bữa trưa."],"collocations":["oat latte","iced latte"],"collocationsVi":["latte yến mạch","latte đá"],"synonyms":["milk coffee"],"antonyms":[],"family":["lattes"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000106', '50000000-0000-0000-0000-000000000109', '50000000-0000-0000-0000-000000000109', '50000000-0000-0000-0000-000000000109', '50000000-0000-0000-0000-000000000109', false),
    ('50000000-0000-0000-0000-000000000275', '50000000-0000-0000-0000-000000000225', 2, 'demo-concierge', 'concierge',
     '{"ipaUk":"/ˌkɒnsiˈeəʒ/","ipaUs":"/ˌkɑːnsiˈerʒ/","senses":[{"pos":"n","definitionEn":"a hotel staff member who assists guests with bookings and luggage","meaningVi":"nhân viên hỗ trợ khách sạn"}],"examples":["Ask the concierge for a quiet dinner reservation.","The hotel concierge arranged our airport taxi."],"examplesVi":["Hãy nhờ nhân viên hỗ trợ khách sạn đặt bàn ăn tối yên tĩnh.","Nhân viên hỗ trợ khách sạn đã sắp xếp xe ra sân bay cho chúng tôi."],"collocations":["hotel concierge","concierge desk"],"collocationsVi":["nhân viên hỗ trợ khách sạn","quầy hỗ trợ khách"],"synonyms":["attendant","host"],"antonyms":[],"family":[],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000108', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', false),
    ('50000000-0000-0000-0000-000000000276', '50000000-0000-0000-0000-000000000225', 3, 'demo-riverbank', 'riverbank',
     '{"ipaUk":"/ˈrɪvəbæŋk/","ipaUs":"/ˈrɪvərbæŋk/","senses":[{"pos":"n","definitionEn":"the land along the edge of a river","meaningVi":"bờ sông"}],"examples":["Families walked along the quiet riverbank at sunset.","We took photos from the shaded riverbank."],"examplesVi":["Nhiều gia đình dạo bộ dọc bờ sông yên tĩnh lúc hoàng hôn.","Chúng tôi chụp ảnh từ bờ sông rợp bóng mát."],"collocations":["grassy riverbank","along the riverbank"],"collocationsVi":["bờ sông xanh cỏ","dọc theo bờ sông"],"synonyms":["riverside","shore"],"antonyms":["midstream"],"family":[],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000104', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000101', false),
    ('50000000-0000-0000-0000-000000000277', '50000000-0000-0000-0000-000000000226', 1, 'demo-standup', 'standup',
     '{"ipaUk":"/ˈstændʌp/","ipaUs":"/ˈstændʌp/","senses":[{"pos":"n","definitionEn":"a short daily team meeting held to sync progress and blockers","meaningVi":"họp đứng đầu ngày"}],"examples":["Our engineering standup starts at nine sharp.","Share blockers quickly during morning standup."],"examplesVi":["Buổi họp đứng kỹ thuật bắt đầu đúng chín giờ.","Hãy nêu nhanh các điểm nghẽn trong buổi họp đầu ngày."],"collocations":["daily standup","morning standup"],"collocationsVi":["họp đứng hằng ngày","họp đầu sáng"],"synonyms":["huddle","sync"],"antonyms":[],"family":[],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000107', '50000000-0000-0000-0000-000000000110', '50000000-0000-0000-0000-000000000110', '50000000-0000-0000-0000-000000000110', '50000000-0000-0000-0000-000000000110', false),
    ('50000000-0000-0000-0000-000000000278', '50000000-0000-0000-0000-000000000226', 2, 'demo-negotiate', 'negotiate',
     '{"ipaUk":"/nɪˈɡəʊʃieɪt/","ipaUs":"/nɪˈɡoʊʃieɪt/","senses":[{"pos":"v","definitionEn":"to try to reach an agreement by formal discussion","meaningVi":"đàm phán, thương lượng"}],"examples":["Both companies met to negotiate the licensing terms.","Learn how to negotiate project timelines clearly."],"examplesVi":["Hai công ty đã gặp nhau để đàm phán điều khoản cấp phép.","Học cách thương lượng tiến độ dự án rõ ràng."],"collocations":["negotiate terms","negotiate a contract"],"collocationsVi":["đàm phán điều khoản","thương lượng hợp đồng"],"synonyms":["bargain","mediate"],"antonyms":["refuse"],"family":["negotiation","negotiator"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000103', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', false),
    ('50000000-0000-0000-0000-000000000279', '50000000-0000-0000-0000-000000000226', 3, 'demo-vault', 'vault',
     '{"ipaUk":"/vɔːlt/","ipaUs":"/vɑːlt/","senses":[{"pos":"n","definitionEn":"a strongly protected room or safe in a bank for storing valuables","meaningVi":"két sắt ngân hàng"}],"examples":["Reserve assets stay inside the central bank vault.","The bank vault closes automatically after hours."],"examplesVi":["Tài sản dự trữ được giữ trong két sắt ngân hàng trung ương.","Két sắt ngân hàng tự động khóa sau giờ làm việc."],"collocations":["bank vault","safety vault"],"collocationsVi":["két ngân hàng","kho lưu giữ an toàn"],"synonyms":["safe","treasury"],"antonyms":[],"family":["vaults"],"contextNotes":[],"needsReview":false}'::jsonb,
     '50000000-0000-0000-0000-000000000103', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000112', false)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0011-0000-' || lpad(to_hex(p.position), 12, '0'))::uuid,
       'PACK', p.id, p.release_id, p.source_topic,
       jsonb_build_object('source_topic', p.source_topic, 'title_en', p.title_en, 'title_vi', p.title_vi, 'hidden', p.hidden),
       false
FROM vocabulary_pack p
WHERE p.id IN ('50000000-0000-0000-0000-000000000214', '50000000-0000-0000-0000-000000000215')
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0012-0000-' || lpad(to_hex(row_number() OVER (ORDER BY p.position, u.position)), 12, '0'))::uuid,
       'UNIT', u.id, p.release_id, p.source_topic || ':' || u.level || ':' || u.position,
       jsonb_build_object('pack_source_topic', p.source_topic, 'level', u.level, 'title', u.title, 'hidden', u.hidden),
       false
FROM vocabulary_unit u
JOIN vocabulary_pack p ON p.id = u.pack_id
WHERE p.id IN ('50000000-0000-0000-0000-000000000214', '50000000-0000-0000-0000-000000000215')
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO vocabulary_release_lineage (id, entity_type, entity_id, release_id, stable_key, base_snapshot, admin_modified)
SELECT ('50000000-0000-0013-0000-' || lpad(to_hex(row_number() OVER (ORDER BY p.position, u.position, e.position)), 12, '0'))::uuid,
       'ENTRY', e.id, p.release_id, e.source_id,
       jsonb_build_object('source_id', e.source_id, 'term', e.term, 'content', e.content, 'hidden', e.hidden),
       false
FROM vocabulary_unit_entry e
JOIN vocabulary_unit u ON u.id = e.unit_id
JOIN vocabulary_pack p ON p.id = u.pack_id
WHERE p.id IN ('50000000-0000-0000-0000-000000000214', '50000000-0000-0000-0000-000000000215')
ON CONFLICT (entity_type, entity_id) DO NOTHING;

INSERT INTO vocabulary_collection (id, learner_id, name, is_default, cover_preset) VALUES
    ('50000000-0000-0000-0000-000000000263', '30000000-0000-0000-0000-000000000004', 'Đã lưu', true, 'BLUE_BOOK'),
    ('50000000-0000-0000-0000-000000000264', '30000000-0000-0000-0000-000000000004', 'Phỏng vấn & Standup', false, 'EMERALD_NOTE'),
    ('50000000-0000-0000-0000-000000000265', '30000000-0000-0000-0000-000000000005', 'Đã lưu', true, 'AMBER_CARD'),
    ('50000000-0000-0000-0000-000000000266', '30000000-0000-0000-0000-000000000006', 'Đã lưu', true, 'ROSE_DECK')
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_card (
    id, learner_id, headword_id, source_context_type, source_context_id,
    next_review_at, stability, difficulty, lapse_count, review_count,
    dedup_key, term, part_of_speech, meaning_vi, definition_en, ipa,
    context_sentence, context_translation_vi, source_entry_id, sense_index, source_revision,
    audio_asset_id, image_asset_id, snapshot_json
) VALUES
    ('50000000-0000-0005-0000-000000000001', '30000000-0000-0000-0000-000000000004', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000226',
     now() - interval '2 hours', 2.4, 4.1, 0, 2, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000277:SENSE:0',
     'standup', 'n', 'họp đứng đầu ngày', 'a short daily team meeting held to sync progress and blockers', '/ˈstændʌp/',
     'Our engineering standup starts at nine sharp.', 'Buổi họp đứng kỹ thuật bắt đầu đúng chín giờ.',
     '50000000-0000-0000-0000-000000000277', 0, 0, '50000000-0000-0000-0000-000000000110', '50000000-0000-0000-0000-000000000107', '{"seed":"demo-v2"}'::jsonb),
    ('50000000-0000-0005-0000-000000000002', '30000000-0000-0000-0000-000000000004', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000226',
     now() + interval '1 day', 4.0, 3.8, 0, 3, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000278:SENSE:0',
     'negotiate', 'v', 'đàm phán, thương lượng', 'to try to reach an agreement by formal discussion', '/nɪˈɡoʊʃieɪt/',
     'Both companies met to negotiate the licensing terms.', 'Hai công ty đã gặp nhau để đàm phán điều khoản cấp phép.',
     '50000000-0000-0000-0000-000000000278', 0, 0, '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000103', '{"seed":"demo-v2"}'::jsonb),
    ('50000000-0000-0005-0000-000000000003', '30000000-0000-0000-0000-000000000004', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000221',
     now() + interval '2 days', 3.1, 4.2, 0, 2, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000237:SENSE:0',
     'bank', 'n', 'ngân hàng', 'an organization where people keep and borrow money', '/bæŋk/',
     'I opened a savings account at the bank today.', 'Hôm nay tôi đã mở tài khoản tiết kiệm tại ngân hàng.',
     '50000000-0000-0000-0000-000000000237', 0, 0, '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000103', '{"seed":"demo-v2"}'::jsonb),
    ('50000000-0000-0005-0000-000000000004', '30000000-0000-0000-0000-000000000004', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000221',
     now() + interval '3 days', 2.8, 4.4, 0, 1, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000237:SENSE:1',
     'bank', 'n', 'bờ sông', 'the sloping land beside a river or stream', '/bæŋk/',
     'We had lunch on the grassy river bank.', 'Chúng tôi đã ăn trưa trên bờ sông đầy cỏ.',
     '50000000-0000-0000-0000-000000000237', 1, 0, '50000000-0000-0000-0000-000000000101', '50000000-0000-0000-0000-000000000104', '{"seed":"demo-v2"}'::jsonb),
    ('50000000-0000-0005-0000-000000000005', '30000000-0000-0000-0000-000000000005', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000224',
     now() - interval '3 hours', 1.8, 4.7, 0, 1, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000271:SENSE:0',
     'boarding pass', 'n', 'thẻ lên máy bay', 'an official card that allows a passenger to board an aircraft', '/ˈbɔːrdɪŋ pæs/',
     'Show your boarding pass at gate twelve.', 'Hãy xuất trình thẻ lên máy bay tại cửa số 12.',
     '50000000-0000-0000-0000-000000000271', 0, 0, '50000000-0000-0000-0000-000000000111', '50000000-0000-0000-0000-000000000105', '{"seed":"demo-v2"}'::jsonb),
    ('50000000-0000-0005-0000-000000000006', '30000000-0000-0000-0000-000000000005', NULL, 'VOCABULARY_UNIT', '50000000-0000-0000-0000-000000000225',
     now() + interval '12 hours', 1.9, 4.3, 0, 1, 'VOCAB_ENTRY:50000000-0000-0000-0000-000000000275:SENSE:0',
     'concierge', 'n', 'nhân viên hỗ trợ khách sạn', 'a hotel staff member who assists guests with bookings and luggage', '/ˌkɑːnsiˈerʒ/',
     'Ask the concierge for a quiet dinner reservation.', 'Hãy nhờ nhân viên hỗ trợ khách sạn đặt bàn ăn tối yên tĩnh.',
     '50000000-0000-0000-0000-000000000275', 0, 0, '50000000-0000-0000-0000-000000000112', '50000000-0000-0000-0000-000000000108', '{"seed":"demo-v2"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_collection_item (collection_id, card_id) VALUES
    ('50000000-0000-0000-0000-000000000263', '50000000-0000-0005-0000-000000000001'),
    ('50000000-0000-0000-0000-000000000263', '50000000-0000-0005-0000-000000000002'),
    ('50000000-0000-0000-0000-000000000263', '50000000-0000-0005-0000-000000000003'),
    ('50000000-0000-0000-0000-000000000263', '50000000-0000-0005-0000-000000000004'),
    ('50000000-0000-0000-0000-000000000264', '50000000-0000-0005-0000-000000000001'),
    ('50000000-0000-0000-0000-000000000264', '50000000-0000-0005-0000-000000000002'),
    ('50000000-0000-0000-0000-000000000265', '50000000-0000-0005-0000-000000000005'),
    ('50000000-0000-0000-0000-000000000265', '50000000-0000-0005-0000-000000000006')
ON CONFLICT DO NOTHING;

-- 13. Three additional Curriculum paths + enrollments & item progression
INSERT INTO curriculum_path (id, code, name, level, description, status) VALUES
    ('50000000-0000-0000-0000-000000000602', 'TRAVEL_SURVIVAL_A1', 'Tiếng Anh Du lịch Thực chiến', 'A1',
     'Hội thoại quán cà phê, làm thủ tục sân bay và từ vựng khách sạn.', 'PUBLISHED'),
    ('50000000-0000-0000-0000-000000000603', 'WORKPLACE_FLUENCY_B1', 'Giao tiếp Công sở & Standup B1', 'B1',
     'Luyện họp nhóm hằng ngày, đàm phán điều khoản và củng cố thì động từ.', 'PUBLISHED'),
    ('50000000-0000-0000-0000-000000000604', 'TOEIC_650_SPRINT', 'Lộ trình Bứt phá TOEIC 650+', 'B2',
     'Bản nháp dành cho ban biên tập: kết hợp ngân hàng ngữ pháp nâng cao và đề TOEIC 200 câu.', 'DRAFT')
ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_section (id, path_id, title, position) VALUES
    ('50000000-0000-0000-0000-000000000613', '50000000-0000-0000-0000-000000000602', 'Sân bay & Check-in', 1),
    ('50000000-0000-0000-0000-000000000614', '50000000-0000-0000-0000-000000000602', 'Quán cà phê & Khách sạn', 2),
    ('50000000-0000-0000-0000-000000000615', '50000000-0000-0000-0000-000000000603', 'Họp Standup & Từ vựng Tài chính', 1),
    ('50000000-0000-0000-0000-000000000616', '50000000-0000-0000-0000-000000000604', 'Cấu trúc Nâng cao & Đề tổng hợp', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule) VALUES
    ('50000000-0000-0000-0000-000000000625', '50000000-0000-0000-0000-000000000613', 1, 'LESSON', '50000000-0000-0000-0000-000000000405', true, NULL),
    ('50000000-0000-0000-0000-000000000626', '50000000-0000-0000-0000-000000000613', 2, 'VOCABULARY_REVIEW', '50000000-0000-0000-0000-000000000224', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000627', '50000000-0000-0000-0000-000000000614', 1, 'LESSON', '50000000-0000-0000-0000-000000000403', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000628', '50000000-0000-0000-0000-000000000614', 2, 'VOCABULARY_REVIEW', '50000000-0000-0000-0000-000000000225', false, NULL),
    ('50000000-0000-0000-0000-000000000629', '50000000-0000-0000-0000-000000000615', 1, 'LESSON', '50000000-0000-0000-0000-000000000404', true, NULL),
    ('50000000-0000-0000-0000-000000000630', '50000000-0000-0000-0000-000000000615', 2, 'VOCABULARY_REVIEW', '50000000-0000-0000-0000-000000000226', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000631', '50000000-0000-0000-0000-000000000615', 3, 'GRAMMAR_PRACTICE', '60000000-0000-0000-0000-000000000101', true, 'PREVIOUS_REQUIRED'),
    ('50000000-0000-0000-0000-000000000632', '50000000-0000-0000-0000-000000000616', 1, 'GRAMMAR_PRACTICE', '60000000-0000-0000-0000-000000000121', true, NULL),
    ('50000000-0000-0000-0000-000000000633', '50000000-0000-0000-0000-000000000616', 2, 'TOEIC_TEST', '60000000-0000-0000-0000-000000000201', true, 'PREVIOUS_REQUIRED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO curriculum_enrollment (learner_id, path_id, status, started_at) VALUES
    ('30000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000603', 'ACTIVE', now() - interval '6 days'),
    ('30000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000602', 'ACTIVE', now() - interval '4 days')
ON CONFLICT (learner_id, path_id) DO NOTHING;

INSERT INTO curriculum_item_progress (learner_id, item_id, status, started_at, completed_at) VALUES
    ('30000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000629', 'COMPLETED', now() - interval '6 days', now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000630', 'UNLOCKED', now() - interval '5 days', NULL),
    ('30000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000625', 'UNLOCKED', now() - interval '4 days', NULL)
ON CONFLICT (learner_id, item_id) DO NOTHING;

-- 14. Missions, Achievements, Gamification Balances, Entitlement Audit, 7-day Analytics & Tutor Telemetry
INSERT INTO mission_definition (id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled) VALUES
    ('10000000-0000-0000-0000-000000000011', 'DAILY_SHADOWING_3', 'Luyện phát âm 3 câu Shadowing', 'SHADOWING_SENTENCE', 3, 25, 3, 'DAILY', 'dev-v1', true),
    ('10000000-0000-0000-0000-000000000012', 'WEEKLY_LESSON_3', 'Hoàn thành 3 bài học trong tuần', 'LESSON_COMPLETED', 3, 60, 8, 'WEEKLY', 'dev-v1', true),
    ('10000000-0000-0000-0000-000000000013', 'WEEKLY_TOEIC_DRILL_2', 'Luyện 2 lượt TOEIC Drill trong tuần', 'TOEIC_DRILL_COMPLETED', 2, 75, 10, 'WEEKLY', 'dev-v1', true),
    ('10000000-0000-0000-0000-000000000014', 'WEEKLY_VOCAB_50', 'Ôn tập 50 lượt thẻ từ vựng', 'VOCABULARY_REVIEW', 50, 50, 5, 'WEEKLY', 'dev-v1', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO achievement_definition (
    id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
) VALUES
    ('40000000-0000-0000-0000-000000000011', 'TEN_LESSONS', 'Kiên trì 10 bài học', 'Hoàn thành 10 bài học khác nhau trên Lyreo', 'DISTINCT_LESSON_COMPLETED', 10, 120, 15, 'dev-v1', true),
    ('40000000-0000-0000-0000-000000000012', 'STREAK_7_DAYS', 'Bền bỉ 7 ngày liên tiếp', 'Giữ chuỗi học tập 7 ngày liên tiếp không gián đoạn', 'STREAK_DAYS', 7, 80, 10, 'dev-v1', true),
    ('40000000-0000-0000-0000-000000000013', 'STREAK_14_DAYS', 'Thói quen 14 ngày', 'Duy trì học đều đặn suốt hai tuần', 'STREAK_DAYS', 14, 150, 20, 'dev-v1', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO learner_streak (learner_id, current_streak, longest_streak, last_study_date) VALUES
    ('30000000-0000-0000-0000-000000000004', 6, 8, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000005', 3, 4, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000006', 7, 14, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000007', 1, 2, (now() AT TIME ZONE 'UTC')::date - 1)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO learner_level (learner_id, xp, level) VALUES
    ('30000000-0000-0000-0000-000000000004', 340, 4),
    ('30000000-0000-0000-0000-000000000005', 145, 2),
    ('30000000-0000-0000-0000-000000000006', 620, 7),
    ('30000000-0000-0000-0000-000000000007', 85, 1)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_wallet (learner_id, cached_balance) VALUES
    ('30000000-0000-0000-0000-000000000004', 42),
    ('30000000-0000-0000-0000-000000000005', 14),
    ('30000000-0000-0000-0000-000000000006', 85),
    ('30000000-0000-0000-0000-000000000007', 6)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_transaction (
    id, learner_id, transaction_type, amount, idempotency_key, reference_type, reference_id, reason, rule_version, created_at
) VALUES
    ('50000000-0000-0000-0000-000000000721', '30000000-0000-0000-0000-000000000004', 'MISSION_REWARD', 8, 'demo:mission:0004:weekly-lesson', 'MISSION', '10000000-0000-0000-0000-000000000012', 'Hoàn thành nhiệm vụ tuần 3 bài học', 'dev-v1', now() - interval '2 days'),
    ('50000000-0000-0000-0000-000000000722', '30000000-0000-0000-0000-000000000004', 'ADMIN_ADJUSTMENT', 34, 'demo:admin:0004:onboarding-grant', 'ADMIN_ADJUSTMENT', NULL, 'Thưởng học viên tích cực lộ trình B2', 'dev-v1', now() - interval '1 day'),
    ('50000000-0000-0000-0000-000000000723', '30000000-0000-0000-0000-000000000006', 'ACHIEVEMENT_REWARD', 10, 'demo:ach:0006:streak7', 'ACHIEVEMENT', '40000000-0000-0000-0000-000000000012', 'Mở khóa thành tích Chuỗi 7 ngày liên tiếp', 'dev-v1', now() - interval '1 day')
ON CONFLICT (idempotency_key) DO NOTHING;

INSERT INTO entitlement_audit_log (
    id, user_id, feature_key, operation_id, decision, policy_version, detail_json, occurred_at
) VALUES
    ('50000000-0000-0000-0000-000000000801', '30000000-0000-0000-0000-000000000004', 'vocabulary.plus-packs', NULL, 'GRANT_CREATED', 'dev-v1', '{"sourceType":"ADMIN_MANUAL","createdBy":"seed-demo"}'::jsonb, now() - interval '2 days'),
    ('50000000-0000-0000-0000-000000000802', '30000000-0000-0000-0000-000000000006', 'toeic.full-access', NULL, 'GRANT_CREATED', 'dev-v1', '{"sourceType":"ADMIN_MANUAL","createdBy":"seed-demo"}'::jsonb, now() - interval '1 day'),
    ('50000000-0000-0000-0000-000000000803', '30000000-0000-0000-0000-000000000007', 'speech.shadowing', NULL, 'GRANT_REVOKED', 'dev-v1', '{"reason":"Hết thời gian trải nghiệm thử"}'::jsonb, now() - interval '6 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learner_daily_activity (
    learner_id, activity_date, study_seconds, lesson_count, vocabulary_review_count, grammar_answer_count, toeic_question_count
) VALUES
    ('30000000-0000-0000-0000-000000000004', (now() AT TIME ZONE 'UTC')::date - 5, 1420, 1, 14, 6, 0),
    ('30000000-0000-0000-0000-000000000004', (now() AT TIME ZONE 'UTC')::date - 3, 1860, 2, 18, 10, 0),
    ('30000000-0000-0000-0000-000000000004', (now() AT TIME ZONE 'UTC')::date - 1, 1650, 1, 22, 8, 15),
    ('30000000-0000-0000-0000-000000000004', (now() AT TIME ZONE 'UTC')::date,     1120, 1, 12, 5, 0),
    ('30000000-0000-0000-0000-000000000005', (now() AT TIME ZONE 'UTC')::date - 2, 780,  1, 9,  4, 0),
    ('30000000-0000-0000-0000-000000000005', (now() AT TIME ZONE 'UTC')::date,     640,  0, 11, 3, 0),
    ('30000000-0000-0000-0000-000000000006', (now() AT TIME ZONE 'UTC')::date - 4, 2400, 1, 20, 18, 60),
    ('30000000-0000-0000-0000-000000000006', (now() AT TIME ZONE 'UTC')::date - 1, 2150, 1, 16, 12, 50),
    ('30000000-0000-0000-0000-000000000006', (now() AT TIME ZONE 'UTC')::date,     1540, 0, 15, 10, 30),
    ('30000000-0000-0000-0000-000000000007', (now() AT TIME ZONE 'UTC')::date - 1, 540,  0, 5,  6, 0)
ON CONFLICT (learner_id, activity_date) DO NOTHING;

INSERT INTO learner_skill_summary (learner_id, skill_code, score, sample_count, updated_at) VALUES
    ('30000000-0000-0000-0000-000000000004', 'LISTENING', 84.50, 12, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000004', 'SPEAKING', 79.00, 9, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000004', 'VOCABULARY', 88.20, 32, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000005', 'VOCABULARY', 74.00, 14, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_LISTENING', 89.00, 4, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_READING', 82.50, 4, now() - interval '5 hours'),
    ('30000000-0000-0000-0000-000000000006', 'GRAMMAR', 91.00, 28, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR', 62.50, 6, now() - interval '1 day')
ON CONFLICT (learner_id, skill_code) DO NOTHING;

INSERT INTO learner_weakness (learner_id, weakness_type, weakness_key, severity, sample_count, updated_at) VALUES
    ('30000000-0000-0000-0000-000000000004', 'LESSON_ACTIVITY', 'SHADOWING', 28.00, 6, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000005', 'LESSON_ACTIVITY', 'DICTATION', 34.50, 5, now() - interval '5 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_SECTION', 'READING', 21.00, 4, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR_TOPIC', '60000000-0000-0000-0000-000000000101', 42.00, 6, now() - interval '1 day')
ON CONFLICT (learner_id, weakness_type, weakness_key) DO NOTHING;

INSERT INTO chat_conversation (id, learner_id, title, created_at, updated_at) VALUES
    ('50000000-0000-0000-0000-000000000901', '30000000-0000-0000-0000-000000000004', 'Standup past tense phrasing', now() - interval '2 days', now() - interval '1 day'),
    ('50000000-0000-0000-0000-000000000902', '30000000-0000-0000-0000-000000000006', 'TOEIC Part 7 inference strategy', now() - interval '5 hours', now() - interval '4 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO chat_message (id, conversation_id, role, content, ai_invocation_id, created_at) VALUES
    ('50000000-0000-0000-0000-000000000911', '50000000-0000-0000-0000-000000000901', 'USER', 'How should I summarize completed tasks during daily standup?', NULL, now() - interval '2 days'),
    ('50000000-0000-0000-0000-000000000912', '50000000-0000-0000-0000-000000000901', 'ASSISTANT', 'Use clear past simple verbs such as shipped, verified, or resolved, then state today''s next deliverable concisely.', NULL, now() - interval '2 days' + interval '2 seconds'),
    ('50000000-0000-0000-0000-000000000913', '50000000-0000-0000-0000-000000000902', 'USER', 'What clues signal an inference question in TOEIC Part 7?', NULL, now() - interval '5 hours'),
    ('50000000-0000-0000-0000-000000000914', '50000000-0000-0000-0000-000000000902', 'ASSISTANT', 'Look for phrases like "What is implied" or "suggested about", then paraphrase evidence across matching emails or notices.', NULL, now() - interval '5 hours' + interval '2 seconds')
ON CONFLICT (id) DO NOTHING;

COMMIT;
