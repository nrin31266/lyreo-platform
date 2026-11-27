-- Reconcile the immutable ledger with the wallet after the existing V020 clamp.
INSERT INTO diamond_transaction (
    id,
    learner_id,
    transaction_type,
    amount,
    idempotency_key,
    reference_type,
    reference_id,
    created_at,
    reason,
    rule_version
)
SELECT
    (
        substr(md5('migration:v021:reconcile:' || reconcile.learner_id::text), 1, 8) || '-' ||
        substr(md5('migration:v021:reconcile:' || reconcile.learner_id::text), 9, 4) || '-' ||
        substr(md5('migration:v021:reconcile:' || reconcile.learner_id::text), 13, 4) || '-' ||
        substr(md5('migration:v021:reconcile:' || reconcile.learner_id::text), 17, 4) || '-' ||
        substr(md5('migration:v021:reconcile:' || reconcile.learner_id::text), 21, 12)
    )::uuid,
    reconcile.learner_id,
    'ADMIN_ADJUSTMENT',
    -reconcile.ledger_balance,
    'migration:v021:reconcile:' || reconcile.learner_id::text,
    'MIGRATION',
    NULL,
    now(),
    'Reconcile negative diamond ledger balance after the wallet constraint',
    'v1'
FROM (
    SELECT learner_id, COALESCE(SUM(amount), 0)::integer AS ledger_balance
      FROM diamond_transaction
     GROUP BY learner_id
    HAVING COALESCE(SUM(amount), 0) < 0
) reconcile
ON CONFLICT (idempotency_key) DO NOTHING;

UPDATE diamond_wallet w
   SET cached_balance = GREATEST(0, COALESCE((
           SELECT SUM(t.amount)::integer
             FROM diamond_transaction t
            WHERE t.learner_id = w.learner_id
       ), 0)),
       updated_at = now()
 WHERE w.cached_balance < 0
    OR w.cached_balance IS DISTINCT FROM COALESCE((
           SELECT SUM(t.amount)::integer
             FROM diamond_transaction t
            WHERE t.learner_id = w.learner_id
       ), 0);


-- Seed readiness is independent of lesson publication and editorial visibility.
CREATE TABLE seed_lesson_audio_unavailable (
    object_key text PRIMARY KEY
);

CREATE OR REPLACE VIEW lesson_availability AS
SELECT l.id AS lesson_id,
       (l.status = 'PUBLISHED' AND m.deleted_at IS NULL AND m.visible
        AND COALESCE(c.visible, true) AND unavailable.object_key IS NULL) AS available,
       COALESCE(c.visible, true) AS channel_visible
FROM lesson l
JOIN lesson_metadata m ON m.lesson_id = l.id
LEFT JOIN lesson_authoring a ON a.lesson_id = l.id
LEFT JOIN lesson_youtube_source s ON s.video_id = a.video_id
LEFT JOIN lesson_youtube_channel c ON c.channel_id = s.channel_id
LEFT JOIN seed_lesson_audio_unavailable unavailable
       ON l.source_type = 'TEXT' AND unavailable.object_key = l.canonical_audio_object_key;
