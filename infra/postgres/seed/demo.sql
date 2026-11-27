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
-- 2. Six demo learner profiles with Free, Plus, expired and pending cases
-- =============================================================================
INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES
    ('30000000-0000-0000-0000-000000000001', 'lyreo-learner-minh-anh', 'minh.anh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000002', 'lyreo-learner-gia-huy', 'gia.huy@lyreo.app'),
    ('30000000-0000-0000-0000-000000000003', 'lyreo-learner-tuan-kiet', 'tuan.kiet@lyreo.app'),
    ('30000000-0000-0000-0000-000000000004', 'lyreo-learner-thao-vy', 'thao.vy@lyreo.app'),
    ('30000000-0000-0000-0000-000000000006', 'lyreo-learner-khanh-linh', 'khanh.linh@lyreo.app'),
    ('30000000-0000-0000-0000-000000000007', 'lyreo-learner-quoc-bao', 'quoc.bao@lyreo.app')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learner_profile (
    learner_id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at, toeic_target_score
)
SELECT v.id, v.display_name, v.goal, v.current_level, v.daily_minutes, v.focus_area, v.avatar_preset, v.first_seen_at, v.onboarding_completed_at, v.toeic_target_score
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, 'Nguyễn Minh Anh', 'CONVERSATION', 'A2', 15, 'LISTENING', 'COMIC_ARI', now() - interval '10 days', now() - interval '10 days' + interval '5 minutes', 750),
    ('30000000-0000-0000-0000-000000000002'::uuid, 'Trần Gia Huy', 'TOEIC', 'B1', 20, 'VOCABULARY', 'KOALA', now() - interval '6 days', now() - interval '6 days' + interval '4 minutes', 650),
    ('30000000-0000-0000-0000-000000000003'::uuid, 'Lê Tuấn Kiệt', NULL, NULL, NULL, NULL, NULL, now() - interval '30 minutes', NULL, NULL),
    ('30000000-0000-0000-0000-000000000004'::uuid, 'Trần Thảo Vy', 'WORK', 'B2', 30, 'SPEAKING', 'COMIC_LUNA', now() - interval '14 days', now() - interval '14 days' + interval '4 minutes', NULL),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'Phạm Khánh Linh', 'TOEIC', 'C1', 45, 'LISTENING', 'FLOWER_IRISES', now() - interval '21 days', now() - interval '21 days' + interval '6 minutes', 850),
    ('30000000-0000-0000-0000-000000000007'::uuid, 'Đỗ Quốc Bảo', 'CONVERSATION', 'B1', 20, 'GRAMMAR', 'RED_PANDA', now() - interval '5 days', now() - interval '5 days' + interval '5 minutes', NULL)
) AS v(id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at, toeic_target_score)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.id)
ON CONFLICT (learner_id) DO UPDATE SET
    display_name = EXCLUDED.display_name;

INSERT INTO learner_plus_period (id, learner_id, starts_at, ends_at, started_by, ended_by)
SELECT v.id, v.learner_id, v.starts_at, v.ends_at, v.started_by, v.ended_by
FROM (VALUES
    ('50000000-0000-0000-0000-000000000011'::uuid, '30000000-0000-0000-0000-000000000001'::uuid, now() - interval '7 days', now() + interval '365 days', 'system', NULL),
    ('50000000-0000-0000-0000-000000000021'::uuid, '30000000-0000-0000-0000-000000000002'::uuid, now() - interval '30 days', now() - interval '1 day', 'system', NULL),
    ('50000000-0000-0000-0000-000000000031'::uuid, '30000000-0000-0000-0000-000000000004'::uuid, now() - interval '14 days', now() + interval '180 days', 'system', NULL),
    ('50000000-0000-0000-0000-000000000034'::uuid, '30000000-0000-0000-0000-000000000006'::uuid, now() - interval '20 days', now() + interval '365 days', 'system', NULL)
) AS v(id, learner_id, starts_at, ends_at, started_by, ended_by)
WHERE NOT EXISTS (SELECT 1 FROM learner_plus_period p WHERE p.learner_id = v.learner_id)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 3. Deterministic Seed Media Artwork (Covers, Badges, Editorial WebPs)
-- =============================================================================
INSERT INTO media_blob (sha256, mime_type, size_bytes, storage_object_key) VALUES
    ('eb41b566e06181d65b68e93fb284ccb02edfced01112c65987ba18b9d68893fa', 'image/webp', 7264,  'media/sha256/eb/eb41b566e06181d65b68e93fb284ccb02edfced01112c65987ba18b9d68893fa'),
    ('f4bade31a8277c811577eb2bb87856d3d99d513f316295511867cb465e68a5a1', 'image/webp', 7988,  'media/sha256/f4/f4bade31a8277c811577eb2bb87856d3d99d513f316295511867cb465e68a5a1'),
    ('7cd548ed462dcab231edce8254c8624d64fb612507cab367734c460d987a4ef0', 'image/webp', 9312,  'media/sha256/7c/7cd548ed462dcab231edce8254c8624d64fb612507cab367734c460d987a4ef0'),
    ('fe224fe495bb89b6770e707f4bac0c716ea77fec39239f5eed7852421ec99308', 'image/webp', 8938,  'media/sha256/fe/fe224fe495bb89b6770e707f4bac0c716ea77fec39239f5eed7852421ec99308'),
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
    ('3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9'),
    ('708beebaf2186b3c677d884c48054aea6a2f9a66cc41e72d6e379d783f2afb15', 'image/webp', 9194,  'media/sha256/70/708beebaf2186b3c677d884c48054aea6a2f9a66cc41e72d6e379d783f2afb15')
ON CONFLICT (sha256) DO NOTHING;

INSERT INTO content_asset (id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by) VALUES
    ('70000000-0000-0000-0000-000000000111', 'eb41b566e06181d65b68e93fb284ccb02edfced01112c65987ba18b9d68893fa', 'media/sha256/eb/eb41b566e06181d65b68e93fb284ccb02edfced01112c65987ba18b9d68893fa', 'image/webp', 7264,  'path-cover-realworld.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000112', 'f4bade31a8277c811577eb2bb87856d3d99d513f316295511867cb465e68a5a1', 'media/sha256/f4/f4bade31a8277c811577eb2bb87856d3d99d513f316295511867cb465e68a5a1', 'image/webp', 7988,  'path-cover-workplace.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000113', '7cd548ed462dcab231edce8254c8624d64fb612507cab367734c460d987a4ef0', 'media/sha256/7c/7cd548ed462dcab231edce8254c8624d64fb612507cab367734c460d987a4ef0', 'image/webp', 9312,  'path-cover-toeic.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000114', 'fe224fe495bb89b6770e707f4bac0c716ea77fec39239f5eed7852421ec99308', 'media/sha256/fe/fe224fe495bb89b6770e707f4bac0c716ea77fec39239f5eed7852421ec99308', 'image/webp', 8938,  'path-cover-toeic-pro.webp', false, 'system'),
    ('70000000-0000-0000-0000-000000000115', '708beebaf2186b3c677d884c48054aea6a2f9a66cc41e72d6e379d783f2afb15', 'media/sha256/70/708beebaf2186b3c677d884c48054aea6a2f9a66cc41e72d6e379d783f2afb15', 'image/webp', 9194,  'path-cover-toeic-a1.webp', false, 'system'),
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
    ('20000000-0000-0000-0000-000000000002', 'Travel', '{"vi":"Du lịch"}'::jsonb),
    ('20000000-0000-0000-0000-000000000003', 'Work', '{"vi":"Công việc"}'::jsonb),
    ('20000000-0000-0000-0000-000000000004', 'News', '{"vi":"Tin tức"}'::jsonb)
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
        '70000000-0000-0000-0000-000000000903',
        'Mở khóa Lộ trình Công sở & Chữa đề TOEIC B1–B2',
        'Thành viên Plus hiện có thể mở toàn bộ bộ đề TOEIC Part 1–7, chặng luyện ngữ pháp chuyên sâu và gia sư AI 200 lượt/ngày.',
        'SYSTEM', 'PLUS', 'PUBLISHED', now() - interval '1 day', now() + interval '30 days', 'admin'
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

WITH reminder AS (
    INSERT INTO learner_notification (id, title, body, category, target_audience, status, published_at, expires_at, created_by)
    SELECT '70000000-0000-0000-0000-000000000907', 'Ôn lại bài đang học',
           'Minh Anh có thể mở lại bài gần nhất hoặc dành vài phút ôn từ vựng hôm nay.',
           'STUDY_REMINDER', 'SELECTED', 'PUBLISHED', now() - interval '2 hours', now() + interval '7 days', 'seed-demo'
    WHERE EXISTS (SELECT 1 FROM app_user WHERE id = '30000000-0000-0000-0000-000000000001')
    ON CONFLICT (id) DO NOTHING RETURNING id
)
INSERT INTO learner_notification_recipient (notification_id, learner_id)
SELECT id, '30000000-0000-0000-0000-000000000001' FROM reminder;

-- =============================================================================
-- 8. Real-Data Curated-Slice Learning Paths (referencing REAL imported UUIDs!)
-- =============================================================================
INSERT INTO curriculum_path (
    id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key
) VALUES
    (
        '70000000-0000-0000-0000-000000000605',
        'TOEIC_A1_STARTER',
        'TOEIC A1 Khởi động cho Người mới (0–350+)',
        'A1',
        'Lộ trình bước đầu dành cho người mới bắt đầu: từ vựng A1 thiết yếu, bài nghe đọc tốc độ chậm dễ tách từ, ngữ pháp Đại từ – Danh từ – Động từ căn bản và làm quen TOEIC Part 1–2–5.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000115',
        'PUBLIC',
        NULL
    ),
    (
        '70000000-0000-0000-0000-000000000601',
        'REAL_WORLD_ENGLISH_A2_B1',
        'Giao tiếp Thực chiến Đời sống (A2–B1)',
        'A2',
        'Xây phản xạ nghe – nói tự nhiên qua từng tình huống đời thường: gọi đồ uống, đi sân bay – khách sạn, từ vựng cốt lõi chọn lọc, ngữ pháp ứng dụng và luyện nghe TOEIC Part 1–2.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000111',
        'PUBLIC',
        NULL
    ),
    (
        '70000000-0000-0000-0000-000000000602',
        'WORKPLACE_FLUENCY_B1_B2',
        'Tiếng Anh Công sở & Đàm phán Quốc tế (B1–B2)',
        'B1',
        'Lộ trình chuyên sâu cho môi trường doanh nghiệp toàn cầu: từ vựng Công nghệ – Kinh tế – Văn phòng, họp Standup, xử lý tình huống khách hàng và luyện đề TOEIC Part 3–7.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000112',
        'FEATURE',
        'lesson.plus-lessons'
    ),
    (
        '70000000-0000-0000-0000-000000000603',
        'TOEIC_500_FOUNDATION',
        'TOEIC 500+ Nền tảng Vững chắc (Part 1–5)',
        'A2',
        'Củng cố nền tảng hướng tới mốc 500+: từ vựng công sở trọng tâm, bài nghe câu ngắn Part 2–3 cắt theo chặng, chuyên đề Thì – Từ loại – Danh từ và luyện đề Part 1–2–3–5.',
        'PUBLISHED',
        '70000000-0000-0000-0000-000000000113',
        'PUBLIC',
        NULL
    ),
    (
        '70000000-0000-0000-0000-000000000604',
        'TOEIC_800_MASTERY',
        'Chinh phục TOEIC 800+ Toàn diện (Part 3–7)',
        'B2',
        'Tăng tốc chinh phục 800+: từ vựng TOEIC nâng cao, bài thông báo Part 4 đa lượt, ngữ pháp Bị động – Mệnh đề quan hệ – Đề thi ETS thật và thử thách Full Test 200 câu.',
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

-- Upsert seeded sections in place without deleting existing sections/items or learner progress
WITH seed_sections (id, path_id, title, position) AS (
    VALUES
    -- Path 5: TOEIC_A1_STARTER (20 items)
    ('70000000-0000-0000-0000-000000000651'::uuid, '70000000-0000-0000-0000-000000000605'::uuid, 'Chương 1 · Nhịp nghe Chậm, Âm cơ bản & Từ vựng A1', 1),
    ('70000000-0000-0000-0000-000000000652'::uuid, '70000000-0000-0000-0000-000000000605'::uuid, 'Chương 2 · Quán ăn, Di chuyển & Danh từ Nền tảng', 2),
    ('70000000-0000-0000-0000-000000000653'::uuid, '70000000-0000-0000-0000-000000000605'::uuid, 'Chương 3 · Nghe hiểu A1 & Làm quen TOEIC Part 1', 3),
    ('70000000-0000-0000-0000-000000000654'::uuid, '70000000-0000-0000-0000-000000000605'::uuid, 'Chương 4 · Phản xạ Câu ngắn & Đánh giá Đầu ra A1', 4),
    -- Path 1: REAL_WORLD_ENGLISH_A2_B1 (22 items)
    ('70000000-0000-0000-0000-000000000611'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, 'Chương 1 · Sinh hoạt Buổi sáng & Quán cà phê', 1),
    ('70000000-0000-0000-0000-000000000612'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, 'Chương 2 · Kể chuyện, Thói quen & Giao tiếp hằng ngày', 2),
    ('70000000-0000-0000-0000-000000000613'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, 'Chương 3 · Du lịch, Khách sạn & Di chuyển Đô thị', 3),
    ('70000000-0000-0000-0000-000000000614'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, 'Chương 4 · Sức khỏe, Xã hội & Kiểm tra Nghe thực tế', 4),
    -- Path 2: WORKPLACE_FLUENCY_B1_B2 (22 items)
    ('70000000-0000-0000-0000-000000000621'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, 'Chương 1 · Họp Standup & Từ vựng Công nghệ', 1),
    ('70000000-0000-0000-0000-000000000622'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, 'Chương 2 · Quản lý Dự án, Kinh tế & Truyền thông', 2),
    ('70000000-0000-0000-0000-000000000623'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, 'Chương 3 · Cấu trúc Học thuật & Nghe–Đọc B2', 3),
    ('70000000-0000-0000-0000-000000000624'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, 'Chương 4 · Đàm phán Khách hàng & Đánh giá Công sở', 4),
    -- Path 3: TOEIC_500_FOUNDATION (24 items)
    ('70000000-0000-0000-0000-000000000631'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, 'Chương 1 · Từ vựng Công sở & Phản xạ Hỏi–Đáp Part 2', 1),
    ('70000000-0000-0000-0000-000000000632'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, 'Chương 2 · Ngữ pháp Từ loại & Giao tiếp Văn phòng', 2),
    ('70000000-0000-0000-0000-000000000633'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, 'Chương 3 · Hội thoại Ngắn Part 3 & Dịch vụ – Đi lại', 3),
    ('70000000-0000-0000-0000-000000000634'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, 'Chương 4 · Tăng tốc Part 5 & Tổng duyệt Nền tảng 500+', 4),
    -- Path 4: TOEIC_800_MASTERY (24 items)
    ('70000000-0000-0000-0000-000000000641'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, 'Chương 1 · Từ vựng Văn phòng B2 & Hội thoại Đa lượt Part 3', 1),
    ('70000000-0000-0000-0000-000000000642'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, 'Chương 2 · Thông báo Chuyên sâu Part 4 & Câu phức Học thuật', 2),
    ('70000000-0000-0000-0000-000000000643'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, 'Chương 3 · Kinh doanh – Tài chính & Đọc điền đoạn Part 6', 3),
    ('70000000-0000-0000-0000-000000000644'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, 'Chương 4 · Đọc hiểu Đa văn bản Part 7 & Thi thử Full Test', 4)
)
INSERT INTO curriculum_section (id, path_id, title, position)
SELECT ss.id, ss.path_id, ss.title, ss.position
FROM seed_sections ss
WHERE NOT EXISTS (SELECT 1 FROM curriculum_section existing WHERE existing.id = ss.id)
ON CONFLICT DO NOTHING;

-- Tag B2 situational & Part 4 TOEIC lessons as Plus when present
UPDATE lesson_metadata
SET access_mode = 'FEATURE', required_feature_key = 'lesson.plus-lessons', visible = true
WHERE lesson_id IN (
    '494cfdbc-b24e-4045-9c7f-96d655d0c16c',
    'a22b36d1-512a-415b-b6e8-5a23adadc9bd'
);

-- Curated items across all 5 paths (20-24 items each; slices verified directly against imported tables)
WITH raw_items (id, section_id, ord, content_type, ref_id, required, unlock_rule, activity_mode, slice_limit, slice_offset, static_cfg) AS (
    VALUES
    -- =========================================================================
    -- PATH 5: TOEIC_A1_STARTER (20 items across 4 chapters)
    -- =========================================================================
    -- Chapter 1 (5 items)
    ('70000000-0000-0005-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000651'::uuid, 1, 'VOCABULARY_REVIEW', 'c19167e3-6efc-53bf-a64a-47217afcd0a4'::uuid, true,  'NONE',              NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000651'::uuid, 2, 'LESSON',            '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000651'::uuid, 3, 'VOCABULARY_REVIEW', 'd0fb4784-6c5c-5c94-b3b9-06b4a0196657'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000651'::uuid, 4, 'LESSON',            '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 4, 4, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000651'::uuid, 5, 'GRAMMAR_PRACTICE',  'be25ff9d-8e47-56d5-b44f-f843f906fc33'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["9301329f-a382-5c03-9989-5ae5310da292"],"questionCount":8}'::jsonb),
    -- Chapter 2 (5 items)
    ('70000000-0000-0005-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000652'::uuid, 1, 'VOCABULARY_REVIEW', '700442dc-1306-5897-b8c7-91189ff05350'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000652'::uuid, 2, 'LESSON',            '0a3989a2-a5e9-41bb-9b7c-7d5172a552d5'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000652'::uuid, 3, 'VOCABULARY_REVIEW', 'fdb65845-cfe7-55de-a387-3d0277e0cd4f'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000652'::uuid, 4, 'LESSON',            '0a3989a2-a5e9-41bb-9b7c-7d5172a552d5'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 4, 4, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000652'::uuid, 5, 'GRAMMAR_PRACTICE',  'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["f9f8de6a-79ed-5439-a061-452aede5a943","1e03858b-89aa-5205-8bbf-7447e477ed4f"],"questionCount":8}'::jsonb),
    -- Chapter 3 (5 items)
    ('70000000-0000-0005-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000653'::uuid, 1, 'VOCABULARY_REVIEW', '75f8c3cf-56c9-56cc-8a8f-cec71b3bb758'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000653'::uuid, 2, 'LESSON',            '246d7560-e4a3-4807-a022-190220c7a00d'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000653'::uuid, 3, 'VOCABULARY_REVIEW', '3f5a0686-3081-5301-aca1-80285342696f'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000653'::uuid, 4, 'GRAMMAR_PRACTICE',  'cc062294-7d1d-5705-8633-0a918e48d9c4'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["9393aac1-b1b8-53e9-a135-91846ace00f5"],"questionCount":8}'::jsonb),
    ('70000000-0000-0005-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000653'::uuid, 5, 'TOEIC_DRILL',       '312f5bea-e822-5141-a021-fb0031fdf504'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1],"questionCount":6}'::jsonb),
    -- Chapter 4 (5 items)
    ('70000000-0000-0005-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000654'::uuid, 1, 'VOCABULARY_REVIEW', '00e2edfe-ce77-596b-bf62-2d17bcdae3c7'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        6, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000654'::uuid, 2, 'LESSON',            'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0005-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000654'::uuid, 3, 'GRAMMAR_PRACTICE',  'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["af15b7da-a8be-54d0-b6ac-b8fbc011ff79"],"questionCount":8}'::jsonb),
    ('70000000-0000-0005-0000-000000000019'::uuid, '70000000-0000-0000-0000-000000000654'::uuid, 4, 'TOEIC_DRILL',       'fe2dc29f-ffda-5fa1-a47f-3c98cf0696fc'::uuid, false, 'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[2],"questionCount":10}'::jsonb),
    ('70000000-0000-0005-0000-000000000020'::uuid, '70000000-0000-0000-0000-000000000654'::uuid, 5, 'TOEIC_DRILL',       'ecd17c4b-57cb-5855-92f5-0fb7a92b4cdf'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2,5],"questionCount":12}'::jsonb),
    -- =========================================================================
    -- PATH 1: REAL_WORLD_ENGLISH_A2_B1 (22 items across 4 chapters)
    -- =========================================================================
    -- Chapter 1 (6 items)
    ('70000000-0000-0001-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 1, 'VOCABULARY_REVIEW', 'c19167e3-6efc-53bf-a64a-47217afcd0a4'::uuid, true,  'NONE',              NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 2, 'LESSON',            '246d7560-e4a3-4807-a022-190220c7a00d'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 3, 'VOCABULARY_REVIEW', 'd0fb4784-6c5c-5c94-b3b9-06b4a0196657'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 4, 'LESSON',            '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 5, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 5, 'LESSON',            '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 5, 5, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 6, 'GRAMMAR_PRACTICE',  'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["af15b7da-a8be-54d0-b6ac-b8fbc011ff79"],"questionCount":8}'::jsonb),
    -- Chapter 2 (6 items)
    ('70000000-0000-0001-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 1, 'VOCABULARY_REVIEW', '87fc9dd8-1e4d-5f49-bb67-05b763c4d8d5'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 2, 'LESSON',            '2eb1d014-7da8-484b-874a-6338c4e0fe80'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 5, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 3, 'GRAMMAR_PRACTICE',  'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["1e03858b-89aa-5205-8bbf-7447e477ed4f","e4f48f1d-184e-563e-8081-2d9a18ade001"],"questionCount":8}'::jsonb),
    ('70000000-0000-0001-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 4, 'VOCABULARY_REVIEW', '9b917da3-5f4f-51cd-ad20-26ee589401ee'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 5, 'LESSON',            'bc3b286b-10c6-47fd-b4d4-4c3c30ff4edc'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 4, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000612'::uuid, 6, 'GRAMMAR_PRACTICE',  'cc062294-7d1d-5705-8633-0a918e48d9c4'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["9393aac1-b1b8-53e9-a135-91846ace00f5"],"questionCount":10}'::jsonb),
    -- Chapter 3 (5 items)
    ('70000000-0000-0001-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 1, 'VOCABULARY_REVIEW', '2871056c-22f2-591c-953d-2557c7e61e0e'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 2, 'LESSON',            '2cfe1fcd-017c-4512-b071-e981c0945632'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 5, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 3, 'VOCABULARY_REVIEW', '1f625e06-569f-5db2-bddf-d6d8c223ea63'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 4, 'LESSON',            '0a3989a2-a5e9-41bb-9b7c-7d5172a552d5'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 5, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 5, 'GRAMMAR_PRACTICE',  'f73e5cfd-d28a-5ef1-9741-2f57b9b84403'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["dde8b6d0-909f-53b6-9a2c-7b0bfefe8e18","4c200c7e-06bd-5b98-a7cf-aee6f8e94fbb"],"questionCount":10}'::jsonb),
    -- Chapter 4 (5 items)
    ('70000000-0000-0001-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 1, 'VOCABULARY_REVIEW', '28eff92a-2588-5684-8101-0c7ec211400a'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000019'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 2, 'VOCABULARY_REVIEW', '1c84fece-fccf-5cf9-9b60-99c39c658f85'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000020'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 3, 'LESSON',            'b42b20ce-1864-4111-9cc5-49da2bef34d3'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 5, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000021'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 4, 'VOCABULARY_REVIEW', '83b23451-de53-545f-b20f-5038a6041a4f'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0001-0000-000000000022'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 5, 'TOEIC_DRILL',       '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2],"questionCount":12}'::jsonb),

    -- =========================================================================
    -- PATH 2: WORKPLACE_FLUENCY_B1_B2 (22 items across 4 chapters)
    -- =========================================================================
    -- Chapter 1 (6 items)
    ('70000000-0000-0002-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 1, 'VOCABULARY_REVIEW', 'c9786a44-99ee-5429-ba65-3b280279d776'::uuid, true,  'NONE',              NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 2, 'VOCABULARY_REVIEW', 'd9df7b34-7215-5e97-9a7e-fb2a8cf8fca6'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 3, 'LESSON',            '23a70d96-5bb5-4b61-ad11-b083322a80b7'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 5, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 4, 'VOCABULARY_REVIEW', '688ae5f2-2389-55e1-b5cc-4b425471bc63'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 5, 'LESSON',            '23a70d96-5bb5-4b61-ad11-b083322a80b7'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 4, 5, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 6, 'GRAMMAR_PRACTICE',  'f177cf24-c574-5266-97d4-3d9af9e676ef'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["32255cfd-b47c-504e-84a7-10c590fd8a66"],"questionCount":10}'::jsonb),
    -- Chapter 2 (6 items)
    ('70000000-0000-0002-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 1, 'VOCABULARY_REVIEW', '61d51944-322b-5d1c-b3e0-d273f7a5b36a'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 2, 'VOCABULARY_REVIEW', '36305066-c7f0-5aad-b203-aaeba3d32695'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 3, 'LESSON',            '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 4, 'GRAMMAR_PRACTICE',  '9cd401c5-18bd-53e9-a294-6f1f39b26c78'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["4808aaae-c0f0-5bf8-9253-4736c67b4de8","2a88d683-3168-5166-bc08-4bef423e92db"],"questionCount":10}'::jsonb),
    ('70000000-0000-0002-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 5, 'VOCABULARY_REVIEW', '6e842768-0668-5dd8-8e1c-f2855f509ca5'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000622'::uuid, 6, 'TOEIC_DRILL',       '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[3,5],"questionCount":12}'::jsonb),
    -- Chapter 3 (5 items)
    ('70000000-0000-0002-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 1, 'VOCABULARY_REVIEW', '3ae9840c-7a44-50d8-b28b-8156db7faca0'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 2, 'LESSON',            'c9f2b0da-c8a2-4636-8a14-8a1667957259'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 5, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 3, 'LESSON',            'de2cc719-0f15-4f25-b845-5504fcdf1860'::uuid, false, 'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 4, 'LESSON',            '494cfdbc-b24e-4045-9c7f-96d655d0c16c'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 5, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 5, 'GRAMMAR_PRACTICE',  'a5191d93-2ca3-52c7-a56e-220e5cc25080'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["7b222d2c-ddd3-5e95-a2e2-2e84ef6ebbac","c6cb617f-b6bd-5664-97c9-f6611ce27ac7"],"questionCount":12}'::jsonb),
    -- Chapter 4 (5 items)
    ('70000000-0000-0002-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000624'::uuid, 1, 'VOCABULARY_REVIEW', '251b6c52-fde0-5f47-a41c-293153868c34'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000019'::uuid, '70000000-0000-0000-0000-000000000624'::uuid, 2, 'VOCABULARY_REVIEW', '8edecdb5-01c8-5da4-a368-9b8be268a7ed'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000020'::uuid, '70000000-0000-0000-0000-000000000624'::uuid, 3, 'LESSON',            'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000021'::uuid, '70000000-0000-0000-0000-000000000624'::uuid, 4, 'VOCABULARY_REVIEW', 'dbd32017-82c5-5b42-a281-629c5acf6d13'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0002-0000-000000000022'::uuid, '70000000-0000-0000-0000-000000000624'::uuid, 5, 'TOEIC_DRILL',       '767147a7-02a4-5ef6-a725-d3615b042d4c'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[5,6,7],"questionCount":20}'::jsonb),

    -- =========================================================================
    -- PATH 3: TOEIC_500_FOUNDATION (24 items across 4 chapters)
    -- =========================================================================
    -- Chapter 1 (6 items)
    ('70000000-0000-0003-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 1, 'VOCABULARY_REVIEW', 'c9786a44-99ee-5429-ba65-3b280279d776'::uuid, true,  'NONE',              NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 2, 'LESSON',            'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 3, 'VOCABULARY_REVIEW', 'c9786a44-99ee-5429-ba65-3b280279d776'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 8, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 4, 'LESSON',            'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 4, 4, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 5, 'GRAMMAR_PRACTICE',  'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["af15b7da-a8be-54d0-b6ac-b8fbc011ff79"],"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000631'::uuid, 6, 'TOEIC_DRILL',       '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2],"questionCount":10}'::jsonb),
    -- Chapter 2 (6 items)
    ('70000000-0000-0003-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 1, 'VOCABULARY_REVIEW', '6aa80005-98c7-50de-8649-1e94e59244d4'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 2, 'GRAMMAR_PRACTICE',  'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["50fcb1ca-dc5a-5996-be31-18f8d667458b","1c803545-872a-51b4-a655-b6bbee2512ec"],"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 3, 'VOCABULARY_REVIEW', '00e2edfe-ce77-596b-bf62-2d17bcdae3c7'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 4, 'LESSON',            '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, false, 'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 5, 'GRAMMAR_PRACTICE',  'cc062294-7d1d-5705-8633-0a918e48d9c4'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["539996ca-f7b6-575e-9a91-a01f0e4e17e9"],"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000632'::uuid, 6, 'TOEIC_DRILL',       'fcc13d9d-40d0-5f50-8c14-aa0e79ccdcbc'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[2,5],"questionCount":12}'::jsonb),
    -- Chapter 3 (6 items)
    ('70000000-0000-0003-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 1, 'VOCABULARY_REVIEW', 'f47fd507-2512-5f80-a952-28da0171e209'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 2, 'LESSON',            '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 4, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 3, 'VOCABULARY_REVIEW', '520b4c15-7adf-51e0-894a-28e483f4b518'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 4, 'LESSON',            '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 4, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 5, 'GRAMMAR_PRACTICE',  'f73e5cfd-d28a-5ef1-9741-2f57b9b84403'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["dde8b6d0-909f-53b6-9a2c-7b0bfefe8e18","4c200c7e-06bd-5b98-a7cf-aee6f8e94fbb"],"questionCount":10}'::jsonb),
    ('70000000-0000-0003-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000633'::uuid, 6, 'TOEIC_DRILL',       '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2,3],"questionCount":15}'::jsonb),
    -- Chapter 4 (6 items)
    ('70000000-0000-0003-0000-000000000019'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 1, 'VOCABULARY_REVIEW', '1f625e06-569f-5db2-bddf-d6d8c223ea63'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000020'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 2, 'LESSON',            'd4c6fe5a-eab9-46be-9331-785de0b6f45c'::uuid, false, 'PREVIOUS_REQUIRED', 'SHADOWING', 4, 8, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000021'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 3, 'VOCABULARY_REVIEW', '61d51944-322b-5d1c-b3e0-d273f7a5b36a'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0003-0000-000000000022'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 4, 'GRAMMAR_PRACTICE',  'f177cf24-c574-5266-97d4-3d9af9e676ef'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["32255cfd-b47c-504e-84a7-10c590fd8a66"],"questionCount":12}'::jsonb),
    ('70000000-0000-0003-0000-000000000023'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 5, 'TOEIC_DRILL',       'fcc13d9d-40d0-5f50-8c14-aa0e79ccdcbc'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[5],"questionCount":20}'::jsonb),
    ('70000000-0000-0003-0000-000000000024'::uuid, '70000000-0000-0000-0000-000000000634'::uuid, 6, 'TOEIC_DRILL',       '767147a7-02a4-5ef6-a725-d3615b042d4c'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2,3,5],"questionCount":30}'::jsonb),

    -- =========================================================================
    -- PATH 4: TOEIC_800_MASTERY (24 items across 4 chapters)
    -- =========================================================================
    -- Chapter 1 (6 items)
    ('70000000-0000-0004-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 1, 'VOCABULARY_REVIEW', '251b6c52-fde0-5f47-a41c-293153868c34'::uuid, true,  'NONE',              NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000002'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 2, 'LESSON',            '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true,  'PREVIOUS_REQUIRED', 'DICTATION', 4, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 3, 'VOCABULARY_REVIEW', '746d8ac7-6127-55f2-a843-48c78b2e5915'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 4, 'LESSON',            '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 4, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 5, 'VOCABULARY_REVIEW', '27d860f3-33bd-5cac-a1ea-9b6b266852b5'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000641'::uuid, 6, 'TOEIC_DRILL',       '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[3],"questionCount":15}'::jsonb),
    -- Chapter 2 (6 items)
    ('70000000-0000-0004-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 1, 'VOCABULARY_REVIEW', '958850ce-c371-54c7-a1c6-e481e003e452'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000008'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 2, 'LESSON',            'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 3, 'GRAMMAR_PRACTICE',  'f177cf24-c574-5266-97d4-3d9af9e676ef'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["32255cfd-b47c-504e-84a7-10c590fd8a66"],"questionCount":12}'::jsonb),
    ('70000000-0000-0004-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 4, 'LESSON',            'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, false, 'PREVIOUS_REQUIRED', 'DICTATION', 4, 4, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 5, 'GRAMMAR_PRACTICE',  '9cd401c5-18bd-53e9-a294-6f1f39b26c78'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["91d7a3b7-a69e-5910-b6f5-9d4b31393505"],"questionCount":12}'::jsonb),
    ('70000000-0000-0004-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000642'::uuid, 6, 'TOEIC_DRILL',       'fcc13d9d-40d0-5f50-8c14-aa0e79ccdcbc'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[4,5],"questionCount":18}'::jsonb),
    -- Chapter 3 (6 items)
    ('70000000-0000-0004-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 1, 'VOCABULARY_REVIEW', 'a0fc2bb9-69a0-54c8-86e5-b8a49a75290c'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 2, 'VOCABULARY_REVIEW', 'efce5782-1cb1-55f2-a80f-59e7fc203838'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 3, 'GRAMMAR_PRACTICE',  'a5191d93-2ca3-52c7-a56e-220e5cc25080'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"subtopicIds":["f84f9e7e-ccf1-5679-b095-68260ed3a78f","feded899-755b-5513-b424-b4d1fde9d0cf"],"questionCount":15}'::jsonb),
    ('70000000-0000-0004-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 4, 'LESSON',            '494cfdbc-b24e-4045-9c7f-96d655d0c16c'::uuid, true,  'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000017'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 5, 'VOCABULARY_REVIEW', 'dcbf032e-bd1b-570a-a62b-466221418959'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000018'::uuid, '70000000-0000-0000-0000-000000000643'::uuid, 6, 'TOEIC_DRILL',       '767147a7-02a4-5ef6-a725-d3615b042d4c'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[5,6],"questionCount":20}'::jsonb),
    -- Chapter 4 (6 items)
    ('70000000-0000-0004-0000-000000000019'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 1, 'VOCABULARY_REVIEW', '43ca446d-a9f9-53fb-a0d8-8f27663b8478'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000020'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 2, 'VOCABULARY_REVIEW', '85496fc3-ae63-5a68-ae73-80a5af7535d5'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        8, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000021'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 3, 'LESSON',            'c9f2b0da-c8a2-4636-8a14-8a1667957259'::uuid, false, 'PREVIOUS_REQUIRED', 'SHADOWING', 4, 0, '{}'::jsonb),
    ('70000000-0000-0004-0000-000000000022'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 4, 'TOEIC_DRILL',       '8db64952-8ff7-5427-85ad-b62ee5cfa9bf'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[3,4],"questionCount":18}'::jsonb),
    ('70000000-0000-0004-0000-000000000023'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 5, 'TOEIC_DRILL',       '8db64952-8ff7-5427-85ad-b62ee5cfa9bf'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[6,7],"questionCount":24}'::jsonb),
    ('70000000-0000-0004-0000-000000000024'::uuid, '70000000-0000-0000-0000-000000000644'::uuid, 6, 'TOEIC_TEST',        '5a6c2fa9-8cb4-5572-b2ad-46114994c37a'::uuid, true,  'PREVIOUS_REQUIRED', NULL,        0, 0, '{"parts":[1,2,3,4,5,6,7],"questionCount":200}'::jsonb)
),
verified_items AS (
    SELECT
        v.id,
        v.section_id,
        ROW_NUMBER() OVER (PARTITION BY v.section_id ORDER BY v.ord)::integer AS position,
        v.content_type,
        v.ref_id,
        v.required,
        v.unlock_rule,
        CASE
            WHEN v.content_type = 'VOCABULARY_REVIEW' AND v.slice_limit > 0 THEN
                COALESCE(
                    (SELECT jsonb_build_object('entryIds', jsonb_agg(sub.id))
                     FROM (
                         SELECT e.id
                         FROM vocabulary_unit_entry e
                         WHERE e.unit_id = v.ref_id AND NOT e.hidden
                         ORDER BY e.position ASC
                         LIMIT v.slice_limit OFFSET v.slice_offset
                     ) sub
                     HAVING count(*) > 0),
                    '{}'::jsonb
                )
            WHEN v.content_type = 'LESSON' AND v.slice_limit > 0 THEN
                COALESCE(
                    (SELECT jsonb_build_object('activityType', COALESCE(v.activity_mode, 'SHADOWING'), 'sentenceIds', jsonb_agg(sub.id))
                     FROM (
                         SELECT s.id
                         FROM lesson_sentence s
                         WHERE s.lesson_id = v.ref_id
                         ORDER BY s.position ASC
                         LIMIT v.slice_limit OFFSET v.slice_offset
                     ) sub
                     HAVING count(*) > 0),
                    jsonb_build_object('activityType', COALESCE(v.activity_mode, 'SHADOWING'))
                )
            WHEN v.content_type = 'GRAMMAR_PRACTICE' AND v.static_cfg ? 'subtopicIds' THEN
                CASE
                    WHEN EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(v.static_cfg->'subtopicIds') AS elem(sub_id)
                        JOIN grammar_subtopic_catalog sc ON sc.id = elem.sub_id::uuid
                    ) THEN
                        jsonb_set(
                            v.static_cfg,
                            '{subtopicIds}',
                            (
                                SELECT jsonb_agg(elem.sub_id)
                                FROM jsonb_array_elements_text(v.static_cfg->'subtopicIds') AS elem(sub_id)
                                JOIN grammar_subtopic_catalog sc ON sc.id = elem.sub_id::uuid
                            )
                        )
                    ELSE v.static_cfg - 'subtopicIds'
                END
            ELSE v.static_cfg
        END AS cfg
    FROM raw_items v
    WHERE
        ((v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
      OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson_availability l WHERE l.lesson_id = v.ref_id AND l.available))
      OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
      OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id)))
      AND EXISTS (SELECT 1 FROM curriculum_section cs WHERE cs.id = v.section_id)
)
INSERT INTO curriculum_item (id, section_id, position, content_type, content_reference_id, required, unlock_rule, config_json)
SELECT vi.id, vi.section_id, vi.position, vi.content_type, vi.ref_id, vi.required, vi.unlock_rule, vi.cfg
FROM verified_items vi
WHERE NOT EXISTS (SELECT 1 FROM curriculum_item existing WHERE existing.id = vi.id)
ON CONFLICT DO NOTHING;

-- Enrollments & initial progression for active learner cohort
INSERT INTO curriculum_enrollment (learner_id, path_id, status, started_at)
SELECT v.learner_id, v.path_id, 'ACTIVE', v.started_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000603'::uuid, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, '70000000-0000-0000-0000-000000000604'::uuid, now() - interval '2 days'),
    ('30000000-0000-0000-0000-000000000007'::uuid, '70000000-0000-0000-0000-000000000605'::uuid, now() - interval '1 day')
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
    ('30000000-0000-0000-0000-000000000007'::uuid, '70000000-0000-0005-0000-000000000001'::uuid, 'COMPLETED', now() - interval '1 day', now() - interval '12 hours'),
    ('30000000-0000-0000-0000-000000000007'::uuid, '70000000-0000-0005-0000-000000000002'::uuid, 'UNLOCKED', now() - interval '12 hours', NULL)
) AS v(learner_id, item_id, status, started_at, completed_at)
WHERE EXISTS (SELECT 1 FROM curriculum_item ci WHERE ci.id = v.item_id)
ON CONFLICT (learner_id, item_id) DO NOTHING;

-- =============================================================================
-- 9. Learner Activity, Streaks, XP, Wallets, Audits & Weaknesses
-- =============================================================================
INSERT INTO learner_streak (learner_id, current_streak, longest_streak, last_study_date) VALUES
    ('30000000-0000-0000-0000-000000000001', 5, 7, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000004', 9, 14, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000006', 12, 21, (now() AT TIME ZONE 'UTC')::date),
    ('30000000-0000-0000-0000-000000000007', 2, 3, (now() AT TIME ZONE 'UTC')::date - 1)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO learner_streak_day (learner_id, study_date, first_fact_id)
SELECT streak.learner_id, streak.last_study_date - elapsed, gen_random_uuid()
FROM learner_streak streak
CROSS JOIN LATERAL generate_series(0, streak.current_streak - 1) AS elapsed
WHERE streak.learner_id IN (
    '30000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000004',
    '30000000-0000-0000-0000-000000000006',
    '30000000-0000-0000-0000-000000000007'
)
AND NOT EXISTS (SELECT 1 FROM learner_streak_day day WHERE day.learner_id = streak.learner_id)
ON CONFLICT (learner_id, study_date) DO NOTHING;

INSERT INTO learner_level (learner_id, xp, level) VALUES
    ('30000000-0000-0000-0000-000000000001', 260, 3),
    ('30000000-0000-0000-0000-000000000004', 580, 6),
    ('30000000-0000-0000-0000-000000000006', 920, 9),
    ('30000000-0000-0000-0000-000000000007', 95, 2)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_wallet (learner_id, cached_balance) VALUES
    ('30000000-0000-0000-0000-000000000001', 28),
    ('30000000-0000-0000-0000-000000000004', 64),
    ('30000000-0000-0000-0000-000000000006', 110),
    ('30000000-0000-0000-0000-000000000007', 8)
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
    ('50000000-0000-0000-0000-000000000804', '30000000-0000-0000-0000-000000000001', 'speech.shadowing', 'seed-demo-score', 'SETTLED', 'v1', '{"amount":1}'::jsonb, now() - interval '3 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO learner_daily_activity (
    learner_id, activity_date, study_seconds, lesson_count, vocabulary_review_count, grammar_answer_count, toeic_question_count
)
SELECT v.learner_id, v.activity_date, v.study_seconds, v.lesson_count, v.vocab_cnt, v.grammar_cnt, v.toeic_cnt
FROM (
    SELECT '30000000-0000-0000-0000-000000000001'::uuid AS learner_id, (now() AT TIME ZONE 'UTC')::date - d AS activity_date,
           (900 + (d * 180) % 900) AS study_seconds, CASE WHEN d % 2 = 0 THEN 1 ELSE 2 END AS lesson_count,
           (14 + d) AS vocab_cnt, (6 + d % 5) AS grammar_cnt, CASE WHEN d % 4 = 0 THEN 10 ELSE 0 END AS toeic_cnt
    FROM generate_series(0, 12) AS d
    WHERE d < 5 OR d % 2 = 0
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000004'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1250 + (d * 140) % 800), CASE WHEN d % 3 = 0 THEN 2 ELSE 1 END,
           (16 + (d * 2) % 10), (8 + d % 6), CASE WHEN d % 2 = 0 THEN 15 ELSE 0 END
    FROM generate_series(0, 13) AS d
    WHERE d <> 9
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000006'::uuid, (now() AT TIME ZONE 'UTC')::date - d,
           (1600 + (d * 190) % 1000), CASE WHEN d % 2 = 0 THEN 1 ELSE 0 END,
           (18 + d % 8), (12 + d % 6), (25 + (d % 4) * 15)
    FROM generate_series(0, 13) AS d
    WHERE d <> 12
    UNION ALL
    SELECT '30000000-0000-0000-0000-000000000007'::uuid, (now() AT TIME ZONE 'UTC')::date - d, 540, 0, 5, 6, 0
    FROM generate_series(1, 2) AS d
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
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_LISTENING', 89.00, 8, now() - interval '1 day'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_READING', 82.50, 8, now() - interval '5 hours'),
    ('30000000-0000-0000-0000-000000000006', 'GRAMMAR', 91.00, 32, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000006', 'VOCABULARY', 87.50, 40, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR', 62.50, 6, now() - interval '1 day')
ON CONFLICT (learner_id, skill_code) DO UPDATE SET
    score = EXCLUDED.score,
    sample_count = GREATEST(learner_skill_summary.sample_count, EXCLUDED.sample_count);

INSERT INTO learner_weakness (learner_id, weakness_type, weakness_key, severity, sample_count, updated_at) VALUES
    ('30000000-0000-0000-0000-000000000001', 'LESSON_ACTIVITY', 'SHADOWING', 22.50, 8, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000001', 'GRAMMAR_TOPIC', 'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b', 31.00, 7, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000001', 'SHADOWING_ASSESSMENT', 'WORD_MISMATCH', 37.50, 8, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'SHADOWING_ASSESSMENT', 'FLUENCY', 44.00, 5, now() - interval '2 hours'),
    ('30000000-0000-0000-0000-000000000001', 'TOEIC_PART', '2', 38.00, 6, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000001', 'TOEIC_PART', '5', 55.00, 5, now() - interval '1 hour'),
    ('30000000-0000-0000-0000-000000000004', 'LESSON_ACTIVITY', 'SHADOWING', 28.00, 6, now() - interval '4 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_PART', '3', 24.00, 8, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_PART', '7', 43.00, 8, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000006', 'TOEIC_SECTION', 'READING', 21.00, 4, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR_TOPIC', 'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09', 42.00, 6, now() - interval '1 day')
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
    ('30000000-0000-0000-0000-000000000006'::uuid, '0c20867e-38c6-4ce0-88ea-2b17629a606c'::uuid, 'COMPLETED', now() - interval '7 days', now() - interval '4 days', now() - interval '4 days'),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'a22b36d1-512a-415b-b6e8-5a23adadc9bd'::uuid, 'COMPLETED', now() - interval '3 days', now() - interval '1 day', now() - interval '1 day')
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
    ('30000000-0000-0000-0000-000000000006'::uuid, '40000000-0000-0000-0000-000000000114'::uuid, 500, now() - interval '1 day')
) AS v(learner_id, achievement_id, progress, unlocked_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.learner_id)
  AND EXISTS (SELECT 1 FROM achievement_definition a WHERE a.id = v.achievement_id)
ON CONFLICT (learner_id, achievement_id) DO UPDATE SET
    progress = GREATEST(learner_achievement.progress, EXCLUDED.progress),
    unlocked_at = COALESCE(learner_achievement.unlocked_at, EXCLUDED.unlocked_at);

COMMIT;
