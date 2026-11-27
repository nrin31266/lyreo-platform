CREATE VIEW notification_learner_directory AS
    SELECT p.learner_id AS id, p.display_name, u.email_snapshot
    FROM learner_profile p JOIN identity_user_directory u ON u.id = p.learner_id
    WHERE p.first_seen_at IS NOT NULL;

COMMENT ON VIEW notification_learner_directory IS 'Learner directory read contract for notification recipients.';

UPDATE learner_notification SET status = 'ARCHIVED', updated_at = now()
WHERE id = '70000000-0000-0000-0000-000000000902'
  AND title = 'Dành 10 phút luyện tiếng Anh hôm nay'
  AND category = 'STUDY_REMINDER' AND target_audience = 'ALL';
