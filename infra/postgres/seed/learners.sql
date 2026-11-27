-- Local learner fixtures. The seed command supplies the subjects from Keycloak.
BEGIN;

CREATE TEMP TABLE demo_learner_seed (
    id uuid, subject uuid, email text, name text, goal text, level text,
    minutes integer, focus text, age interval, fallback_subject text
) ON COMMIT DROP;

INSERT INTO demo_learner_seed VALUES
    ('30000000-0000-0000-0000-000000000001', '__MINH_ANH_SUBJECT__',
     'minh-anh@example.test', 'Minh Anh', 'CONVERSATION', 'A2', 10, 'LISTENING', interval '2 days', 'lyreo-learner-minh-anh'),
    ('30000000-0000-0000-0000-000000000002', '__GIA_HUY_SUBJECT__',
     'gia-huy@example.test', 'Gia Huy', 'TOEIC', 'B1', 20, 'VOCABULARY', interval '1 day', 'lyreo-learner-gia-huy'),
    ('30000000-0000-0000-0000-000000000003', '__NEW_LEARNER_SUBJECT__',
     'learner-moi@example.test', NULL, NULL, NULL, NULL, NULL, interval '30 minutes', 'lyreo-learner-tuan-kiet');

-- Reuse the fixture profile when Keycloak assigns a new subject to the demo account.
UPDATE app_user u SET keycloak_subject = demo.subject::text, email_snapshot = demo.email
FROM demo_learner_seed demo
WHERE u.id = demo.id AND (u.email_snapshot = demo.email OR u.keycloak_subject = demo.fallback_subject)
  AND u.keycloak_subject <> demo.subject::text
  AND NOT EXISTS (SELECT 1 FROM app_user real_user WHERE real_user.keycloak_subject = demo.subject::text);

INSERT INTO app_user (id, keycloak_subject, email_snapshot)
SELECT id, subject::text, email FROM demo_learner_seed
ON CONFLICT DO NOTHING;

INSERT INTO learner_profile (
    learner_id, display_name, goal, current_level, daily_minutes, focus_area, first_seen_at, onboarding_completed_at
)
SELECT u.id, demo.name, demo.goal, demo.level, demo.minutes, demo.focus, now() - demo.age,
       CASE WHEN demo.goal IS NOT NULL THEN now() - demo.age + interval '5 minutes' END
FROM demo_learner_seed demo JOIN app_user u ON u.keycloak_subject = demo.subject::text
ON CONFLICT (learner_id) DO UPDATE SET first_seen_at = excluded.first_seen_at
WHERE learner_profile.first_seen_at IS NULL;

-- A user may have signed in before seeding. Keep their profile and hide the old demo-only twin.
UPDATE learner_profile p SET first_seen_at = NULL
FROM demo_learner_seed demo JOIN app_user old_user ON old_user.id = demo.id
JOIN app_user real_user ON real_user.keycloak_subject = demo.subject::text
WHERE p.learner_id = old_user.id AND (old_user.email_snapshot = demo.email OR old_user.keycloak_subject = demo.fallback_subject)
  AND old_user.id <> real_user.id;

COMMIT;
