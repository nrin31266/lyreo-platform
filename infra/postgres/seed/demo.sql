-- Lyreo Development Seed (Clean Real-Data + Rich Experience Upgrade)
-- Idempotent: purges legacy fake demo-* vocabulary/short-lesson fixtures,
-- preserves imported datasets (Vocabulary 1.3.0, Grammar/TOEIC 2.0.12) and authored lessons,
-- and seeds covers, badge artwork, roleplay scenarios, announcements, missions, and real curated-slice paths.
BEGIN;
SET LOCAL lock_timeout = '10s';

-- =============================================================================
-- 1. Purge polluted legacy fake demo data (keep real datasets and authored lessons)
-- =============================================================================
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

-- Soft-delete old fake short audio/text demo lessons (respecting published lesson immutability trigger)
UPDATE lesson_metadata
SET deleted_at = COALESCE(deleted_at, now()),
    visible = FALSE,
    updated_at = now()
WHERE lesson_id IN (
    '50000000-0000-0000-0000-000000000401', '50000000-0000-0000-0000-000000000402',
    '50000000-0000-0000-0000-000000000403', '50000000-0000-0000-0000-000000000404',
    '50000000-0000-0000-0000-000000000405'
);

-- Archive any old demo-2.0.12 grammar/toeic catalog rows (immutable snapshots are preserved safely)
UPDATE grammar_topic_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE grammar_subtopic_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE grammar_bank_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';
UPDATE toeic_test_catalog SET publication_status = 'ARCHIVED' WHERE id::text LIKE '60000000-%';

-- =============================================================================
-- 2. Demo Learners & Active Free vs Plus Entitlements
-- =============================================================================
INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES
    ('30000000-0000-0000-0000-000000000001', 'offline-demo-minh-anh', 'minh-anh@example.test'),
    ('30000000-0000-0000-0000-000000000002', 'offline-demo-gia-huy', 'gia-huy@example.test'),
    ('30000000-0000-0000-0000-000000000003', 'offline-demo-new', 'learner-moi@example.test'),
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
    ('30000000-0000-0000-0000-000000000001'::uuid, 'Minh Anh', 'CONVERSATION', 'A2', 15, 'LISTENING', 'COMIC_ARI', now() - interval '10 days', now() - interval '10 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000002'::uuid, 'Gia Huy', 'TOEIC', 'B1', 20, 'VOCABULARY', 'KOALA', now() - interval '6 days', now() - interval '6 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000003'::uuid, 'Người học mới', NULL, NULL, NULL, NULL, NULL, now() - interval '30 minutes', NULL),
    ('30000000-0000-0000-0000-000000000004'::uuid, 'Trần Thảo Vy', 'WORK', 'B2', 30, 'SPEAKING', 'COMIC_LUNA', now() - interval '14 days', now() - interval '14 days' + interval '4 minutes'),
    ('30000000-0000-0000-0000-000000000005'::uuid, 'Lê Hoàng Nam', 'TRAVEL', 'A1', 15, 'VOCABULARY', 'TOUCAN', now() - interval '9 days', now() - interval '9 days' + interval '3 minutes'),
    ('30000000-0000-0000-0000-000000000006'::uuid, 'Phạm Khánh Linh', 'EXAM', 'C1', 45, 'READING', 'FLOWER_IRISES', now() - interval '21 days', now() - interval '21 days' + interval '6 minutes'),
    ('30000000-0000-0000-0000-000000000007'::uuid, 'Đỗ Quốc Bảo', 'CONVERSATION', 'B1', 20, 'GRAMMAR', 'RED_PANDA', now() - interval '5 days', now() - interval '5 days' + interval '5 minutes'),
    ('30000000-0000-0000-0000-000000000008'::uuid, 'Vũ Mai Phương', NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', NULL)
) AS v(id, display_name, goal, current_level, daily_minutes, focus_area, avatar_preset, first_seen_at, onboarding_completed_at)
WHERE EXISTS (SELECT 1 FROM app_user u WHERE u.id = v.id)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO user_entitlement_grant (
    id, user_id, feature_key, status, valid_from, valid_until, source_type, source_reference, created_by
) VALUES
    ('50000000-0000-0000-0000-000000000011', '30000000-0000-0000-0000-000000000001', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000012', '30000000-0000-0000-0000-000000000001', 'vocabulary.saved-cards', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000013', '30000000-0000-0000-0000-000000000001', 'speech.shadowing', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000014', '30000000-0000-0000-0000-000000000001', 'toeic.full-access', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000015', '30000000-0000-0000-0000-000000000001', 'grammar.advanced', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000016', '30000000-0000-0000-0000-000000000001', 'chat.tutor', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000017', '30000000-0000-0000-0000-000000000001', 'lesson.plus-lessons', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000018', '30000000-0000-0000-0000-000000000001', 'speaking.roleplay', 'ACTIVE', now() - interval '7 days', now() + interval '365 days', 'DEV_SEED', 'demo-plus', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000021', '30000000-0000-0000-0000-000000000002', 'toeic.full-access', 'ACTIVE', now() - interval '30 days', now() - interval '1 day', 'DEV_SEED', 'demo-expired', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000031', '30000000-0000-0000-0000-000000000004', 'vocabulary.plus-packs', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000032', '30000000-0000-0000-0000-000000000004', 'lesson.plus-lessons', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000033', '30000000-0000-0000-0000-000000000004', 'speaking.roleplay', 'ACTIVE', now() - interval '14 days', now() + interval '180 days', 'ADMIN_MANUAL', 'cohort-workplace-b2', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000034', '30000000-0000-0000-0000-000000000006', 'toeic.full-access', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'seed-demo'),
    ('50000000-0000-0000-0000-000000000035', '30000000-0000-0000-0000-000000000006', 'grammar.advanced', 'ACTIVE', now() - interval '20 days', now() + interval '365 days', 'ADMIN_MANUAL', 'toeic-scholar', 'seed-demo')
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- 3. Deterministic Seed Media Artwork (Covers, Badges, Editorial WebPs)
-- =============================================================================
INSERT INTO media_blob (sha256, mime_type, size_bytes, storage_object_key) VALUES
    ('38a00d60c894e3bb9ea0a465491eea4cd4773a192ef2e14e0584795c1743c138', 'image/webp', 50972, 'media/sha256/38/38a00d60c894e3bb9ea0a465491eea4cd4773a192ef2e14e0584795c1743c138'),
    ('43fb7dd521ad1cbaaef204cfb71c37c960dddaf0a3487ebfaae2e4dd75f124f2', 'image/webp', 32882, 'media/sha256/43/43fb7dd521ad1cbaaef204cfb71c37c960dddaf0a3487ebfaae2e4dd75f124f2'),
    ('bc10127042651a87d1a7027d3cd2fc8ae97e66969d4f60b892adbd1aac83bca6', 'image/webp', 18410, 'media/sha256/bc/bc10127042651a87d1a7027d3cd2fc8ae97e66969d4f60b892adbd1aac83bca6'),
    ('b97261fed6decc9d47143d2310642194e91a4fcebeb5a2dc2522abcba78a1a35', 'image/webp', 9698,  'media/sha256/b9/b97261fed6decc9d47143d2310642194e91a4fcebeb5a2dc2522abcba78a1a35'),
    ('07ec0de43d9fabd025c9a92de630fdb646f623b97ac6a0b185653477ec37bc18', 'image/webp', 8760,  'media/sha256/07/07ec0de43d9fabd025c9a92de630fdb646f623b97ac6a0b185653477ec37bc18'),
    ('546ec7e7e011b5987e60b9dfe884de1afb2b2e3bc3de2880b472bf5a06d28c51', 'image/webp', 17232, 'media/sha256/54/546ec7e7e011b5987e60b9dfe884de1afb2b2e3bc3de2880b472bf5a06d28c51'),
    ('1c987af2bdc925fc80c875c9edbe88074e349895a507249749243bb90bb56ca9', 'image/webp', 14460, 'media/sha256/1c/1c987af2bdc925fc80c875c9edbe88074e349895a507249749243bb90bb56ca9'),
    ('6129e923d4d9aa4186e19f640b4476172bbb304ae8044f496ccb86604216b94e', 'image/webp', 8996,  'media/sha256/61/6129e923d4d9aa4186e19f640b4476172bbb304ae8044f496ccb86604216b94e'),
    ('32573650fcda5415252be0ac494d20bae8096494045d63b54f228e927929792d', 'image/webp', 13758, 'media/sha256/32/32573650fcda5415252be0ac494d20bae8096494045d63b54f228e927929792d'),
    ('94930b8f2d121545c0658998b73c4e6caf97c5d480b095fe8ff483cb52dcc201', 'image/webp', 15078, 'media/sha256/94/94930b8f2d121545c0658998b73c4e6caf97c5d480b095fe8ff483cb52dcc201'),
    ('d7af3f40dc9b387e80ca0cb038154e7b6903adaea3f6c58df70b94313bb5641d', 'image/webp', 10286, 'media/sha256/d7/d7af3f40dc9b387e80ca0cb038154e7b6903adaea3f6c58df70b94313bb5641d'),
    ('00688b4b57e0af2fa033cfd2e71b7e3788d7014cbfd4ec9112b8e6f971a26362', 'image/webp', 13706, 'media/sha256/00/00688b4b57e0af2fa033cfd2e71b7e3788d7014cbfd4ec9112b8e6f971a26362'),
    ('ebf86e851802c1ad749affe277c1aece69ea0bb7ec02bdb3ef0ce75bb4f6b15c', 'image/webp', 10288, 'media/sha256/eb/ebf86e851802c1ad749affe277c1aece69ea0bb7ec02bdb3ef0ce75bb4f6b15c'),
    ('bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78'),
    ('cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6'),
    ('c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04'),
    ('3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9')
ON CONFLICT (sha256) DO NOTHING;

INSERT INTO content_asset (id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by) VALUES
    ('70000000-0000-0000-0000-000000000111', '38a00d60c894e3bb9ea0a465491eea4cd4773a192ef2e14e0584795c1743c138', 'media/sha256/38/38a00d60c894e3bb9ea0a465491eea4cd4773a192ef2e14e0584795c1743c138', 'image/webp', 50972, 'path-cover-realworld.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000112', '43fb7dd521ad1cbaaef204cfb71c37c960dddaf0a3487ebfaae2e4dd75f124f2', 'media/sha256/43/43fb7dd521ad1cbaaef204cfb71c37c960dddaf0a3487ebfaae2e4dd75f124f2', 'image/webp', 32882, 'path-cover-workplace.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000113', 'bc10127042651a87d1a7027d3cd2fc8ae97e66969d4f60b892adbd1aac83bca6', 'media/sha256/bc/bc10127042651a87d1a7027d3cd2fc8ae97e66969d4f60b892adbd1aac83bca6', 'image/webp', 18410, 'path-cover-toeic.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000201', 'b97261fed6decc9d47143d2310642194e91a4fcebeb5a2dc2522abcba78a1a35', 'media/sha256/b9/b97261fed6decc9d47143d2310642194e91a4fcebeb5a2dc2522abcba78a1a35', 'image/webp', 9698,  'badge-streak-7.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000202', '07ec0de43d9fabd025c9a92de630fdb646f623b97ac6a0b185653477ec37bc18', 'media/sha256/07/07ec0de43d9fabd025c9a92de630fdb646f623b97ac6a0b185653477ec37bc18', 'image/webp', 8760,  'badge-streak-30.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000203', '546ec7e7e011b5987e60b9dfe884de1afb2b2e3bc3de2880b472bf5a06d28c51', 'media/sha256/54/546ec7e7e011b5987e60b9dfe884de1afb2b2e3bc3de2880b472bf5a06d28c51', 'image/webp', 17232, 'badge-streak-100.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000204', '1c987af2bdc925fc80c875c9edbe88074e349895a507249749243bb90bb56ca9', 'media/sha256/1c/1c987af2bdc925fc80c875c9edbe88074e349895a507249749243bb90bb56ca9', 'image/webp', 14460, 'badge-streak-365.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000205', '6129e923d4d9aa4186e19f640b4476172bbb304ae8044f496ccb86604216b94e', 'media/sha256/61/6129e923d4d9aa4186e19f640b4476172bbb304ae8044f496ccb86604216b94e', 'image/webp', 8996,  'badge-lessons-10.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000206', '32573650fcda5415252be0ac494d20bae8096494045d63b54f228e927929792d', 'media/sha256/32/32573650fcda5415252be0ac494d20bae8096494045d63b54f228e927929792d', 'image/webp', 13758, 'badge-lessons-50.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000207', '94930b8f2d121545c0658998b73c4e6caf97c5d480b095fe8ff483cb52dcc201', 'media/sha256/94/94930b8f2d121545c0658998b73c4e6caf97c5d480b095fe8ff483cb52dcc201', 'image/webp', 15078, 'badge-lessons-150.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000208', 'd7af3f40dc9b387e80ca0cb038154e7b6903adaea3f6c58df70b94313bb5641d', 'media/sha256/d7/d7af3f40dc9b387e80ca0cb038154e7b6903adaea3f6c58df70b94313bb5641d', 'image/webp', 10286, 'badge-vocab-500.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000209', '00688b4b57e0af2fa033cfd2e71b7e3788d7014cbfd4ec9112b8e6f971a26362', 'media/sha256/00/00688b4b57e0af2fa033cfd2e71b7e3788d7014cbfd4ec9112b8e6f971a26362', 'image/webp', 13706, 'badge-vocab-2500.webp', false, 'seed-demo'),
    ('70000000-0000-0000-0000-000000000210', 'ebf86e851802c1ad749affe277c1aece69ea0bb7ec02bdb3ef0ce75bb4f6b15c', 'media/sha256/eb/ebf86e851802c1ad749affe277c1aece69ea0bb7ec02bdb3ef0ce75bb4f6b15c', 'image/webp', 10288, 'badge-toeic-master.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000105', 'c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'media/sha256/c0/c03327391d93f2020f5d50478eeaa27653a4bd1df8df75a8f54a58b99826eb04', 'image/webp', 6960,  'vocab-airport-boarding.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000106', 'bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'media/sha256/bf/bf4e92fd4fe653bba15709b477f6922e2426549e1c67661abc6be915c343be78', 'image/webp', 7300,  'vocab-coffee-ordering.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000107', 'cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'media/sha256/cf/cffdfb8e4dca1dfd00da9eeca3c83c2f9079fd0dd8430ba2fba63b67e759f5d6', 'image/webp', 11108, 'vocab-office-standup.webp', false, 'seed-demo'),
    ('50000000-0000-0000-0000-000000000108', '3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'media/sha256/36/3652f161358140440805622f04a02dbb2ff9544e4975efb66d74af24d9c9e1a9', 'image/webp', 5032,  'vocab-hotel-concierge.webp', false, 'seed-demo')
ON CONFLICT (id) DO NOTHING;

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
-- 5. Tiered Achievements with Profile Badges (14) + Enriched Missions (12)
-- =============================================================================
DELETE FROM achievement_definition WHERE code IN ('TEN_LESSONS', 'STREAK_7_DAYS', 'STREAK_14_DAYS');

INSERT INTO achievement_definition (
    id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled,
    badge_asset_id, badge_tier, category
) VALUES
    ('40000000-0000-0000-0000-000000000101', 'STREAK_7', 'Nhịp học 7 ngày', 'Duy trì thói quen học tiếng Anh 7 ngày liên tiếp.', 'STREAK_DAYS', 7, 100, 10, 'dev-v1', true, '70000000-0000-0000-0000-000000000201', 'BRONZE', 'STREAK'),
    ('40000000-0000-0000-0000-000000000102', 'STREAK_30', 'Ngọn lửa 30 ngày', 'Hoàn thành 30 ngày học liên tiếp không đứt quãng.', 'STREAK_DAYS', 30, 350, 35, 'dev-v1', true, '70000000-0000-0000-0000-000000000202', 'SILVER', 'STREAK'),
    ('40000000-0000-0000-0000-000000000103', 'STREAK_100', 'Bậc thầy Kiên trì 100 ngày', 'Chiến binh học tập bền bỉ suốt 100 ngày liên tiếp.', 'STREAK_DAYS', 100, 1000, 100, 'dev-v1', true, '70000000-0000-0000-0000-000000000203', 'GOLD', 'STREAK'),
    ('40000000-0000-0000-0000-000000000104', 'STREAK_365', 'Huyền thoại 365 ngày', 'Giữ chuỗi học tập trọn vẹn một năm dương lịch.', 'STREAK_DAYS', 365, 3650, 300, 'dev-v1', true, '70000000-0000-0000-0000-000000000204', 'LEGENDARY', 'STREAK'),
    ('40000000-0000-0000-0000-000000000105', 'LESSONS_10', 'Người nghe Bền bỉ', 'Hoàn thành 10 bài nghe-chép chính tả hoặc shadowing khác nhau.', 'DISTINCT_LESSON_COMPLETED', 10, 150, 15, 'dev-v1', true, '70000000-0000-0000-0000-000000000205', 'BRONZE', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000106', 'LESSONS_50', 'Giọng đọc Chuẩn xác', 'Hoàn thành 50 bài học với độ chính xác phát âm cao.', 'DISTINCT_LESSON_COMPLETED', 50, 600, 60, 'dev-v1', true, '70000000-0000-0000-0000-000000000206', 'GOLD', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000107', 'LESSONS_150', 'Bậc thầy Âm thanh', 'Chinh phục 150 bài luyện nghe và shadowing.', 'DISTINCT_LESSON_COMPLETED', 150, 1800, 180, 'dev-v1', true, '70000000-0000-0000-0000-000000000207', 'PLATINUM', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000108', 'VOCAB_100', 'Khởi đầu Vốn từ 100', 'Hoàn thành 100 lượt ôn tập thẻ từ vựng qua SRS.', 'VOCABULARY_REVIEW', 100, 120, 12, 'dev-v1', true, '70000000-0000-0000-0000-000000000208', 'BRONZE', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000109', 'VOCAB_500', 'Người Sưu tầm Từ vựng', 'Hoàn thành 500 lượt ôn tập từ vựng trong hệ thống SRS.', 'VOCABULARY_REVIEW', 500, 450, 45, 'dev-v1', true, '70000000-0000-0000-0000-000000000208', 'SILVER', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000110', 'VOCAB_2500', 'Từ điển Sống 2500', 'Đạt cột mốc 2.500 lượt ôn tập ghi nhớ sâu.', 'VOCABULARY_REVIEW', 2500, 1600, 160, 'dev-v1', true, '70000000-0000-0000-0000-000000000209', 'GOLD', 'VOCABULARY'),
    ('40000000-0000-0000-0000-000000000111', 'TOEIC_25', 'Chiến sĩ TOEIC Tập sự', 'Hoàn thành 25 lượt luyện tập TOEIC Drill hoặc Full Test.', 'TOEIC_DRILL_COMPLETED', 25, 400, 40, 'dev-v1', true, '70000000-0000-0000-0000-000000000210', 'SILVER', 'TOEIC'),
    ('40000000-0000-0000-0000-000000000112', 'TOEIC_100', 'Chuyên gia Đề thi TOEIC', 'Hoàn thành 100 phiên luyện tập TOEIC có chấm điểm.', 'TOEIC_DRILL_COMPLETED', 100, 1400, 140, 'dev-v1', true, '70000000-0000-0000-0000-000000000210', 'PLATINUM', 'TOEIC'),
    ('40000000-0000-0000-0000-000000000113', 'SHADOWING_300', 'Phát âm Tự nhiên', 'Luyện nói theo 300 câu hội thoại với phản hồi AI.', 'SHADOWING_SENTENCE', 300, 500, 50, 'dev-v1', true, '70000000-0000-0000-0000-000000000206', 'SILVER', 'LESSONS'),
    ('40000000-0000-0000-0000-000000000114', 'GRAMMAR_500', 'Kiến trúc sư Ngữ pháp', 'Giải đúng và hoàn thành 500 câu bài tập ngữ pháp.', 'GRAMMAR_ANSWER', 500, 750, 75, 'dev-v1', true, '70000000-0000-0000-0000-000000000209', 'GOLD', 'MASTERY')
ON CONFLICT (code) DO UPDATE SET
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    target = EXCLUDED.target,
    xp_reward = EXCLUDED.xp_reward,
    diamond_reward = EXCLUDED.diamond_reward,
    badge_asset_id = EXCLUDED.badge_asset_id,
    badge_tier = EXCLUDED.badge_tier,
    category = EXCLUDED.category,
    enabled = EXCLUDED.enabled;

INSERT INTO mission_definition (
    id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled, category, difficulty
) VALUES
    ('10000000-0000-0000-0000-000000000101', 'DAILY_VOCAB_15', 'Ôn tập 15 thẻ từ vựng hôm nay', 'VOCABULARY_REVIEW', 15, 25, 3, 'DAILY', 'dev-v1', true, 'VOCAB', 'EASY'),
    ('10000000-0000-0000-0000-000000000102', 'DAILY_VOCAB_35', 'Bứt phá 35 thẻ từ vựng SRS', 'VOCABULARY_REVIEW', 35, 50, 6, 'DAILY', 'dev-v1', true, 'VOCAB', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000103', 'DAILY_SHADOWING_5', 'Luyện nói 5 câu Shadowing chuẩn ngữ điệu', 'SHADOWING_SENTENCE', 5, 30, 4, 'DAILY', 'dev-v1', true, 'LESSON', 'EASY'),
    ('10000000-0000-0000-0000-000000000104', 'DAILY_GRAMMAR_10', 'Hoàn thành 10 câu Ngữ pháp ứng dụng', 'GRAMMAR_ANSWER', 10, 35, 4, 'DAILY', 'dev-v1', true, 'GRAMMAR', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000105', 'DAILY_LESSON_1', 'Hoàn thành trọn vẹn 1 bài học tình huống', 'LESSON_COMPLETED', 1, 45, 5, 'DAILY', 'dev-v1', true, 'LESSON', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000106', 'DAILY_TOEIC_DRILL_1', 'Luyện 1 bài TOEIC Drill nhanh 10 phút', 'TOEIC_DRILL_COMPLETED', 1, 40, 5, 'DAILY', 'dev-v1', true, 'TOEIC', 'HARD'),
    ('10000000-0000-0000-0000-000000000107', 'WEEKLY_VOCAB_150', 'Ôn tập 150 lượt từ vựng trong tuần', 'VOCABULARY_REVIEW', 150, 120, 15, 'WEEKLY', 'dev-v1', true, 'VOCAB', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000108', 'WEEKLY_LESSON_4', 'Hoàn thành 4 bài nghe & shadowing trong tuần', 'LESSON_COMPLETED', 4, 150, 18, 'WEEKLY', 'dev-v1', true, 'LESSON', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000109', 'WEEKLY_SHADOWING_40', 'Chinh phục 40 câu Shadowing mỗi tuần', 'SHADOWING_SENTENCE', 40, 160, 20, 'WEEKLY', 'dev-v1', true, 'LESSON', 'HARD'),
    ('10000000-0000-0000-0000-000000000110', 'WEEKLY_GRAMMAR_60', 'Giải quyết 60 câu Ngữ pháp chuyên sâu', 'GRAMMAR_ANSWER', 60, 140, 15, 'WEEKLY', 'dev-v1', true, 'GRAMMAR', 'NORMAL'),
    ('10000000-0000-0000-0000-000000000111', 'WEEKLY_TOEIC_3', 'Hoàn thành 3 phiên TOEIC Drill có mục tiêu', 'TOEIC_DRILL_COMPLETED', 3, 200, 25, 'WEEKLY', 'dev-v1', true, 'TOEIC', 'HARD'),
    ('10000000-0000-0000-0000-000000000112', 'WEEKLY_ALL_ROUNDER', 'Đạt 25 câu luyện nói phản xạ tự nhiên', 'SHADOWING_SENTENCE', 25, 110, 12, 'WEEKLY', 'dev-v1', true, 'GENERAL', 'EASY')
ON CONFLICT (code) DO UPDATE SET
    title = EXCLUDED.title,
    target = EXCLUDED.target,
    xp_reward = EXCLUDED.xp_reward,
    diamond_reward = EXCLUDED.diamond_reward,
    category = EXCLUDED.category,
    difficulty = EXCLUDED.difficulty,
    enabled = EXCLUDED.enabled;

-- =============================================================================
-- 6. Real-World AI Voice Roleplay Scenarios (6 published scenarios)
-- =============================================================================
INSERT INTO speaking_scenario (
    id, code, title, title_vi, category, cefr_level, cover_asset_id,
    ai_role_name, ai_persona, learner_role, setting_description,
    opening_line, system_prompt, objectives_json, tts_voice,
    target_turns, estimated_minutes, access_mode, required_feature_key, status
) VALUES
    (
        '70000000-0000-0000-0000-000000000801',
        'CAFE_MORNING_RUSH',
        'Morning Rush Custom Coffee Order',
        'Gọi đồ uống tùy chỉnh giờ cao điểm',
        'DAILY_LIFE',
        'A2',
        '50000000-0000-0000-0000-000000000106',
        'Mia (Specialty Barista)',
        'Friendly, fast-paced specialty coffee shop barista during the morning rush in downtown Seattle. Helpful with milk alternatives and pastry pairings.',
        'Customer ordering before work',
        'A bustling neighborhood espresso bar at 8:15 AM with an espresso grinder humming behind the counter.',
        'Good morning! Welcome to Cedar Roasters. What can I get started for you today?',
        'Stay upbeat, confirm size/milk/ice preferences naturally, suggest one warm pastry option, and give a clear pickup instruction.',
        '["Order a custom coffee drink specifying size and milk type","Ask about pastry availability or allergens","Complete payment and confirm counter pickup"]'::jsonb,
        'af_heart', 6, 4, 'PUBLIC', NULL, 'PUBLISHED'
    ),
    (
        '70000000-0000-0000-0000-000000000802',
        'HOTEL_CHECKIN_UPGRADE',
        'Hotel Check-in & Quiet Room Request',
        'Nhận phòng khách sạn & yêu cầu phòng yên tĩnh',
        'TRAVEL',
        'A2',
        '50000000-0000-0000-0000-000000000108',
        'Daniel (Front Desk Concierge)',
        'Polite, attentive boutique hotel receptionist focused on guest comfort and clear check-in instructions.',
        'Tired international traveler checking in for 3 nights',
        'Evening check-in lobby at a boutique hotel after an international flight.',
        'Good evening and welcome to the Harborview Hotel! Do you have a reservation under your name?',
        'Verify booking details, respond constructively to room noise/floor requests, explain breakfast hours and Wi-Fi password clearly.',
        '["Confirm reservation details and check-in dates","Polite request for a higher-floor quiet room","Ask about breakfast hours and late check-out"]'::jsonb,
        'am_adam', 6, 5, 'PUBLIC', NULL, 'PUBLISHED'
    ),
    (
        '70000000-0000-0000-0000-000000000803',
        'CLINIC_CONSULTATION',
        'Walk-in Clinic Symptom Consultation',
        'Mô tả triệu chứng tại phòng khám',
        'HEALTH',
        'B1',
        NULL,
        'Dr. Sarah Chen',
        'Calm, empathetic primary-care physician who asks clear follow-up questions about symptoms, duration, and allergies.',
        'Patient visiting a walk-in clinic with a persistent sore throat and cough',
        'Quiet examination room at a neighborhood medical clinic.',
        'Hi there, thanks for coming in today. What symptoms have been bothering you, and how long have they lasted?',
        'Ask concise clinical follow-up questions one at a time, confirm medication allergies, and explain simple rest and prescription instructions.',
        '["Describe main symptoms and when they started","Answer questions about fever and allergies","Confirm medicine dosage and follow-up advice"]'::jsonb,
        'af_bella', 7, 5, 'PUBLIC', NULL, 'PUBLISHED'
    ),
    (
        '70000000-0000-0000-0000-000000000804',
        'ENGINEERING_STANDUP',
        'Monday Engineering Standup & Blockers',
        'Họp Standup kỹ thuật & báo cáo điểm nghẽn',
        'WORKPLACE',
        'B1',
        '50000000-0000-0000-0000-000000000107',
        'Alex (Engineering Lead)',
        'Pragmatic engineering manager leading a 15-minute sprint standup. Focused on clear status updates, realistic ETA, and unblocking teammates.',
        'Software engineer presenting yesterday''s progress and an API blocker',
        'Morning video standup with a distributed product engineering squad.',
        'Morning everyone! Let''s kick off our Monday sync. How did your release prep go yesterday, and what''s on your plate today?',
        'Keep discussion crisp and technical. Probe politely for concrete root causes on any blocker and help agree on an owner and ETA.',
        '["Summarize completed work clearly using past simple","State today''s concrete sprint deliverable","Explain a technical blocker and request specific help"]'::jsonb,
        'am_michael', 8, 6, 'FEATURE', 'speaking.roleplay', 'PUBLISHED'
    ),
    (
        '70000000-0000-0000-0000-000000000805',
        'CLIENT_NEGOTIATION',
        'Client Scope & Timeline Negotiation',
        'Đàm phán phạm vi & tiến độ với khách hàng',
        'BUSINESS',
        'B2',
        NULL,
        'Claire (Enterprise Client Director)',
        'Sharp, results-oriented enterprise stakeholder who wants extra features before launch without extending the delivery deadline.',
        'Product Delivery Manager balancing scope and engineering capacity',
        'Quarterly roadmap alignment call ahead of an enterprise launch.',
        'Thanks for making time today. Our leadership team really wants the custom analytics export included in next Friday''s launch — can your team fit that in?',
        'Push diplomatically for Friday delivery first, then respond realistically when offered a phased rollout or trade-off.',
        '["Acknowledge the client urgency professionally","Propose a phased Phase-1 / Phase-2 tradeoff","Lock down a concrete sign-off date"]'::jsonb,
        'bf_emma', 8, 7, 'FEATURE', 'speaking.roleplay', 'PUBLISHED'
    ),
    (
        '70000000-0000-0000-0000-000000000806',
        'AIRPORT_REBOOKING_PRO',
        'Missed Connection Airport Rebooking',
        'Xử lý đổi chuyến bay khẩn cấp tại sân bay',
        'TRAVEL',
        'B2',
        '50000000-0000-0000-0000-000000000105',
        'Marcus (Senior Gate Agent)',
        'Experienced airline service desk agent managing rebooking options after a weather delay caused a missed connecting flight.',
        'Passenger needing an urgent seat on the next evening flight plus meal/hotel voucher',
        'Transfer service desk at Terminal 2 right after landing 40 minutes late.',
        'I understand your connecting flight has already departed. Let me pull up your itinerary — what''s your final destination and urgency today?',
        'Offer realistic standby and confirmed alternatives, explain baggage transfer rules, and discuss voucher eligibility when asked politely.',
        '["Explain the missed connection clearly with booking code","Compare standby vs confirmed next-day routing","Request baggage tracking and overnight voucher details"]'::jsonb,
        'bm_george', 8, 6, 'FEATURE', 'speaking.roleplay', 'PUBLISHED'
)
ON CONFLICT (code) DO UPDATE SET
    title = EXCLUDED.title,
    title_vi = EXCLUDED.title_vi,
    category = EXCLUDED.category,
    cefr_level = EXCLUDED.cefr_level,
    cover_asset_id = EXCLUDED.cover_asset_id,
    ai_role_name = EXCLUDED.ai_role_name,
    ai_persona = EXCLUDED.ai_persona,
    learner_role = EXCLUDED.learner_role,
    setting_description = EXCLUDED.setting_description,
    opening_line = EXCLUDED.opening_line,
    system_prompt = EXCLUDED.system_prompt,
    objectives_json = EXCLUDED.objectives_json,
    tts_voice = EXCLUDED.tts_voice,
    target_turns = EXCLUDED.target_turns,
    estimated_minutes = EXCLUDED.estimated_minutes,
    access_mode = EXCLUDED.access_mode,
    required_feature_key = EXCLUDED.required_feature_key,
    status = EXCLUDED.status,
    updated_at = now();

-- =============================================================================
-- 7. Human-Readable Learner Notifications & Announcements
-- =============================================================================
INSERT INTO learner_notification (
    id, title, body, category, target_audience, status, published_at, expires_at, created_by
) VALUES
    (
        '70000000-0000-0000-0000-000000000901',
        'Lộ trình Thực chiến A2–B1 mới đã mở!',
        'Khám phá lộ trình ghép sẵn từng chặng ngắn từ bài nghe tình huống, cụm từ vựng chọn lọc và hội thoại AI tại quán cà phê, khách sạn.',
        'SYSTEM', 'ALL', 'PUBLISHED', now() - interval '2 days', now() + interval '60 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000902',
        'Nhắc giữ chuỗi học 10 phút tối nay',
        'Chỉ cần ôn 15 thẻ từ vựng hoặc nói 5 câu Shadowing trước 23:59 UTC để bảo vệ chuỗi ngày học của bạn và mở huy hiệu 7 ngày.',
        'STUDY_REMINDER', 'ALL', 'PUBLISHED', now() - interval '6 hours', now() + interval '7 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000903',
        'Mở khóa 3 tình huống Voice Roleplay Công sở B1–B2',
        'Thành viên Plus hiện có thể luyện phản xạ họp Standup, đàm phán khách hàng và xử lý đổi chuyến bay không giới hạn mỗi ngày.',
        'REWARD', 'PLUS', 'PUBLISHED', now() - interval '1 day', now() + interval '30 days', 'admin'
    ),
    (
        '70000000-0000-0000-0000-000000000904',
        'Trải nghiệm gói Lyreo Plus — Học sâu & Lộ trình Công sở',
        'Nâng cấp Lyreo Plus để mở khóa bộ từ vựng TOEIC & Technology chuyên sâu, bài luyện nâng cao và 100 lượt Voice Roleplay/ngày.',
        'PROMO', 'FREE', 'PUBLISHED', now() - interval '12 hours', now() + interval '30 days', 'admin'
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

-- Path 1 sections
INSERT INTO curriculum_section (id, path_id, title, position) VALUES
    ('70000000-0000-0000-0000-000000000611', '70000000-0000-0000-0000-000000000601', 'Chặng 1 · Sinh hoạt & Quán cà phê buổi sáng', 1),
    ('70000000-0000-0000-0000-000000000612', '70000000-0000-0000-0000-000000000601', 'Chặng 2 · Kể chuyện & Giao tiếp hằng ngày', 2),
    ('70000000-0000-0000-0000-000000000613', '70000000-0000-0000-0000-000000000601', 'Chặng 3 · Du lịch, Khách sạn & Di chuyển', 3),
    ('70000000-0000-0000-0000-000000000614', '70000000-0000-0000-0000-000000000601', 'Chặng 4 · Phản xạ Y tế & Kiểm tra Nghe', 4),
    ('70000000-0000-0000-0000-000000000621', '70000000-0000-0000-0000-000000000602', 'Chặng 1 · Họp Standup & Từ vựng Công nghệ', 1),
    ('70000000-0000-0000-0000-000000000622', '70000000-0000-0000-0000-000000000602', 'Chặng 2 · Cấu trúc Học thuật & Đọc-Nghe B2', 2),
    ('70000000-0000-0000-0000-000000000623', '70000000-0000-0000-0000-000000000602', 'Chặng 3 · Đàm phán Khách hàng & TOEIC Mục tiêu', 3)
ON CONFLICT (id) DO NOTHING;

-- Tag B2 situational lesson as Plus when present
UPDATE lesson_metadata
SET access_mode = 'FEATURE', required_feature_key = 'lesson.plus-lessons', visible = true
WHERE lesson_id = '494cfdbc-b24e-4045-9c7f-96d655d0c16c';

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
    ('70000000-0000-0001-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 4, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000801'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"targetTurns":6}'::jsonb),
    ('70000000-0000-0001-0000-000000000015'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 5, 'LESSON', '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING","sentenceIds":["cd0c3f7f-2b14-4222-a794-b4b31694cbe9","f3b87f81-880d-412b-b78f-b162bb2bb6df","a7853141-b8b9-4d0c-98f9-cee9d7e068f6","74f8900f-e7f1-49ba-9889-3ce5ef974829","6127b404-81c1-4e48-95f7-68c15df4712a"]}'::jsonb),
    ('70000000-0000-0001-0000-000000000016'::uuid, '70000000-0000-0000-0000-000000000611'::uuid, 6, 'LESSON', '1d053d6a-87a5-49b7-a2fb-dceb4c8fc461'::uuid, false, 'NONE',
     '{"activityType":"DICTATION","sentenceIds":["d729cb90-5450-47fa-b710-c369ad75f0cd","237b45ca-7fcd-42e1-ba1d-3b9ea1260504","51d970f2-ce98-4824-9218-9f23d2b7cb05","d951fd63-df24-45d8-b05e-565014c9fd47","faf7f74f-d904-4e9c-b794-7ba909936a50"]}'::jsonb),
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
    ('70000000-0000-0001-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 2, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000802'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"targetTurns":6}'::jsonb),
    ('70000000-0000-0001-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 3, 'LESSON', '2cfe1fcd-017c-4512-b071-e981c0945632'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"activityType":"SHADOWING"}'::jsonb),
    ('70000000-0000-0001-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000613'::uuid, 4, 'GRAMMAR_PRACTICE', 'f73e5cfd-d28a-5ef1-9741-2f57b9b84403'::uuid, false, 'NONE',
     '{"questionCount":8}'::jsonb),
    -- Section 4
    ('70000000-0000-0001-0000-000000000013'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 1, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000803'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"targetTurns":7}'::jsonb),
    ('70000000-0000-0001-0000-000000000014'::uuid, '70000000-0000-0000-0000-000000000614'::uuid, 2, 'TOEIC_DRILL', '8832fd01-4378-5ae4-acc6-20aa57af5ba8'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[1,2],"questionCount":10}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
 OR (v.content_type = 'SPEAKING_SCENARIO' AND EXISTS (SELECT 1 FROM speaking_scenario s WHERE s.id = v.ref_id))
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
    ('70000000-0000-0002-0000-000000000003'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 3, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000804'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"targetTurns":8}'::jsonb),
    ('70000000-0000-0002-0000-000000000012'::uuid, '70000000-0000-0000-0000-000000000621'::uuid, 4, 'LESSON', '23a70d96-5bb5-4b61-ad11-b083322a80b7'::uuid, true, 'PREVIOUS_REQUIRED',
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
    ('70000000-0000-0002-0000-000000000009'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 2, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000805'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"targetTurns":8}'::jsonb),
    ('70000000-0000-0002-0000-000000000010'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 3, 'TOEIC_DRILL', '767147a7-02a4-5ef6-a725-d3615b042d4c'::uuid, true, 'PREVIOUS_REQUIRED',
     '{"parts":[5,6,7],"questionCount":15}'::jsonb),
    ('70000000-0000-0002-0000-000000000011'::uuid, '70000000-0000-0000-0000-000000000623'::uuid, 4, 'SPEAKING_SCENARIO', '70000000-0000-0000-0000-000000000806'::uuid, false, 'NONE',
     '{"targetTurns":8}'::jsonb)
) AS v(id, section_id, position, content_type, ref_id, required, unlock_rule, cfg)
WHERE
    (v.content_type = 'VOCABULARY_REVIEW' AND EXISTS (SELECT 1 FROM vocabulary_unit u WHERE u.id = v.ref_id))
 OR (v.content_type = 'LESSON' AND EXISTS (SELECT 1 FROM lesson l WHERE l.id = v.ref_id))
 OR (v.content_type = 'GRAMMAR_PRACTICE' AND EXISTS (SELECT 1 FROM grammar_topic_catalog g WHERE g.id = v.ref_id))
 OR (v.content_type IN ('TOEIC_DRILL','TOEIC_TEST') AND EXISTS (SELECT 1 FROM toeic_test_catalog t WHERE t.id = v.ref_id))
 OR (v.content_type = 'SPEAKING_SCENARIO' AND EXISTS (SELECT 1 FROM speaking_scenario s WHERE s.id = v.ref_id))
ON CONFLICT (id) DO UPDATE SET
    content_reference_id = EXCLUDED.content_reference_id,
    config_json = EXCLUDED.config_json,
    position = EXCLUDED.position;

-- Enrollments & initial progression for demo learners
INSERT INTO curriculum_enrollment (learner_id, path_id, status, started_at)
SELECT v.learner_id, v.path_id, 'ACTIVE', v.started_at
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, now() - interval '3 days'),
    ('30000000-0000-0000-0000-000000000004'::uuid, '70000000-0000-0000-0000-000000000602'::uuid, now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000005'::uuid, '70000000-0000-0000-0000-000000000601'::uuid, now() - interval '2 days')
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
    ('30000000-0000-0000-0000-000000000007', 2, 3, (now() AT TIME ZONE 'UTC')::date - 1)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO learner_level (learner_id, xp, level) VALUES
    ('30000000-0000-0000-0000-000000000001', 260, 3),
    ('30000000-0000-0000-0000-000000000004', 580, 6),
    ('30000000-0000-0000-0000-000000000005', 145, 2),
    ('30000000-0000-0000-0000-000000000006', 920, 9),
    ('30000000-0000-0000-0000-000000000007', 95, 2)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_wallet (learner_id, cached_balance) VALUES
    ('30000000-0000-0000-0000-000000000001', 28),
    ('30000000-0000-0000-0000-000000000004', 64),
    ('30000000-0000-0000-0000-000000000005', 16),
    ('30000000-0000-0000-0000-000000000006', 110),
    ('30000000-0000-0000-0000-000000000007', 8)
ON CONFLICT (learner_id) DO NOTHING;

INSERT INTO diamond_transaction (
    id, learner_id, transaction_type, amount, idempotency_key, reference_type, reference_id, reason, rule_version, created_at
) VALUES
    ('70000000-0000-0000-0000-000000000721', '30000000-0000-0000-0000-000000000004', 'MISSION_REWARD', 18, 'demo:v2:mission:0004:weekly-lesson', 'MISSION', '10000000-0000-0000-0000-000000000108', 'Hoàn thành nhiệm vụ tuần 4 bài học', 'dev-v1', now() - interval '2 days'),
    ('70000000-0000-0000-0000-000000000722', '30000000-0000-0000-0000-000000000004', 'ADMIN_ADJUSTMENT', 30, 'demo:v2:admin:0004:cohort-grant', 'ADMIN_ADJUSTMENT', NULL, 'Thưởng học viên tích cực lộ trình B2', 'dev-v1', now() - interval '1 day'),
    ('70000000-0000-0000-0000-000000000723', '30000000-0000-0000-0000-000000000006', 'ACHIEVEMENT_REWARD', 10, 'demo:v2:ach:0006:streak7', 'ACHIEVEMENT', '40000000-0000-0000-0000-000000000101', 'Mở khóa huy hiệu Nhịp học 7 ngày', 'dev-v1', now() - interval '1 day')
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
    ('30000000-0000-0000-0000-000000000001', (now() AT TIME ZONE 'UTC')::date - 2, 1180, 1, 16, 8, 0),
    ('30000000-0000-0000-0000-000000000001', (now() AT TIME ZONE 'UTC')::date,     960,  1, 15, 6, 0),
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
    ('30000000-0000-0000-0000-000000000001', 'LISTENING', 81.00, 10, now() - interval '3 hours'),
    ('30000000-0000-0000-0000-000000000001', 'VOCABULARY', 85.40, 24, now() - interval '2 hours'),
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
    ('30000000-0000-0000-0000-000000000007', 'GRAMMAR_TOPIC', 'de2eaf7d-ce3f-5fb2-9a3b-c4f3c7393c09', 42.00, 6, now() - interval '1 day')
ON CONFLICT (learner_id, weakness_type, weakness_key) DO NOTHING;

COMMIT;
