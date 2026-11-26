-- Lyreo Development Seed (Clean Real-Data + Rich Experience Upgrade)
-- Idempotent: purges legacy fake demo-* vocabulary/short-lesson fixtures,
-- preserves imported datasets (Vocabulary 1.3.0, Grammar/TOEIC 2.0.12) and authored lessons,
-- and seeds covers, badge artwork, announcements, missions, and curated paths.
BEGIN;
SET LOCAL lock_timeout = '10s';

-- =============================================================================
-- 1. Purge polluted legacy fake demo data (keep real datasets and authored lessons)
-- =============================================================================
DELETE FROM curriculum_item_progress
WHERE item_id IN (SELECT id FROM curriculum_item WHERE content_type = 'SPEAKING_SCENARIO');
DELETE FROM curriculum_item WHERE content_type = 'SPEAKING_SCENARIO';
DELETE FROM speaking_turn;
DELETE FROM speaking_session;
DELETE FROM speaking_scenario;
DELETE FROM user_entitlement_grant WHERE feature_key = 'speaking.roleplay';
DELETE FROM entitlement_quota_policy WHERE feature_key = 'speaking.roleplay';
DELETE FROM content_asset WHERE id IN (
    '70000000-0000-0000-0000-000000000304',
    '70000000-0000-0000-0000-000000000305'
);

DELETE FROM curriculum_item_progress
WHERE item_id IN (
    SELECT ci.id FROM curriculum_item ci
    JOIN curriculum_section cs ON cs.id = ci.section_id
    WHERE cs.path_id IN (
        '50000000-0000-0000-0000-000000000601',
        '50000000-0000-0000-0000-000000000602',
        '50000000-0000-0000-0000-000000000603',
        '50000000-0000-0000-0000-000000000604'
    )
);
DELETE FROM curriculum_enrollment
WHERE path_id IN (
    '50000000-0000-0000-0000-000000000601',
    '50000000-0000-0000-0000-000000000602',
    '50000000-0000-0000-0000-000000000603',
    '50000000-0000-0000-0000-000000000604'
);
DELETE FROM curriculum_path
WHERE id IN (
    '50000000-0000-0000-0000-000000000601',
    '50000000-0000-0000-0000-000000000602',
    '50000000-0000-0000-0000-000000000603',
    '50000000-0000-0000-0000-000000000604'
);

-- Purge fake demo-1.0.0 vocabulary release & cards pointing to demo entries
DELETE FROM vocabulary_collection_item
WHERE card_id IN (
    SELECT id FROM vocabulary_card
    WHERE source_entry_id IN (
        SELECT e.id FROM vocabulary_unit_entry e
        JOIN vocabulary_unit u ON u.id = e.unit_id
        JOIN vocabulary_pack p ON p.id = u.pack_id
        WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
    )
    OR source_context_id IN (
        SELECT u.id FROM vocabulary_unit u
        JOIN vocabulary_pack p ON p.id = u.pack_id
        WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
    )
);
DELETE FROM vocabulary_review
WHERE card_id IN (
    SELECT id FROM vocabulary_card
    WHERE source_entry_id IN (
        SELECT e.id FROM vocabulary_unit_entry e
        JOIN vocabulary_unit u ON u.id = e.unit_id
        JOIN vocabulary_pack p ON p.id = u.pack_id
        WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
    )
    OR source_context_id IN (
        SELECT u.id FROM vocabulary_unit u
        JOIN vocabulary_pack p ON p.id = u.pack_id
        WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
    )
);
DELETE FROM vocabulary_card
WHERE source_entry_id IN (
    SELECT e.id FROM vocabulary_unit_entry e
    JOIN vocabulary_unit u ON u.id = e.unit_id
    JOIN vocabulary_pack p ON p.id = u.pack_id
    WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
)
OR source_context_id IN (
    SELECT u.id FROM vocabulary_unit u
    JOIN vocabulary_pack p ON p.id = u.pack_id
    WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
);
DELETE FROM vocabulary_entry_progress
WHERE entry_id IN (
    SELECT e.id FROM vocabulary_unit_entry e
    JOIN vocabulary_unit u ON u.id = e.unit_id
    JOIN vocabulary_pack p ON p.id = u.pack_id
    WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
);
DELETE FROM vocabulary_study_session
WHERE unit_id IN (
    SELECT u.id FROM vocabulary_unit u
    JOIN vocabulary_pack p ON p.id = u.pack_id
    WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
);
DELETE FROM vocabulary_release_lineage WHERE release_id = '50000000-0000-0000-0000-000000000201';
DELETE FROM vocabulary_unit_entry
WHERE unit_id IN (
    SELECT u.id FROM vocabulary_unit u
    JOIN vocabulary_pack p ON p.id = u.pack_id
    WHERE p.release_id = '50000000-0000-0000-0000-000000000201'
);
DELETE FROM vocabulary_unit
WHERE pack_id IN (SELECT id FROM vocabulary_pack WHERE release_id = '50000000-0000-0000-0000-000000000201');
DELETE FROM vocabulary_pack WHERE release_id = '50000000-0000-0000-0000-000000000201';

-- Soft-delete legacy short audio/text test fixtures (respecting published lesson immutability trigger)
UPDATE lesson_metadata
SET deleted_at = COALESCE(deleted_at, now()),
    visible = FALSE,
    updated_at = now()
WHERE lesson_id IN (
    '50000000-0000-0000-0000-000000000401', '50000000-0000-0000-0000-000000000402',
    '50000000-0000-0000-0000-000000000403', '50000000-0000-0000-0000-000000000404',
    '50000000-0000-0000-0000-000000000405',
    'b79dc408-d005-4325-8278-1ac1061ae2a5', 'eec5f1fa-3865-4433-9613-97ee7cefce6f',
    '528d6f69-e27b-4176-b00f-1f7be7af6d60'
);

-- Archive any old demo-2.0.12 grammar/toeic catalog rows (immutable snapshots are preserved safely)
UPDATE grammar_topic_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE grammar_subtopic_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE grammar_bank_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE toeic_test_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';

-- Remove legacy unbadged/duplicate missions, achievements, and placeholder demo media
DELETE FROM mission_progress
WHERE mission_id IN (
    SELECT id FROM mission_definition
    WHERE code IN (
        'DAILY_GRAMMAR_5', 'DAILY_LESSON', 'DAILY_SHADOWING_3', 'DAILY_TOEIC',
        'DAILY_VOCAB_10', 'WEEKLY_LESSON_3', 'WEEKLY_TOEIC_DRILL_2', 'WEEKLY_VOCAB_50'
    )
);
DELETE FROM mission_definition
WHERE code IN (
    'DAILY_GRAMMAR_5', 'DAILY_LESSON', 'DAILY_SHADOWING_3', 'DAILY_TOEIC',
    'DAILY_VOCAB_10', 'WEEKLY_LESSON_3', 'WEEKLY_TOEIC_DRILL_2', 'WEEKLY_VOCAB_50'
);

DELETE FROM learner_achievement
WHERE achievement_id IN (
    SELECT id FROM achievement_definition
    WHERE code IN ('FIRST_LESSON', 'FIVE_LESSONS', 'STREAK_3_DAYS', 'TEN_LESSONS', 'STREAK_7_DAYS', 'STREAK_14_DAYS')
);
DELETE FROM achievement_definition
WHERE code IN ('FIRST_LESSON', 'FIVE_LESSONS', 'STREAK_3_DAYS', 'TEN_LESSONS', 'STREAK_7_DAYS', 'STREAK_14_DAYS');

DELETE FROM content_asset
WHERE id IN (
    '50000000-0000-0000-0000-000000000101',
    '50000000-0000-0000-0000-000000000102',
    '50000000-0000-0000-0000-000000000109',
    '50000000-0000-0000-0000-000000000110',
    '50000000-0000-0000-0000-000000000111',
    '50000000-0000-0000-0000-000000000112'
);
UPDATE content_asset SET created_by = 'system' WHERE created_by = 'seed-demo';

-- =============================================================================
-- 2. Learners Cohort (24 profiles) & Active Free vs Plus Entitlements
-- =============================================================================
INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES
    ('30000000-0000-0000-0000-000000000001', 'lyreo-learner-minh-anh', 'minh.anh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000002', 'lyreo-learner-gia-huy', 'gia.huy@lyreo.app'),
    ('30000000-0000-0000-0000-000000000003', 'lyreo-learner-tuan-kiet', 'tuan.kiet@lyreo.app'),
    ('30000000-0000-0000-0000-000000000004', 'lyreo-learner-thao-vy', 'thao.vy@lyreo.app'),
    ('30000000-0000-0000-0000-000000000005', 'lyreo-learner-hoang-nam', 'hoang.nam@lyreo.app'),
    ('30000000-0000-0000-0000-000000000006', 'lyreo-learner-khanh-linh', 'khanh.linh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000007', 'lyreo-learner-quoc-bao', 'quoc.bao@lyreo.app'),
    ('30000000-0000-0000-0000-000000000008', 'lyreo-learner-mai-phuong', 'mai.phuong@lyreo.app'),
    ('30000000-0000-0000-0000-000000000009', 'lyreo-learner-duc-minh', 'duc.minh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000010', 'lyreo-learner-ngoc-han', 'ngoc.han@lyreo.app'),
    ('30000000-0000-0000-0000-000000000011', 'lyreo-learner-anh-khoa', 'anh.khoa@lyreo.app'),
    ('30000000-0000-0000-0000-000000000012', 'lyreo-learner-bao-ngoc', 'bao.ngoc@lyreo.app'),
    ('30000000-0000-0000-0000-000000000013', 'lyreo-learner-trung-hieu', 'trung.hieu@lyreo.app'),
    ('30000000-0000-0000-0000-000000000014', 'lyreo-learner-thanh-truc', 'thanh.truc@lyreo.app'),
    ('30000000-0000-0000-0000-000000000015', 'lyreo-learner-nhat-quang', 'nhat.quang@lyreo.app'),
    ('30000000-0000-0000-0000-000000000016', 'lyreo-learner-phuong-anh', 'phuong.anh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000017', 'lyreo-learner-hai-dang', 'hai.dang@lyreo.app'),
    ('30000000-0000-0000-0000-000000000018', 'lyreo-learner-lan-huong', 'lan.huong@lyreo.app'),
    ('30000000-0000-0000-0000-000000000019', 'lyreo-learner-tien-dat', 'tien.dat@lyreo.app'),
    ('30000000-0000-0000-0000-000000000020', 'lyreo-learner-kieu-oanh', 'kieu.oanh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000021', 'lyreo-learner-trong-nhan', 'trong.nhan@lyreo.app'),
    ('30000000-0000-0000-0000-000000000022', 'lyreo-learner-my-duyen', 'my.duyen@lyreo.app'),
    ('30000000-0000-0000-0000-000000000023', 'lyreo-learner-huu-phuc', 'huu.phuc@lyreo.app'),
    ('30000000-0000-0000-0000-000000000024', 'lyreo-learner-thuy-tien', 'thuy.tien@lyreo.app')
ON CONFLICT (id) DO UPDATE SET
    keycloak_subject = EXCLUDED.keycloak_subject,
    email_snapshot = EXCLUDED.email_snapshot;

INSERT INTO learner_profile (
    learner_id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at
)
SELECT v.id, v.display_name, v.goal, v.current_level, v.daily_minutes, v.focus_area, v.avatar_preset, v.first_seen_at, v.onboarding_completed_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, 'Nguyễn Minh Anh', 'CONVERSATION', 'A2', 15, 'LISTENING', 'COMIC_ARI', now() - interval '10 days', now() - interval '10 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000002'::uuid, 'Trần Gia Huy', 'TOEIC', 'B1', 20, 'VOCABULARY', 'KOALA', now() - interval '6 days', now() - interval '6 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000003'::uuid, 'Lê Tuấn Kiệt', NULL, NULL, NULL, NULL, NULL, now() - interval '30 minutes', NULL),
    ('30000000-0000-0000-0000-000000000004'::uuid, 'Trần Thảo Vy', 'WORK', 'B2', 30, 'SPEAKING', 'COMIC_LUNA', now() - interval '14 days', now() - interval '14 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000005'::uuid, 'Lê Hoàng Nam', 'TRAVEL', 'A1', 15, 'VOCABULARY', 'TOUCAN', now() - interval '9 days', now() - interval '9 days' + interval '3 minutes'),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'Phạm Khánh Linh', 'EXAM', 'C1', 45, 'READING', 'FLOWER_IRISES', now() - interval '21 days', now() - interval '21 days' + interval '6 minutes'),
    ('30000000-0000-0000-0000-000000000007'::uuid, 'Đỗ Quốc Bảo', 'CONVERSATION', 'B1', 20, 'GRAMMAR', 'RED_PANDA', now() - interval '5 days', now() - interval '5 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000008'::uuid, 'Vũ Mai Phương', NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', NULL),
    ('30000000-0000-0000-0000-000000000009'::uuid, 'Hoàng Đức Minh', 'WORK', 'B1', 25, 'SPEAKING', 'PANDA', now() - interval '12 days', now() - interval '12 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000010'::uuid, 'Bùi Ngọc Hân', 'CONVERSATION', 'A2', 15, 'LISTENING', 'FLOWER_HEATHER', now() - interval '8 days', now() - interval '8 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000011'::uuid, 'Đặng Anh Khoa', 'TOEIC', 'B2', 30, 'LISTENING', 'LION', now() - interval '16 days', now() - interval '16 days' + interval '3 minutes'),
    ('30000000-0000-0000-0000-000000000012'::uuid, 'Ngô Bảo Ngọc', 'TRAVEL', 'A1', 10, 'VOCABULARY', 'PEEP_BUN', now() - interval '4 days', now() - interval '4 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000013'::uuid, 'Võ Trung Hiếu', 'EXAM', 'B1', 30, 'GRAMMAR', 'BEAR', now() - interval '11 days', now() - interval '11 days' + interval '6 minutes'),
    ('30000000-0000-0000-0000-000000000014'::uuid, 'Huỳnh Thanh Trúc', 'WORK', 'B2', 20, 'SPEAKING', 'COMIC_NOVA', now() - interval '15 days', now() - interval '15 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000015'::uuid, 'Phan Nhật Quang', 'TOEIC', 'A2', 20, 'READING', 'PENGUIN', now() - interval '7 days', now() - interval '7 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000016'::uuid, 'Dương Phương Anh', 'CONVERSATION', 'B1', 15, 'SPEAKING', 'FLOWER_CLIMBING_LILY', now() - interval '13 days', now() - interval '13 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000017'::uuid, 'Lý Hải Đăng', NULL, NULL, NULL, NULL, NULL, now() - interval '45 minutes', NULL),
    ('30000000-0000-0000-0000-000000000018'::uuid, 'Tạ Lan Hương', 'WORK', 'C1', 35, 'SPEAKING', 'COMIC_MIKA', now() - interval '19 days', now() - interval '19 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000019'::uuid, 'Trịnh Tiến Đạt', 'TOEIC', 'B1', 25, 'GRAMMAR', 'ORCA', now() - interval '17 days', now() - interval '17 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000020'::uuid, 'Đinh Kiều Oanh', 'TRAVEL', 'A2', 15, 'LISTENING', 'PEEP_CURLS', now() - interval '3 days', now() - interval '3 days' + interval '3 minutes'),
    ('30000000-0000-0000-0000-000000000021'::uuid, 'Cao Trọng Nhân', 'EXAM', 'B2', 40, 'READING', 'COMIC_KYO', now() - interval '18 days', now() - interval '18 days' + interval '6 minutes'),
    ('30000000-0000-0000-0000-000000000022'::uuid, 'Mai Mỹ Duyên', 'CONVERSATION', 'A1', 10, 'LISTENING', 'GIRAFFE', now() - interval '2 days', now() - interval '2 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000023'::uuid, 'Tô Hữu Phúc', 'WORK', 'B1', 20, 'VOCABULARY', 'PEEP_BEANIE', now() - interval '20 days', now() - interval '20 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000024'::uuid, 'Hồ Thủy Tiên', NULL, NULL, NULL, NULL, NULL, now() - interval '15 minutes', NULL)
) AS v(id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.id)
ON CONFLICT (learner_id) DO UPDATE SET
    display_name = EXCLUDED.display_name;

INSERT INTO user_entitlement_grant (
    id, user_id, feature_key, status, valid_from, valid_until, source_type, source_reference, created_by
) VALUES
    ('50000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000001', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000012', '30000000-0000-0000-0000-000000000001', 'vocabulary.saved-cards', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000001', 'speech.shadowing', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000014', '30000000-0000-0000-0000-000000000001', 'toeic.full-access', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000015', '30000000-0000-0000-0000-000000000001', 'grammar.advanced', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000016', '30000000-0000-0000-0000-000000000001', 'chat.tutor', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000017', '30000000-0000-0000-0000-000000000001', 'lesson.plus-lessons', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000021', '30000000-0000-0000-0000-000000000002', 'toeic.full-access', 'ACTIVE', now() - interval '30 days', now() - interval '1 day', 'ADMIN_MANUAL', 'lyreo-trial-expired', 'system'),
    ('50000000-0000-0000-0000-000000000031', '30000000-0000-0000-0000-000000000004', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'system'),
    ('50000000-0000-0000-0000-000000000032', '30000000-0000-0000-0000-000000000004', 'lesson.plus-lessons', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'system'),
    ('50000000-0000-0000-0000-000000000034', '30000000-0000-0000-0000-000000000006', 'toeic.full-access', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'system'),
    ('50000000-0000-0000-0000-000000000035', '30000000-0000-0000-0000-000000000006', 'grammar.advanced', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'system'),
    ('50000000-0000-0000-0000-000000000036', '30000000-0000-0000-0000-000000000011', 'toeic.full-access', 'ACTIVE', now() - interval '10 days', now() + interval '180 days', 'ADMIN_MANUAL', 'toeic-800-cohort', 'system'),
    ('50000000-0000-0000-0000-000000000038', '30000000-0000-0000-0000-000000000018', 'lesson.plus-lessons', 'ACTIVE', now() - interval '12 days', now() + interval '365 days', 'ADMIN_MANUAL', 'lyreo-plus-annual', 'system'),
    ('50000000-0000-0000-0000-000000000039', '30000000-0000-0000-0000-000000000021', 'toeic.full-access', 'ACTIVE', now() - interval '15 days', now() + interval '180 days', 'ADMIN_MANUAL', 'toeic-800-cohort', 'system')
ON CONFLICT (id) DO UPDATE SET
    source_type = EXCLUDED.source_type,
    source_reference = EXCLUDED.source_reference,
    created_by = EXCLUDED.created_by;

-- =============================================================================
-- 3. Deterministic Seed Media Artwork (Covers, Badges, Editorial WebPs)
-- =============================================================================
INSERT INTO media_blob (sha256, mime_type, size_bytes, storage_object_key) VALUES
    ('95af109e9aefebda0e8e3c44700cefc790304020186429f744229a6cf07e98a9', 'image/webp', 43836, 'media/sha256/95/95af109e9aefebda0e8e3c44700cefc790304020186429f744229a6cf07e98a9'),
    ('3b4e421a07845912cabc87d806e830b148c4d33f0fb5f76ef5dcc5ca18187c85', 'image/webp', 27816, 'media/sha256/3b/3b4e421a07845912cabc87d806e830b148c4d33f0fb5f76ef5dcc5ca18187c85'),
    ('0130fed06ebe68c4110b88c8bf9cb577062a89213f6d560c96661506b68a3afd', 'image/webp', 15120, 'media/sha256/01/0130fed06ebe68c4110b88c8bf9cb577062a89213f6d560c96661506b68a3afd'),
    ('d9b78a39750a6f027999dae54d2a3bddf800621d28e4a76161fd19d174d155ae', 'image/webp', 6846,  'media/sha256/d9/d9b78a39750a6f027999dae54d2a3bddf800621d28e4a76161fd19d174d155ae'),
    ('b5708202b355cd71b5c9a609beb8c30d19f0001e033a312bf5ab02d00075e2d9', 'image/webp', 10396, 'media/sha256/b5/b5708202b355cd71b5c9a609beb8c30d19f0001e033a312bf5ab02d00075e2d9'),
    ('dd6dae710db83c5dce6add3dc0d28ee6cfc2abcf6068c942b9a219f002838b7a', 'image/webp', 11992, 'media/sha256/dd/dd6dae710db83c5dce6add3dc0d28ee6cfc2abcf6068c942b9a219f002838b7a'),
    ('6ffda9695d8d4bd8e9357f2f479fdbe4663c2de400a9c704faefb2a2b0021876', 'image/webp', 12062, 'media/sha256/6f/6ffda9695d8d4bd8e9357f2f479fdbe4663c2de400a9c704faefb2a2b0021876'),
    ('31da509bb11aca4b7ef1929a56be9fa3746f15cad9821cf137214d5f2fd323f8', 'image/webp', 13346, 'media/sha256/31/31da509bb11aca4b7ef1929a56be9fa3746f15cad9821cf137214d5f2fd323f8'),
    ('aa9ee4fb1de027dcee02170c0e95e627c6a207ed4445ca30b6e74d5b7034b3eb', 'image/webp', 11312, 'media/sha256/aa/aa9ee4fb1de027dcee02170c0e95e627c6a207ed4445ca30b6e74d5b7034b3eb'),
    ('bf6bb71d50b1a93aafc10a129ea06c27636c5d86081ac2cef3a85ca95a93e978', 'image/webp', 13014, 'media/sha256/bf/bf6bb71d50b1a93aafc10a129ea06c27636c5d86081ac2cef3a85ca95a93e978'),
    ('2d057b6de3ad294978a1a22f0b68d12fa30406bbc837635871ee86f4a8842763', 'image/webp', 12318, 'media/sha256/2d/2d057b6de3ad294978a1a22f0b68d12fa30406bbc837635871ee86f4a8842763'),
    ('178b5c3f4ddfd879df2da34a60800a3d7105b33d79ce14ee58dbff912b144f2c', 'image/webp', 10056, 'media/sha256/17/178b5c3f4ddfd879df2da34a60800a3d7105b33d79ce14ee58dbff912b144f2c'),
    ('92aa3e14050fc165716ec561bc1f63cee834d62204199b635fe6ee8155e29b55', 'image/webp', 11080, 'media/sha256/92/92aa3e14050fc165716ec561bc1f63cee834d62204199b635fe6ee8155e29b55'),
    ('5cd7aad63cc1af67c456d7771e0ff7800d43e7320dda65ac8c6ebdaf19fb539d', 'image/webp', 12306, 'media/sha256/5c/5cd7aad63cc1af67c456d7771e0ff7800d43e7320dda65ac8c6ebdaf19fb539d'),
    ('fc8037d6e8ea13e689af1de97540d83d7bd3ee87eeac5d59e4e4430c2f896423', 'image/webp', 10648, 'media/sha256/fc/fc8037d6e8ea13e689af1de97540d83d7bd3ee87eeac5d59e4e4430c2f896423'),
    ('68c024d750b2f6a4a35adbc51d19c91821de25389ba29af73c18d3f2fa94fdaf', 'image/webp', 11668, 'media/sha256/68/68c024d750b2f6a4a35adbc51d19c91821de25389ba29af73c18d3f2fa94fdaf'),
    ('0e86c6790f9458e19ff5f239ff4a9c9160ee881e3855c117aa6ec8525041a3fe', 'image/webp', 10988, 'media/sha256/0e/0e86c6790f9458e19ff5f239ff4a9c9160ee881e3855c117aa6ec8525041a3fe'),
    ('eef7bba38d87645e82cd978dbd12a0c2778fa5e138e31463caa5108fad8860d8', 'image/webp', 13584, 'media/sha256/ee/eef7bba38d87645e82cd978dbd12a0c2778fa5e138e31463caa5108fad8860d8'),
    ('bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78'),
    ('cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6'),
    ('c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04'),
    ('3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9')
ON CONFLICT (sha256) DO NOTHING;

INSERT INTO content_asset (id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by) VALUES
    ('70000000-0000-0000-0000-000000000111', '95af109e9aefebda0e8e3c44700cefc790304020186429f744229a6cf07e98a9', 'media/sha256/95/95af109e9aefebda0e8e3c44700cefc790304020186429f744229a6cf07e98a9', 'image/webp', 43836, 'path-cover-realworld.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000112', '3b4e421a07845912cabc87d806e830b148c4d33f0fb5f76ef5dcc5ca18187c85', 'media/sha256/3b/3b4e421a07845912cabc87d806e830b148c4d33f0fb5f76ef5dcc5ca18187c85', 'image/webp', 27816, 'path-cover-workplace.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000113', '0130fed06ebe68c4110b88c8bf9cb577062a89213f6d560c96661506b68a3afd', 'media/sha256/01/0130fed06ebe68c4110b88c8bf9cb577062a89213f6d560c96661506b68a3afd', 'image/webp', 15120, 'path-cover-toeic.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000114', 'd9b78a39750a6f027999dae54d2a3bddf800621d28e4a76161fd19d174d155ae', 'media/sha256/d9/d9b78a39750a6f027999dae54d2a3bddf800621d28e4a76161fd19d174d155ae', 'image/webp', 6846,  'path-cover-toeic-pro.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000201', 'b5708202b355cd71b5c9a609beb8c30d19f0001e033a312bf5ab02d00075e2d9', 'media/sha256/b5/b5708202b355cd71b5c9a609beb8c30d19f0001e033a312bf5ab02d00075e2d9', 'image/webp', 10396, 'badge-streak-7.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000202', 'dd6dae710db83c5dce6add3dc0d28ee6cfc2abcf6068c942b9a219f002838b7a', 'media/sha256/dd/dd6dae710db83c5dce6add3dc0d28ee6cfc2abcf6068c942b9a219f002838b7a', 'image/webp', 11992, 'badge-streak-30.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000203', '6ffda9695d8d4bd8e9357f2f479fdbe4663c2de400a9c704faefb2a2b0021876', 'media/sha256/6f/6ffda9695d8d4bd8e9357f2f479fdbe4663c2de400a9c704faefb2a2b0021876', 'image/webp', 12062, 'badge-streak-100.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000204', '31da509bb11aca4b7ef1929a56be9fa3746f15cad9821cf137214d5f2fd323f8', 'media/sha256/31/31da509bb11aca4b7ef1929a56be9fa3746f15cad9821cf137214d5f2fd323f8', 'image/webp', 13346, 'badge-streak-365.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000205', 'aa9ee4fb1de027dcee02170c0e95e627c6a207ed4445ca30b6e74d5b7034b3eb', 'media/sha256/aa/aa9ee4fb1de027dcee02170c0e95e627c6a207ed4445ca30b6e74d5b7034b3eb', 'image/webp', 11312, 'badge-lessons-10.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000206', 'bf6bb71d50b1a93aafc10a129ea06c27636c5d86081ac2cef3a85ca95a93e978', 'media/sha256/bf/bf6bb71d50b1a93aafc10a129ea06c27636c5d86081ac2cef3a85ca95a93e978', 'image/webp', 13014, 'badge-lessons-50.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000207', '2d057b6de3ad294978a1a22f0b68d12fa30406bbc837635871ee86f4a8842763', 'media/sha256/2d/2d057b6de3ad294978a1a22f0b68d12fa30406bbc837635871ee86f4a8842763', 'image/webp', 12318, 'badge-lessons-150.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000208', '178b5c3f4ddfd879df2da34a60800a3d7105b33d79ce14ee58dbff912b144f2c', 'media/sha256/17/178b5c3f4ddfd879df2da34a60800a3d7105b33d79ce14ee58dbff912b144f2c', 'image/webp', 10056, 'badge-vocab-100.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000209', '92aa3e14050fc165716ec561bc1f63cee834d62204199b635fe6ee8155e29b55', 'media/sha256/92/92aa3e14050fc165716ec561bc1f63cee834d62204199b635fe6ee8155e29b55', 'image/webp', 11080, 'badge-vocab-500.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000210', '5cd7aad63cc1af67c456d7771e0ff7800d43e7320dda65ac8c6ebdaf19fb539d', 'media/sha256/5c/5cd7aad63cc1af67c456d7771e0ff7800d43e7320dda65ac8c6ebdaf19fb539d', 'image/webp', 12306, 'badge-vocab-2500.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000211', 'fc8037d6e8ea13e689af1de97540d83d7bd3ee87eeac5d59e4e4430c2f896423', 'media/sha256/fc/fc8037d6e8ea13e689af1de97540d83d7bd3ee87eeac5d59e4e4430c2f896423', 'image/webp', 10648, 'badge-toeic-25.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000212', '68c024d750b2f6a4a35adbc51d19c91821de25389ba29af73c18d3f2fa94fdaf', 'media/sha256/68/68c024d750b2f6a4a35adbc51d19c91821de25389ba29af73c18d3f2fa94fdaf', 'image/webp', 11668, 'badge-toeic-100.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000213', '0e86c6790f9458e19ff5f239ff4a9c9160ee881e3855c117aa6ec8525041a3fe', 'media/sha256/0e/0e86c6790f9458e19ff5f239ff4a9c9160ee881e3855c117aa6ec8525041a3fe', 'image/webp', 10988, 'badge-shadowing-300.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000214', 'eef7bba38d87645e82cd978dbd12a0c2778fa5e138e31463caa5108fad8860d8', 'media/sha256/ee/eef7bba38d87645e82cd978dbd12a0c2778fa5e138e31463caa5108fad8860d8', 'image/webp', 13584, 'badge-grammar-500.webp', false, 'system'),
    ('50000000-0000-0000-0000-000000000105', 'c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'vocab-airport-boarding.webp', false, 'system'),
    ('50000000-0000-0000-0000-000000000106', 'bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'vocab-coffee-ordering.webp', false, 'system'),
    ('50000000-0000-0000-0000-000000000107', 'cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'vocab-office-standup.webp', false, 'system'),
    ('50000000-0000-0000-0000-000000000108', '3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'vocab-hotel-concierge.webp', false, 'system')
ON CONFLICT (id) DO UPDATE SET
    sha256 = EXCLUDED.sha256,
    storage_key = EXCLUDED.storage_key,
    mime_type = EXCLUDED.mime_type,
    size_bytes = EXCLUDED.size_bytes,
    filename = EXCLUDED.filename,
    created_by = EXCLUDED.created_by;

-- =============================================================================
-- 4. Tag Plus Access on Selected Real Imported Vocabulary Packs & Published Lessons
-- =============================================================================
UPDATE vocabulary_pack
SET access_mode = 'FEATURE',
    required_feature_key = 'vocabulary.plus-packs'
WHERE id IN (
    'd374d7cb-30ce-50dc-8ebd-b51ed42135f4', -- Technology
    '32b62455-b8c2-5fd9-b4fd-2bfd6e966f9f', -- Economy
    '152bb8be-f810-5950-a283-a436e2165025'  -- TOEIC Vocabulary
);

UPDATE lesson_metadata
SET access_mode = 'FEATURE',
    required_feature_key = 'lesson.plus-lessons',
    updated_at = now()
WHERE lesson_id IN (
    'c9f2b0da-c8a2-4636-8a14-8a1667957259', -- Where did English come from? (B2)
    'de2cc719-0f15-4f25-b845-5504fcdf1860'  -- Alice follows the White Rabbit (B2)
);

INSERT INTO lesson_category (id, name, translations) VALUES
    ('20000000-0000-0000-0000-000000000001', 'Daily conversation', '{"vi":"Giao tiếp hằng ngày"}'::jsonb),
    ('20000000-0000-0000-0000-000000000002', 'Workplace & Standups', '{"vi":"Công sở & Họp nhóm"}'::jsonb),
    ('20000000-0000-0000-0000-000000000003', 'Travel & Airport', '{"vi":"Du lịch & Sân bay"}'::jsonb),
    ('20000000-0000-0000-0000-000000000004', 'Daily Cafes & Dining', '{"vi":"Quán cà phê & Ẩm thực"}'::jsonb)
ON CONFLICT DO NOTHING;

-- =============================================================================
-- 5. Tiered Achievements with Profile Badges (14) + Enriched Missions (12) + XP Policy (Lv.99 Cap)
-- =============================================================================
INSERT INTO module_runtime_config (owner_module, config_key, schema_version, config_json, updated_at) VALUES
    ('gamification', 'reward-policy', 1, '{"ruleVersion":"v1","directLessonActivityDiamondsEnabled":false,"cutoverEffectiveAt":"2026-11-25T00:00:00Z","xpPerLevel":100,"xpGrowthPerLevel":25,"xpLevelMultiplier":1.03,"maxLevel":99,"milestoneInterval":5,"milestoneDiamondBase":15,"milestoneDiamondGrowth":10}'::jsonb, now())
ON CONFLICT (owner_module, config_key) DO UPDATE SET
    config_json = EXCLUDED.config_json,
    updated_at = now();

INSERT INTO achievement_definition (
    id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled,
    badge_asset_id, badge_tier, category
) VALUES
    ('40000000-0000-0000-0000-000000000101', 'STREAK_7', 'Nhịp học 7 ngày', 'Duy trì thói quen học tiếng Anh 7 ngày liên tiếp.', 'STREAK_DAYS', 7, 100, 10, 'v1', true, '70000000-0000-0000-0000-000000000201', 'BRONZE', 'STREAK'),
    ('40000000-0000-0000-0000-000000000102', 'STREAK_30', 'Ngọn lửa 30 ngày', 'Hoàn thành 30 ngày học liên tiếp không đứt quãng.', 'STREAK_DAYS', 30, 350, 35, 'v1', true, '70000000-0000-0000-0000-000000000202', 'SILVER', 'STREAK'),
    ('40000000-0000-0000-0000-000000000103', 'STREAK_100', 'Bậc thầy Kiên trì 100 ngày', 'Chiến binh học tập bền bỉ suốt 100 ngày liên tiếp.', 'STREAK_DAYS', 100, 1000, 100, 'v1', true, '70000000-0000-0000-0000-000000000203', 'GOLD', 'STREAK'),
    ('40000000-0000-0000-0000-000000000104', 'STREAK_365', 'Huyền thoại 365 ngày', 'Giữ chuỗi học tập trọn vẹn một năm dương lịch.', 'STREAK_DAYS', 365, 3650, 300, 'v1', true, '70000000-0000-0000-0000-000000000204', 'LEGENDARY', 'STREAK'),
    ('40000000-0000-0000-0000-000000000105', 'LESSONS_10', 'Người nghe Bền bỉ', 'Hoàn thành 10 bài nghe-chép chính tả hoặc shadowing khác nhau.', 'DISTINCT_LESSON_COMPLETED', 10, 150, 15, 'v1', true, '70000000-0000-0000-0000-000000000205', 'BRONZE', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000106', 'LESSONS_50', 'Giọng đọc Chuẩn xác', 'Hoàn thành 50 bài học với độ chính xác phát âm cao.', 'DISTINCT_LESSON_COMPLETED', 50, 600, 60, 'v1', true, '70000000-0000-0000-0000-000000000206', 'GOLD', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000107', 'LESSONS_150', 'Bậc thầy Âm thanh', 'Chinh phục 150 bài luyện nghe và shadowing.', 'DISTINCT_LESSON_COMPLETED', 150, 1800, 180, 'v1', true, '70000000-0000-0000-0000-000000000207', 'PLATINUM', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000108', 'VOCAB_100', 'Khởi đầu Vốn từ 100', 'Hoàn thành 100 lượt ôn tập thẻ từ vựng qua SRS.', 'VOCABULARY_REVIEW', 100, 120, 12, 'v1', true, '70000000-0000-0000-0000-000000000208', 'BRONZE', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000109', 'VOCAB_500', 'Người Sưu tầm Từ vựng', 'Hoàn thành 500 lượt ôn tập từ vựng trong hệ thống SRS.', 'VOCABULARY_REVIEW', 500, 450, 45, 'v1', true, '70000000-0000-0000-0000-000000000209', 'SILVER', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000110', 'VOCAB_2500', 'Từ điển Sống 2500', 'Đạt cột mốc 2.500 lượt ôn tập ghi nhớ sâu.', 'VOCABULARY_REVIEW', 2500, 1600, 160, 'v1', true, '70000000-0000-0000-0000-000000000210', 'GOLD', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000111', 'TOEIC_25', 'Chiến sĩ TOEIC Tập sự', 'Hoàn thành 25 lượt luyện tập TOEIC Drill hoặc Full Test.', 'TOEIC_DRILL_COMPLETED', 25, 400, 40, 'v1', true, '70000000-0000-0000-0000-000000000211', 'SILVER', 'TOEIC'),
    ('40000000-0000-0000-0000-000000000112', 'TOEIC_100', 'Chuyên gia Đề thi TOEIC', 'Hoàn thành 100 phiên luyện tập TOEIC có chấm điểm.', 'TOEIC_DRILL_COMPLETED', 100, 1400, 140, 'v1', true, '70000000-0000-0000-0000-000000000212', 'PLATINUM', 'TOEIC'),
    ('40000000-0000-0000-0000-000000000113', 'SHADOWING_300', 'Phát âm Tự nhiên', 'Luyện nói theo 300 câu hội thoại với phản hồi AI.', 'SHADOWING_SENTENCE', 300, 500, 50, 'v1', true, '70000000-0000-0000-0000-000000000213', 'SILVER', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000114', 'GRAMMAR_500', 'Kiến trúc sư Ngữ pháp', 'Giải đúng và hoàn thành 500 câu bài tập ngữ pháp.', 'GRAMMAR_ANSWER', 500, 750, 75, 'v1', true, '70000000-0000-0000-0000-000000000214', 'GOLD', 'MASTERY')
ON CONFLICT (code) DO UPDATE SET
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    target = EXCLUDED.target,
    xp_reward = EXCLUDED.xp_reward,
    diamond_reward = EXCLUDED.diamond_reward,
    rule_version = EXCLUDED.rule_version,
    badge_asset_id = EXCLUDED.badge_asset_id,
    badge_tier = EXCLUDED.badge_tier,
    category = EXCLUDED.category,
    enabled = EXCLUDED.enabled;

INSERT INTO mission_definition (
    id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled, category, difficulty
) VALUES
    ('10000000-0000-0000-0000-000000000101', 'DAILY_VOCAB_15', 'Ôn tập 15 thẻ từ vựng hôm nay', 'VOCABULARY_REVIEW', 15, 25, 3, 'DAILY', 'v1', true, 'VOCAB', 'EASY'),
    ('10000000-0000-0000-0000-000000000102', 'DAILY_VOCAB_35', 'Bứt phá 35 thẻ từ vựng SRS', 'VOCABULARY_REVIEW', 35, 50, 6, 'DAILY', 'v1', true, 'VOCAB', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000103', 'DAILY_SHADOWING_5', 'Luyện nói 5 câu Shadowing chuẩn ngữ điệu', 'SHADOWING_SENTENCE', 5, 30, 4, 'DAILY', 'v1', true, 'LESSON', 'EASY'),
    ('10000000-0000-0000-0000-000000000104', 'DAILY_GRAMMAR_10', 'Hoàn thành 10 câu Ngữ pháp ứng dụng', 'GRAMMAR_ANSWER', 10, 35, 4, 'DAILY', 'v1', true, 'GRAMMAR', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000105', 'DAILY_LESSON_1', 'Hoàn thành trọn vẹn 1 bài học tình huống', 'LESSON_COMPLETED', 1, 45, 5, 'DAILY', 'v1', true, 'LESSON', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000106', 'DAILY_TOEIC_DRILL_1', 'Luyện 1 bài TOEIC Drill nhanh 10 phút', 'TOEIC_DRILL_COMPLETED', 1, 40, 5, 'DAILY', 'v1', true, 'TOEIC', 'HARD'),
    ('10000000-0000-0000-0000-000000000107', 'WEEKLY_VOCAB_150', 'Ôn tập 150 lượt từ vựng trong tuần', 'VOCABULARY_REVIEW', 150, 120, 15, 'WEEKLY', 'v1', true, 'VOCAB', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000108', 'WEEKLY_LESSON_4', 'Hoàn thành 4 bài nghe & shadowing trong tuần', 'LESSON_COMPLETED', 4, 150, 18, 'WEEKLY', 'v1', true, 'LESSON', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000109', 'WEEKLY_SHADOWING_40', 'Chinh phục 40 câu Shadowing mỗi tuần', 'SHADOWING_SENTENCE', 40, 160, 20, 'WEEKLY', 'v1', true, 'LESSON', 'HARD'),
    ('10000000-0000-0000-0000-000000000110', 'WEEKLY_GRAMMAR_60', 'Giải quyết 60 câu Ngữ pháp chuyên sâu', 'GRAMMAR_ANSWER', 60, 140, 15, 'WEEKLY', 'v1', true, 'GRAMMAR', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000111', 'WEEKLY_TOEIC_3', 'Hoàn thành 3 phiên TOEIC Drill có mục tiêu', 'TOEIC_DRILL_COMPLETED', 3, 200, 25, 'WEEKLY', 'v1', true, 'TOEIC', 'HARD'),
    ('10000000-0000-0000-0000-000000000112', 'WEEKLY_ALL_ROUNDER', 'Đạt 25 câu luyện nói phản xạ tự nhiên', 'SHADOWING_SENTENCE', 25, 110, 12, 'WEEKLY', 'v1', true, 'GENERAL', 'EASY')
ON CONFLICT (code) DO UPDATE SET
    title = EXCLUDED.title,
    target = EXCLUDED.target,
    xp_reward = EXCLUDED.xp_reward,
    diamond_reward = EXCLUDED.diamond_reward,
    rule_version = EXCLUDED.rule_version,
    category = EXCLUDED.category,
    difficulty = EXCLUDED.difficulty,
    enabled = EXCLUDED.enabled;

-- =============================================================================
-- 6. Human-Readable Learner Notifications & Announcements
-- =============================================================================
INSERT INTO learner_notification (
    id, title, body, category, target_audience, status, published_at, expires_at, created_by
) VALUES
    (
        '70000000-0000-0000-0000-000000000901',
        'Lộ trình Thực chiến A2–B1 mới đã mở!',
        'Khám phá lộ trình ghép sẵn từng chặng ngắn từ bài nghe tình huống, cụm từ vựng chọn lọc và ngữ pháp nền tảng.',
        'SYSTEM', 'ALL', 'PUBLISHED', now() - interval '2 days', now() + interval '60 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000902',
        'Dành 10 phút luyện tiếng Anh hôm nay',
        'Mở lại bài đang học hoặc dành vài phút ôn từ vựng để giữ nhịp luyện tập.',
        'STUDY_REMINDER', 'ALL', 'PUBLISHED', now() - interval '6 hours', now() + interval '7 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000903',
        'Mở khóa Lộ trình Công sở & Chữa đề TOEIC B1–B2',
        'Thành viên Plus hiện có thể mở toàn bộ bộ đề TOEIC Part 1–7, chặng luyện ngữ pháp chuyên sâu và gia sư AI 200 lượt/ngày.',
        'REWARD', 'PLUS', 'PUBLISHED', now() - interval '1 day', now() + interval '30 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000904',
        'Trải nghiệm gói Lyreo Plus — Học sâu & Bứt tốc TOEIC',
        'Nâng cấp Lyreo Plus để mở khóa bộ từ vựng TOEIC & Technology chuyên sâu, 100 lượt chấm Shadowing và 200 lượt Gia sư AI/ngày.',
        'PROMO', 'FREE', 'PUBLISHED', now() - interval '12 hours', now() + interval '30 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000905',
        'Bản nháp hướng dẫn xem tiến độ',
        'Sử dụng mục Tiến độ để xem hoạt động học gần đây và những nội dung nên luyện thêm.',
        'SYSTEM', 'ALL', 'DRAFT', NULL, NULL, 'seed-demo'
    ),
    (
        '70000000-0000-0000-0000-000000000906',
        'Thông báo bảo trì đã kết thúc',
        'Đợt bảo trì hệ thống đã hoàn tất.',
        'SYSTEM', 'ALL', 'PUBLISHED', now() - interval '14 days', now() - interval '7 days', 'seed-demo'
    )
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 8. Real-Data Curated-Slice Learning Paths (referencing REAL imported UUIDs!)
-- =============================================================================
INSERT INTO curriculum_path (
    id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key
) VALUES
    (
        '70000000-0000-0000-0000-000000000601',
        'REAL_WORLD_ENGLISH_A2_B1',
        'Lộ trình Giao tiếp Thực chiến Đời sống (A2–B1)',
        'A2',
        'Ghép từng chặng thực tế: cụm từ vựng cốt lõi chọn lọc từ kho 1.3.0, bài nghe-chép & shadowing tình huống, ngữ pháp nền tảng và luyện nói AI thực tế.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000111',
        'PUBLIC',
        NULL
    ),
    (
        '70000000-0000-0000-0000-000000000602',
        'WORKPLACE_FLUENCY_B1_B2',
        'Tiếng Anh Công sở, Công nghệ & Đàm phán (B1–B2 Plus)',
        'B1',
        'Lộ trình nâng cao dành cho môi trường làm việc quốc tế: từ vựng Công sở/Tech/TOEIC chọn lọc, họp Standup, đàm phán khách hàng và luyện đề TOEIC theo Part.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000112',
        'FEATURE',
        'lesson.plus-lessons'
    ),
    (
        '70000000-0000-0000-0000-000000000603',
        'TOEIC_500_FOUNDATION',
        'Lộ trình TOEIC 500+ Nền tảng (Nghe–Đọc Part 1–5)',
        'A2',
        'Xây chắc nền tảng TOEIC mục tiêu 500+: bộ từ vựng công sở cốt lõi, bài câu ngắn Part 2–3 cắt từng chặng, ngữ pháp Thì/Từ loại và luyện đề Part 1–2–5.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000113',
        'PUBLIC',
        NULL
    ),
    (
        '70000000-0000-0000-0000-000000000604',
        'TOEIC_800_MASTERY',
        'Chinh phục TOEIC 800+ Toàn diện (Part 3–7 & Full Test Plus)',
        'B2',
        'Bứt phá band 800+ cho mục tiêu công việc quốc tế: bộ từ vựng TOEIC nâng cao, bài thông báo Part 4, ngữ pháp Bị động/Mệnh đề quan hệ và thi thử Full Test 2026.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000114',
        'FEATURE',
        'lesson.plus-lessons'
    )
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    level = EXCLUDED.level,
    description = EXCLUDED.description,
    status = EXCLUDED.status,
    cover_asset_id = EXCLUDED.cover_asset_id,
    access_mode = EXCLUDED.access_mode,
    required_feature_key = EXCLUDED.required_feature_key,
    updated_at = now();

-- Path 1-4 sections
INSERT INTO curriculum_section (id, path_id, title, position) VALUES
    ('70000000-0000-0000-0000-000000000611', '70000000-0000-0000-0000-000000000601', 'Chặng 1 · Sinh hoạt & Quán cà phê buổi sáng', 1),
    ('70000000-0000-0000-0000-000000000612', '70000000-0000-0000-0000-000000000601', 'Chặng 2 · Kể chuyện & Giao tiếp hằng ngày', 2),
    ('70000000-0000-0000-0000-000000000613', '70000000-0000-0000-0000-000000000601', 'Chặng 3 · Du lịch, Khách sạn & Di chuyển', 3),
    ('70000000-0000-0000-0000-000000000614', '70000000-0000-0000-0000-000000000601', 'Chặng 4 · Phản xạ Y tế & Kiểm tra Nghe', 4),
    ('70000000-0000-0000-0000-000000000621', '70000000-0000-0000-0000-000000000602', 'Chặng 1 · Họp Standup & Từ vựng Công nghệ', 1),
    ('70000000-0000-0000-0000-000000000622', '70000000-0000-0000-0000-000000000602', 'Chặng 2 · Cấu trúc Học thuật & Đọc-Nghe B2', 2),
    ('70000000-0000-0000-0000-000000000623', '70000000-0000-0000-0000-000000000602', 'Chặng 3 · Đàm phán Khách hàng & TOEIC Mục tiêu', 3),
    ('70000000-0000-0000-0000-000000000631', '70000000-0000-0000-0000-000000000603', 'Chặng 1 · Từ vựng Công sở & Part 2 Phản xạ ngắn', 1),
    ('70000000-0000-0000-0000-000000000632', '70000000-0000-0000-0000-000000000603', 'Chặng 2 · Ngữ pháp Từ loại & Hội thoại Part 3', 2),
    ('70000000-0000-0000-0000-000000000633', '70000000-0000-0000-0000-000000000603', 'Chặng 3 · Nghe hiểu Part 1–2 & Tổng ôn Part 5', 3),
    ('70000000-0000-0000-0000-000000000641', '70000000-0000-0000-0000-000000000604', 'Chặng 1 · Từ vựng TOEIC B2 & Hội thoại Part 3', 1),
    ('70000000-0000-0000-0000-000000000642', '70000000-0000-0000-0000-000000000604', 'Chặng 2 · Thông báo Part 4 & Ngữ pháp Câu phức', 2),
    ('70000000-0000-0000-0000-000000000643', '70000000-0000-0000-0000-000000000604', 'Chặng 3 · Đọc hiểu Part 6–7 & Full Test 2026', 3)
ON CONFLICT (id) DO UPDATE SET
    title = EXCLUDED.title,
    position = EXCLUDED.position;

-- Tag B2 situational & Part 4 TOEIC lessons as Plus when present
UPDATE lesson_metadata
SET access_mode = 'FEATURE', required_feature_key = 'lesson.plus-lessons', visible = true
WHERE lesson_id IN (
    '494cfdbc-b24e-4045-9c7f-96d655d0c16c',
    'a22b36d1-512a-415b-b6e8-5a23adadc9bd'
);

-- Path 1 items (REAL UUIDs + curated slices, guarded by EXISTS for empty-DB safety)
INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule, config_json)
SELECT v.id, v.section_id, v.position, v.content_type, v.ref_id, v.required, v.unlock_rule, v.cfg
FROM (VALUES
    -- Section 1
    ('70000000-0000-0001-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 1, 'VOCABULARY_REVIEW', 'c19167e3-6efc-53bf-a64a-47217afcd0a4'::uuid, true, 'NONE',
     '{"entryIds":["63c32867-219d-53c3-b595-aae1c0199de6","656dabc9-ebd1-50c4-855f-681ea95498e2","299d0034-4739-5b58-be16-5e02d3fdce91","9cd60434-9dee-5947-a681-7ca6b5689f8b","aae839a1-3f3b-5de0-b82d-9bf3a8185d3e","6f4823f1-5a8f-530a-aa3a-a3001a34f959"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 2, 'LESSON', '246d7560-e4a3-4807-a022-190220c7a00d'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["425d0e78-5e7c-45bb-ab34-a02c54b1a7c6","320b98f3-1ee7-4dd3-bf2a-dce3c6493608","152131f2-5b7c-4cdb-9870-27618ca5c662"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 3, 'GRAMMAR_PRACTICE', 'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":8}'::jsonb),
    ('70000000-0000-0001-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 4, 'LESSON', '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["cd0c3f7f-2b14-4222-a794-b4b31694cbe9","f3b87f81-880d-412b-b78f-b162bb2bb6df","a7853141-b8b9-4d0c-98f9-cee9d7e068f6","74f8900f-e7f1-49ba-9889-3ce5ef974829","6127b404-81c1-4e48-95f7-68c15df4712a"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 5, 'LESSON', '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, false, 'NONE',
     '{"activityType":"DICTATION","sentenceIds":["d729cb90-5450-47fa-b710-c369ad75f0cd","237b45ca-7fcd-42e1-ba1d-3b9ea1260504","51d970f2-ce98-4824-9218-9f23d2b7cb05","d951fd63-df24-45d8-b05e-565014c9fd47","faf7f74f-d904-4e9c-b794-7ba909936a50"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 6, 'LESSON', '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["53649faa-1e29-4c62-8861-7cf445584cc5","2d11a2be-609b-48eb-98f2-71ff49bce670","3d587af2-d1d9-4a46-813f-0ea93ff6cf2a","e8a234a9-edc3-4cbb-9456-2e5ece75dbac","a5d5bce5-0e16-48af-be48-4a5e24e4a749"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 7, 'LESSON', '0a3989a2-a5e9-41bb-9b7c-7d5172a552d5'::uuid, false, 'NONE',
     '{"activityType":"DICTATION","sentenceIds":["5483571e-dbe0-43f1-9d7f-04e1642cd189","7a69c1fc-54e9-4f90-99b3-12382ef0a4fd","cc911cc3-f2a6-473b-99a9-f64ce36c6465","7251b5fe-7a62-43d4-be60-3a29d3f1b947","b5b0b47e-4d35-4e64-8dd5-bf342b541f02"]}'::jsonb),
    -- Section 2
    ('70000000-0000-0001-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 1, 'VOCABULARY_REVIEW', '87fc9dd8-1e4d-5f49-bb67-05b763c4d8d5'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["98b541e5-2a3b-5c66-8944-db152e822c8e","11c6f96c-3aa3-57a9-a62e-309aa98cd003","c0e0c69e-c1ed-5fbb-91b5-c7623b3fad70","a4bc7408-a76d-5630-adf7-08e6ab55da9e","6f980e0f-45e9-5a66-bef8-eb0971d00591","3153034f-bf67-555a-91af-530ce6a17946"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 2, 'LESSON', 'bc3b286b-10c6-47fd-b4d4-4c3c30ff4edc'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION","sentenceIds":["50ebfe18-ef7d-4635-93e8-fbeb770f4f7c","44bdbaff-8a96-4725-9170-bca42379a620","d9c6dd18-ed11-46f7-92fc-318742742750"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 3, 'GRAMMAR_PRACTICE', 'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":8}'::jsonb),
    ('70000000-0000-0001-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 4, 'GRAMMAR_PRACTICE', 'cc062294-7d1d-5705-8633-0a918e48d9c4'::uuid, false, 'NONE',
     '{"questionCount":8}'::jsonb),
    -- Section 3
    ('70000000-0000-0001-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 1, 'VOCABULARY_REVIEW', '2871056c-22f2-591c-953d-2557c7e61e0e'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["d0a0b426-801e-54e9-aa72-2a1b19a8b58f","0b9a827d-4a7f-52a5-96bb-5537db88ab61","6c6febdb-29a8-53ab-94ea-7301d16f30b9"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 2, 'LESSON', '2cfe1fcd-017c-4512-b071-e981c0945632'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING"}'::jsonb),
    ('70000000-0000-0001-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 3, 'GRAMMAR_PRACTICE', 'f73e5cfd-d28a-5ef1-9741-2f57b9b84403'::uuid, false, 'NONE',
     '{"questionCount":8}'::jsonb),
    -- Section 4
    ('70000000-0000-0001-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 1, 'TOEIC_DRILL', '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[1,2],"questionCount":10}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
ON CONFLICT (id) DO UPDATE SET
    content_reference_id = EXCLUDED.content_reference_id,
    config_json = EXCLUDED.config_json,
    position = EXCLUDED.position;

-- Path 2 items (Workplace Plus)
INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule, config_json)
SELECT v.id, v.section_id, v.position, v.content_type, v.ref_id, v.required, v.unlock_rule, v.cfg
FROM (VALUES
    ('70000000-0000-0002-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 1, 'VOCABULARY_REVIEW', 'c9786a44-99ee-5429-ba65-3b280279d776'::uuid, true, 'NONE',
     '{"entryIds":["19fc0403-0d97-593f-a9a3-379fa1ee502b","be85838e-fae3-5d37-8356-6f3012f66183","5c73dfdd-be4f-53be-9d7a-a3799023a070","34ec2e0a-56e9-50e5-9426-d7b0ec9ba8fc"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 2, 'VOCABULARY_REVIEW', 'd9df7b34-7215-5e97-9a7e-fb2a8cf8fca6'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["65a66d76-8ee2-5598-a363-613055cb4097","816bd310-c256-5296-8149-2da75c43dee3","19661f56-5103-58c4-ac53-45191abeb7ff","33456350-2b5b-5b9f-8d56-8de5631ef54a"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 3, 'LESSON', '23a70d96-5bb5-4b61-ad11-b083322a80b7'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["bf222739-68cd-445b-8290-6f0f8b78b4f1","57693995-babd-41c6-bf31-43abf237f791","e996dae2-e00b-48d5-aa0b-555923461a10","215996dc-a76f-402e-94ec-9a5f9a14dc1f","b680f7d1-aaf3-40b2-8d3a-bf2aa56e062a"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 1, 'GRAMMAR_PRACTICE', 'f177cf24-c574-5266-97d4-3d9af9e676ef'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0002-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 2, 'GRAMMAR_PRACTICE', '9cd401c5-18bd-53e9-a294-6f1f39b26c78'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0002-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 3, 'LESSON', 'c9f2b0da-c8a2-4636-8a14-8a1667957259'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION"}'::jsonb),
    ('70000000-0000-0002-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 4, 'LESSON', 'de2cc719-0f15-4f25-b845-5504fcdf1860'::uuid, false, 'NONE',
     '{"activityType":"SHADOWING","sentenceIds":["24a70923-9a80-47fd-bbe9-d6831c8723e0","48002dde-167f-4200-bfb3-c994d09d7577","35c861bc-54eb-4dac-a8c4-54519b5682fb"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 5, 'LESSON', '494cfdbc-b24e-4045-9c7f-96d655d0c16c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION","sentenceIds":["92466b42-460b-4e40-8024-ac7f5cb15947","779d873e-08ff-410f-94a9-7ce675b1c6f4","d1ed1ff7-36c1-4ca9-9e95-ca76804889f6","ab8f1347-74f1-46b3-afa2-5df0b8b3e53e","ea76abc3-2ff7-4473-9595-ef44c3a25b31"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 1, 'VOCABULARY_REVIEW', '251b6c52-fde0-5f47-a41c-293153868c34'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["b21f4ccc-6b7d-5fca-8f8f-78a9743002a3","95c7cfa5-fa38-5663-9a8d-1f061c368128","8a0e4be7-5a08-55cc-9f60-c83cff625056","a90ddfcd-3d09-5cbb-b4ef-840b64639e7f","297a4139-4356-59f3-98d4-94b88c67a53a","3efd4d1f-9c57-5fbf-a7ca-2e1c039c09ec"]}'::jsonb),
    ('70000000-0000-0002-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 2, 'TOEIC_DRILL', '767147a7-02a4-5ef6-a725-d3615b042d4c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[5,6,7],"questionCount":15}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
ON CONFLICT (id) DO UPDATE SET
    content_reference_id = EXCLUDED.content_reference_id,
    config_json = EXCLUDED.config_json,
    position = EXCLUDED.position;

-- Path 3 items (TOEIC 500+ Foundation)
INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule, config_json)
SELECT v.id, v.section_id, v.position, v.content_type, v.ref_id, v.required, v.unlock_rule, v.cfg
FROM (VALUES
    ('70000000-0000-0003-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 1, 'VOCABULARY_REVIEW', 'c9786a44-99ee-5429-ba65-3b280279d776'::uuid, true, 'NONE',
     '{"entryIds":["19fc0403-0d97-593f-a9a3-379fa1ee502b","be85838e-fae3-5d37-8356-6f3012f66183","1f0295db-3e11-524b-9a2a-ef11480fd920","0142fee2-885c-5903-891b-50d7db98a77c","5c73dfdd-be4f-53be-9d7a-a3799023a070","34ec2e0a-56e9-50e5-9426-d7b0ec9ba8fc"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 2, 'LESSON', 'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["8fe21f75-69bc-4514-8cca-73b49b00a86c","d9c217b5-2247-455b-8d0a-6762b4384bd7","ad9035ce-f7f4-4f0a-b76e-d82c9a90f9e1","c8437d6c-a8cb-4b3b-8b11-66c8e98985b1"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 3, 'LESSON', 'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION","sentenceIds":["c7f7a097-455b-4fb0-b427-e059a375ec19","ff9a0455-d136-410a-ab28-7b5ec2cb8539","fbc47683-c76e-4629-afe4-cd05b7a86034","6b8e4715-c1ec-451d-9bbb-915eb469a8d7"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 4, 'GRAMMAR_PRACTICE', 'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 1, 'GRAMMAR_PRACTICE', 'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 2, 'GRAMMAR_PRACTICE', 'cc062294-7d1d-5705-8633-0a918e48d9c4'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 3, 'VOCABULARY_REVIEW', '6aa80005-98c7-50de-8649-1e94e59244d4'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["9b27c494-4082-54e8-b5f2-36b4ac73f904","940b9066-a0f9-5b5a-b061-c09b780034cf","0b063229-6db9-53a5-8036-b3065459c99f","f873d8d8-952b-564d-b6b8-b25fe9060faa","5b0814ce-c9e2-5f80-964a-6f0da0702056","aec4b538-3c13-51af-9288-3aa67b90a336"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 4, 'LESSON', '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["6627fe34-d254-40d0-907c-738bc85d7463","35503aee-c4ae-49fd-b002-b1f74d0e64ef","b1f7c03b-fb39-4d14-84e2-8eb1d11ebbb5","757f7936-c3bf-4dba-9732-244a4c7e9cea"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 1, 'TOEIC_DRILL', '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[1,2],"questionCount":12}'::jsonb),
    ('70000000-0000-0003-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 2, 'GRAMMAR_PRACTICE', 'f73e5cfd-d28a-5ef1-9741-2f57b9b84403'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 3, 'LESSON', 'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, false, 'NONE',
     '{"activityType":"SHADOWING","sentenceIds":["81f617e8-3d16-49d6-ae25-cdc509e51bd9","eede1812-602f-46a3-ac9b-a58bc535a1fd","c705165f-795d-420f-879d-8c2f28350b3f","4dcaa4c9-cffa-414f-810e-c9c0227d4b4b"]}'::jsonb),
    ('70000000-0000-0003-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 4, 'TOEIC_DRILL', 'fcc13d9d-40d0-5f50-8c14-aa0e79ccdcbc'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[5],"questionCount":15}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
ON CONFLICT (id) DO UPDATE SET
    content_reference_id = EXCLUDED.content_reference_id,
    config_json = EXCLUDED.config_json,
    position = EXCLUDED.position;

-- Path 4 items (TOEIC 800+ Mastery Plus)
INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule, config_json)
SELECT v.id, v.section_id, v.position, v.content_type, v.ref_id, v.required, v.unlock_rule, v.cfg
FROM (VALUES
    ('70000000-0000-0004-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 1, 'VOCABULARY_REVIEW', '251b6c52-fde0-5f47-a41c-293153868c34'::uuid, true, 'NONE',
     '{"entryIds":["b21f4ccc-6b7d-5fca-8f8f-78a9743002a3","95c7cfa5-fa38-5663-9a8d-1f061c368128","8a0e4be7-5a08-55cc-9f60-c83cff625056","a90ddfcd-3d09-5cbb-b4ef-840b64639e7f","297a4139-4356-59f3-98d4-94b88c67a53a","3efd4d1f-9c57-5fbf-a7ca-2e1c039c09ec"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 2, 'LESSON', '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION","sentenceIds":["e5591f8b-876d-4265-8298-c4de7f4628db","b660c4e2-42b2-4350-a562-297e7b38876d","76a4fa56-c4cc-4e94-a0bf-bf01f0c45c57","a41fd3da-06b7-4d3b-8eb3-1de8becde8f7"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 3, 'VOCABULARY_REVIEW', '746d8ac7-6127-55f2-a843-48c78b2e5915'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["62105923-8803-575d-9cd0-05c3b33ca30f","d5d2ea2e-6e39-5612-8b33-0b9e30b760d1","bcf81d90-2ed1-5d54-b886-088da368645f","c223262f-d50e-5635-8b5d-a3c77769cbc1","50258a13-7689-5b66-9959-877d8e1c5473","be885a96-8a52-57e2-b31e-032a65e6e8a5"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 4, 'LESSON', '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["7bb7693e-0db9-43f9-9e07-746caa54317a","2cb10a68-f950-492e-b368-5d178948a2d1","3a08289f-8c6b-44d7-9a01-e7f2bc308458","1deafbdc-58c4-4beb-a8f5-2d8043667c8f"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 1, 'LESSON', 'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["6c28fd77-ecec-44d1-bfbe-813b783aeca0","ee5ae150-cfad-45d2-b36c-9f1d8dc3fb0f","61826f3c-360d-4b6a-b42a-e54b0675d65d","816d631b-16b1-4b60-ba95-3d5cfbf69247"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 2, 'GRAMMAR_PRACTICE', 'f177cf24-c574-5266-97d4-3d9af9e676ef'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":12}'::jsonb),
    ('70000000-0000-0004-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 3, 'GRAMMAR_PRACTICE', '9cd401c5-18bd-53e9-a294-6f1f39b26c78'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":12}'::jsonb),
    ('70000000-0000-0004-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 4, 'LESSON', 'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"DICTATION","sentenceIds":["b65576fc-0ab6-4bd5-987b-ac0b10fcb7a7","b606e27a-0416-4d0f-a315-577788412903","601a5a62-4eab-4a13-a8a6-bc496dfc8434","a37842f0-e111-4731-8b1b-bc6c346e9671"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 1, 'VOCABULARY_REVIEW', '27d860f3-33bd-5cac-a1ea-9b6b266852b5'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"entryIds":["cc3b8767-b9d5-5ec1-9f3c-67a25b784343","a26adc3c-77de-539c-86b6-6c4c01fb2949","06f21564-b817-58ef-8132-3bcf80dc0bd9","7c7bc158-8101-51a3-b337-f5d376917ec3","d4cf2d44-f206-5c67-952a-676f96a7aee0","a2f825de-61f5-5d95-98ec-2be1b5b937d4"]}'::jsonb),
    ('70000000-0000-0004-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 2, 'GRAMMAR_PRACTICE', 'a5191d93-2ca3-52c7-a56e-220e5cc25080'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"questionCount":15}'::jsonb),
    ('70000000-0000-0004-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 3, 'TOEIC_DRILL', '8db64952-8ff7-5427-85ad-b62ee5cfa9bf'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[3,4,6,7],"questionCount":20}'::jsonb),
    ('70000000-0000-0004-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 4, 'TOEIC_TEST', '5a6c2fa9-8cb4-5572-b2ad-46114994c37a'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[1,2,3,4,5,6,7],"questionCount":200}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
ON CONFLICT (id) DO UPDATE SET
    content_reference_id = EXCLUDED.content_reference_id,
    config_json = EXCLUDED.config_json,
    position = EXCLUDED.position;

-- Enrollments & initial progression for active learner cohort
INSERT INTO curriculum_enrollment (learner_id, path_id, status, started_at)
SELECT v.learner_id, v.path_id, 'ACTIVE', v.started_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, now() - interval '6 days'),
    ('30000000-0000-0000-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, now() - interval '4 days')
) AS v(learner_id, path_id, started_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.learner_id)
  AND EXISTS (SELECT 1 FROM curriculum_path cp WHERE cp.id = v.path_id)
ON CONFLICT (learner_id, path_id) DO NOTHING;

INSERT INTO curriculum_item_progress (learner_id, item_id, status, started_at, completed_at)
SELECT v.learner_id, v.item_id, v.status, v.started_at, v.completed_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0001-0000-000000000001'::uuid, 'COMPLETED', now() - interval '3 days', now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0001-0000-000000000002'::uuid, 'UNLOCKED', now() - interval '2 days', NULL),
    ('30000000-0000-0000-0000-000000000004'::uuid, '70000000-0000-0002-0000-000000000001'::uuid, 'COMPLETED', now() - interval '5 days', now() - interval '4 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '70000000-0000-0002-0000-000000000002'::uuid, 'UNLOCKED', now() - interval '4 days', NULL),
    ('30000000-0000-0000-0000-000000000005'::uuid, '70000000-0000-0001-0000-000000000001'::uuid, 'UNLOCKED', now() - interval '2 days', NULL)
) AS v(learner_id, item_id, status, started_at, completed_at)
WHERE EXISTS (SELECT 1 FROM curriculum_item ci WHERE ci.id = v.item_id)
ON CONFLICT (learner_id, item_id) DO NOTHING;

-- =============================================================================
-- 9. Learner Activity, Streaks, XP, Wallets, Audits & Weaknesses
-- =============================================================================
INSERT INTO learner_streak (learner_id, current_streak, longest_streak, last_study_date) VALUES
    ('30000000-0000-0000-0000-000000000001', 5, 7, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000004', 9, 14, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000005', 3, 4, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000006', 12, 21, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000007', 2, 3, (now() AT TIME ZONE 'UTC')::date - 1),
    ('30000000-0000-0000-0000-000000000009', 4, 8, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000011', 8, 12, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000014', 6, 10, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000018', 15, 22, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000021', 7, 11, (now() AT TIME ZONE 'UTC')::date)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO learner_level (learner_id, xp, level) VALUES
    ('30000000-0000-0000-0000-000000000001', 260, 3),
    ('30000000-0000-0000-0000-000000000004', 580, 6),
    ('30000000-0000-0000-0000-000000000005', 145, 2),
    ('30000000-0000-0000-0000-000000000006', 920, 9),
    ('30000000-0000-0000-0000-000000000007', 95, 2),
    ('30000000-0000-0000-0000-000000000009', 310, 4),
    ('30000000-0000-0000-0000-000000000011', 640, 6),
    ('30000000-0000-0000-0000-000000000014', 475, 5),
    ('30000000-0000-0000-0000-000000000018', 1120, 10),
    ('30000000-0000-0000-0000-000000000021', 720, 7)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_wallet (learner_id, cached_balance) VALUES
    ('30000000-0000-0000-0000-000000000001', 28),
    ('30000000-0000-0000-0000-000000000004', 64),
    ('30000000-0000-0000-0000-000000000005', 16),
    ('30000000-0000-0000-0000-000000000006', 110),
    ('30000000-0000-0000-0000-000000000007', 8),
    ('30000000-0000-0000-0000-000000000009', 35),
    ('30000000-0000-0000-0000-000000000011', 80),
    ('30000000-0000-0000-0000-000000000014', 52),
    ('30000000-0000-0000-0000-000000000018', 140),
    ('30000000-0000-0000-0000-000000000021', 74)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_transaction (
    id, learner_id, transaction_type, amount, idempotency_key, reference_type, reference_id, reason, rule_version, created_at
) VALUES
    ('70000000-0000-0000-0000-000000000721', '30000000-0000-0000-0000-000000000004', 'MISSION_REWARD', 18, 'seed:v2:mission:0004:weekly-lesson', 'MISSION', '10000000-0000-0000-0000-000000000108', 'Hoàn thành nhiệm vụ tuần 4 bài học', 'v1', now() - interval '2 days'),
    ('70000000-0000-0000-0000-000000000722', '30000000-0000-0000-0000-000000000004', 'ADMIN_ADJUSTMENT', 30, 'seed:v2:admin:0004:cohort-grant', 'ADMIN_ADJUSTMENT', NULL, 'Thưởng học viên tích cực lộ trình B2', 'v1', now() - interval '1 day'),
    ('70000000-0000-0000-0000-000000000723', '30000000-0000-0000-0000-000000000006', 'ACHIEVEMENT_REWARD', 10, 'seed:v2:ach:0006:streak7', 'ACHIEVEMENT', '40000000-0000-0000-0000-000000000101', 'Mở khóa huy hiệu Nhịp học 7 ngày', 'v1', now() - interval '1 day')
ON CONFLICT (id) DO UPDATE SET
    idempotency_key = EXCLUDED.idempotency_key;

INSERT INTO entitlement_audit_log (
    id, user_id, feature_key, operation_id, decision, policy_version, detail_json, occurred_at
) VALUES
    ('50000000-0000-0000-0000-000000000801', '30000000-0000-0000-0000-000000000004', 'vocabulary.plus-packs', NULL, 'GRANT_CREATED', 'v1', '{"sourceType":"ADMIN_MANUAL","createdBy":"system"}'::jsonb, now() - interval '2 days'),
    ('50000000-0000-0000-0000-000000000802', '30000000-0000-0000-0000-000000000006', 'toeic.full-access', NULL, 'GRANT_CREATED', 'v1', '{"sourceType":"ADMIN_MANUAL","createdBy":"system"}'::jsonb, now() - interval '1 day'),
    ('50000000-0000-0000-0000-000000000803', '30000000-0000-0000-0000-000000000007', 'speech.shadowing', NULL, 'GRANT_REVOKED', 'v1', '{"reason":"Hết thời gian trải nghiệm thử"}'::jsonb, now() - interval '6 hours'),
    ('50000000-0000-0000-0000-000000000804', '30000000-0000-0000-0000-000000000001', 'speech.shadowing', 'seed-demo-score', 'SETTLED', 'v1', '{"amount":1}'::jsonb, now() - interval '3 hours'),
    ('50000000-0000-0000-0000-000000000805', '30000000-0000-0000-0000-000000000005', 'chat.tutor', 'seed-demo-chat', 'DENIED_QUOTA', 'v1', '{"tier":"FREE","limit":10}'::jsonb, now() - interval '2 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learner_daily_activity (
    learner_id, activity_date, study_seconds, lesson_count, vocabulary_review_count, grammar_answer_count, toeic_question_count
)
SELECT v.learner_id, v.activity_date, v.study_seconds, v.lesson_count, v.vocab_cnt, v.grammar_cnt, v.toeic_cnt
FROM (
    SELECT '30000000-0000-0000-0000-000000000001'::uuid AS learner_id, (now() AT TIME ZONE 'UTC')::date - d AS activity_date,
           (900 + (d * 180) % 900) AS study_seconds, CASE WHEN d % 2 = 0 THEN 1 ELSE 2 END AS lesson_count,
           (14 + d) AS vocab_cnt, (6 + d % 5) AS grammar_cnt, CASE WHEN d % 4 = 0 THEN 10 ELSE 0 END AS toeic_cnt
    FROM generate_series(0, 12, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000004'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1250 + (d * 140) % 800), CASE WHEN d % 3 = 0 THEN 2 ELSE 1 END,
           (16 + (d * 2) % 10), (8 + d % 6), CASE WHEN d % 2 = 0 THEN 15 ELSE 0 END
    FROM generate_series(0, 13) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000005'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (650 + d * 70), CASE WHEN d = 0 OR d = 2 THEN 1 ELSE 0 END,
           (10 + d % 5), (4 + d % 3), 0
    FROM generate_series(0, 10, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000006'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1600 + (d * 190) % 1000), CASE WHEN d % 2 = 0 THEN 1 ELSE 0 END,
           (18 + d % 8), (12 + d % 6), (25 + (d % 4) * 15)
    FROM generate_series(0, 13) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000009'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (900 + d * 60), 1, 12, 7, 5
    FROM generate_series(0, 8, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000011'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1400 + d * 90), 1, 15, 9, 20
    FROM generate_series(0, 11) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000014'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1100 + d * 80), 1, 14, 8, 0
    FROM generate_series(0, 9) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000018'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1800 + d * 100), 2, 20, 12, 10
    FROM generate_series(0, 13) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000021'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1320 + d * 70), 1, 16, 10, 30
    FROM generate_series(0, 10, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000007'::uuid, (now() AT TIME ZONE 'UTC')::date - 1, 540, 0, 5, 6, 0
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000010'::uuid, (now() AT TIME ZONE 'UTC')::date - d, 760, 1, 11, 5, 0
    FROM generate_series(0, 6, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000013'::uuid, (now() AT TIME ZONE 'UTC')::date - d, 920, 1, 13, 9, 10
    FROM generate_series(1, 9, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000016'::uuid, (now() AT TIME ZONE 'UTC')::date - d, 840, 1, 10, 4, 0
    FROM generate_series(0, 8, 2) AS d
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000019'::uuid, (now() AT TIME ZONE 'UTC')::date - d, 1050, 0, 12, 11, 15
    FROM generate_series(0, 7, 2) AS d
) AS v
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.learner_id)
ON CONFLICT (learner_id, activity_date) DO UPDATE SET
    study_seconds = GREATEST(learner_daily_activity.study_seconds, EXCLUDED.study_seconds),
    lesson_count = GREATEST(learner_daily_activity.lesson_count, EXCLUDED.lesson_count),
    vocabulary_review_count = GREATEST(learner_daily_activity.vocabulary_review_count, EXCLUDED.vocabulary_review_count),
    grammar_answer_count = GREATEST(learner_daily_activity.grammar_answer_count, EXCLUDED.grammar_answer_count),
    toeic_question_count = GREATEST(learner_daily_activity.toeic_question_count, EXCLUDED.toeic_question_count);

INSERT INTO learner_skill_summary (learner_id, skill_code, score, sample_count, updated_at) VALUES
    ('30000000-0000-0000-0000-000000000001', 'LISTENING', 81.00, 14, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000001', 'SPEAKING', 76.50, 10, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'VOCABULARY', 85.40, 28, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'GRAMMAR', 78.20, 16, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000004', 'LISTENING', 84.50, 18, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000004', 'SPEAKING', 79.00, 14, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000004', 'VOCABULARY', 88.20, 36, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000004', 'GRAMMAR', 83.40, 22, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000004', 'TOEIC_LISTENING', 81.00, 6, now() - interval '6 hours'),
    ('30000000-0000-0000-0000-000000000005', 'VOCABULARY', 74.00, 14, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000005', 'LISTENING', 69.50, 8, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_LISTENING', 89.00, 8, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_READING', 82.50, 8, now() - interval '5 hours'),
    ('30000000-0000-0000-0000-000000000006', 'GRAMMAR', 91.00, 32, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000006', 'VOCABULARY', 87.50, 40, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR', 62.50, 6, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000009', 'SPEAKING', 77.80, 11, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000009', 'LISTENING', 80.20, 9, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000011', 'TOEIC_LISTENING', 86.00, 7, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000011', 'TOEIC_READING', 79.40, 7, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000014', 'SPEAKING', 86.00, 15, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000014', 'VOCABULARY', 84.00, 22, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000018', 'SPEAKING', 92.40, 20, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000018', 'LISTENING', 90.80, 19, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000021', 'TOEIC_READING', 84.00, 9, now() - interval '3 hours')
ON CONFLICT (learner_id, skill_code) DO UPDATE SET
    score = EXCLUDED.score,
    sample_count = GREATEST(learner_skill_summary.sample_count, EXCLUDED.sample_count);

INSERT INTO learner_weakness (learner_id, weakness_type, weakness_key, severity, sample_count, updated_at) VALUES
    ('30000000-0000-0000-0000-000000000001', 'LESSON_ACTIVITY', 'SHADOWING', 22.50, 8, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000001', 'GRAMMAR_TOPIC', 'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b', 31.00, 7, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000001', 'SHADOWING_ASSESSMENT', 'WORD_MISMATCH', 37.50, 8, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'SHADOWING_ASSESSMENT', 'FLUENCY', 44.00, 5, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'TOEIC_PART', '5', 55.00, 5, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000004', 'LESSON_ACTIVITY', 'SHADOWING', 28.00, 6, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000005', 'LESSON_ACTIVITY', 'DICTATION', 34.50, 5, now() - interval '5 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_SECTION', 'READING', 21.00, 4, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR_TOPIC', 'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09', 42.00, 6, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000011', 'TOEIC_SECTION', 'READING', 26.50, 6, now() - interval '5 hours')
ON CONFLICT (learner_id, weakness_type, weakness_key) DO NOTHING;

INSERT INTO learner_weakness (learner_id, weakness_type, weakness_key, severity, sample_count, updated_at)
SELECT '30000000-0000-0000-0000-000000000001', 'VOCABULARY_UNIT', u.id::text, 26.00, 4, now() - interval '1 day'
FROM vocabulary_unit u
WHERE u.id = '87fc9dd8-1e4d-5f49-bb67-05b763c4d8d5'
ON CONFLICT (learner_id, weakness_type, weakness_key) DO NOTHING;

INSERT INTO lesson_progress (learner_id, lesson_id, status, started_at, completed_at, last_activity_at)
SELECT v.learner_id, v.lesson_id, v.status, v.started_at, v.completed_at, v.last_activity_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '246d7560-e4a3-4807-a022-190220c7a00d'::uuid, 'COMPLETED', now() - interval '6 days', now() - interval '5 days', now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000001'::uuid, '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, 'IN_PROGRESS', now() - interval '1 day', NULL, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '23a70d96-5bb5-4b61-ad11-b083322a80b7'::uuid, 'COMPLETED', now() - interval '8 days', now() - interval '6 days', now() - interval '6 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, 'c9f2b0da-c8a2-4636-8a14-8a1667957259'::uuid, 'COMPLETED', now() - interval '5 days', now() - interval '3 days', now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '494cfdbc-b24e-4045-9c7f-96d655d0c16c'::uuid, 'IN_PROGRESS', now() - interval '2 days', NULL, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000005'::uuid, '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, 'COMPLETED', now() - interval '4 days', now() - interval '2 days', now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, 'COMPLETED', now() - interval '7 days', now() - interval '4 days', now() - interval '4 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, 'COMPLETED', now() - interval '3 days', now() - interval '1 day', now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000011'::uuid, 'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, 'COMPLETED', now() - interval '5 days', now() - interval '2 days', now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000018'::uuid, '494cfdbc-b24e-4045-9c7f-96d655d0c16c'::uuid, 'COMPLETED', now() - interval '9 days', now() - interval '5 days', now() - interval '5 days')
) AS v(learner_id, lesson_id, status, started_at, completed_at, last_activity_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.learner_id)
  AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.lesson_id)
ON CONFLICT (learner_id, lesson_id) DO NOTHING;

INSERT INTO learner_achievement (learner_id, achievement_id, progress, unlocked_at)
SELECT v.learner_id, v.achievement_id, v.progress, v.unlocked_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '40000000-0000-0000-0000-000000000101'::uuid, 7, now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000001'::uuid, '40000000-0000-0000-0000-000000000108'::uuid, 100, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '40000000-0000-0000-0000-000000000101'::uuid, 7, now() - interval '6 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '40000000-0000-0000-0000-000000000105'::uuid, 10, now() - interval '4 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '40000000-0000-0000-0000-000000000108'::uuid, 100, now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '40000000-0000-0000-0000-000000000101'::uuid, 7, now() - interval '10 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '40000000-0000-0000-0000-000000000111'::uuid, 25, now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '40000000-0000-0000-0000-000000000114'::uuid, 500, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000018'::uuid, '40000000-0000-0000-0000-000000000101'::uuid, 7, now() - interval '12 days'),
    ('30000000-0000-0000-0000-000000000018'::uuid, '40000000-0000-0000-0000-000000000113'::uuid, 300, now() - interval '2 days')
) AS v(learner_id, achievement_id, progress, unlocked_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.learner_id)
  AND EXISTS (SELECT 1 FROM achievement_definition a WHERE a.id = v.achievement_id)
ON CONFLICT (learner_id, achievement_id) DO UPDATE SET
    progress = GREATEST(learner_achievement.progress, EXCLUDED.progress),
    unlocked_at = COALESCE(learner_achievement.unlocked_at, EXCLUDED.unlocked_at);

COMMIT;
