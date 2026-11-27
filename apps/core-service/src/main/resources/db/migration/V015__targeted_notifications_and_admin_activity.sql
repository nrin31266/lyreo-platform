ALTER TABLE learner_notification DROP CONSTRAINT learner_notification_target_audience_check;
ALTER TABLE learner_notification ADD CHECK (target_audience IN ('ALL', 'FREE', 'PLUS', 'SELECTED'));

CREATE TABLE learner_notification_recipient (
    notification_id uuid NOT NULL REFERENCES learner_notification(id) ON DELETE CASCADE,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (notification_id, learner_id)
);
CREATE INDEX learner_notification_recipient_learner_idx
    ON learner_notification_recipient (learner_id, notification_id);

-- Retire the original demo broadcast that incorrectly promised a personal streak reward.
UPDATE learner_notification SET status = 'ARCHIVED', updated_at = now()
WHERE id = '70000000-0000-0000-0000-000000000902'
  AND title = 'Nhắc giữ chuỗi học 10 phút tối nay' AND target_audience = 'ALL';

CREATE TABLE learner_notification_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL,
    actor text NOT NULL,
    action text NOT NULL,
    title text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- Admin-only read contract. Source modules continue to own their writes and full histories.
CREATE VIEW admin_activity_directory AS
    SELECT 'plus-start:' || id AS id, 'MEMBERSHIP' AS category, 'PLUS_STARTED' AS action,
           learner_id, started_by AS actor, NULL::text AS detail, NULL::integer AS amount, starts_at AS occurred_at
    FROM learner_plus_period WHERE starts_at <= now()
    UNION ALL
    SELECT 'plus-end:' || id, 'MEMBERSHIP', 'PLUS_ENDED', learner_id, ended_by, NULL, NULL, ends_at
    FROM learner_plus_period WHERE ends_at <= now()
    UNION ALL
    SELECT 'account:' || id, 'ACCOUNT', action, learner_id, actor, reason, NULL, occurred_at
    FROM learner_account_audit WHERE action IN ('LOCKED', 'UNLOCKED', 'PASSWORD_SET', 'TEMPORARY_PASSWORD_SET')
    UNION ALL
    SELECT 'notification:' || id, 'NOTIFICATION', action, NULL, actor, title, NULL, occurred_at
    FROM learner_notification_audit
    UNION ALL
    SELECT 'diamond:' || id, 'REWARD', 'DIAMOND_ADJUSTED', learner_id, NULL, NULL, amount, created_at
    FROM diamond_transaction WHERE transaction_type = 'ADMIN_ADJUSTMENT'
    UNION ALL
    SELECT 'quota:' || id, 'OTHER', 'QUOTA_UPDATED', NULL, NULL, feature_key, NULL, occurred_at
    FROM entitlement_audit_log WHERE decision = 'QUOTA_UPDATED';

COMMENT ON VIEW admin_activity_directory IS
    'Shared Admin read contract for membership, account, notification, reward and configuration events.';
