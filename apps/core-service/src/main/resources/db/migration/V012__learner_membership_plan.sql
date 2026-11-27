CREATE TABLE learner_plus_period (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    started_by text,
    ended_by text,
    CHECK (ends_at IS NULL OR ends_at >= starts_at)
);

CREATE INDEX learner_plus_period_history_idx ON learner_plus_period (learner_id, starts_at DESC);
CREATE UNIQUE INDEX learner_plus_period_open_idx ON learner_plus_period (learner_id) WHERE ends_at IS NULL;

-- The old Admin console considered any effective grant to be Plus.
WITH effective_grants AS (
    SELECT user_id, valid_from, LEAST(valid_until, revoked_at) AS ends_at
    FROM user_entitlement_grant
), membership AS (
    SELECT user_id, range_agg(tstzrange(valid_from, ends_at, '[)')) AS periods
    FROM effective_grants
    WHERE ends_at IS NULL OR ends_at > valid_from
    GROUP BY user_id
)
INSERT INTO learner_plus_period (learner_id, starts_at, ends_at)
SELECT user_id, lower(period), upper(period)
FROM membership CROSS JOIN LATERAL unnest(periods) AS merged(period);
