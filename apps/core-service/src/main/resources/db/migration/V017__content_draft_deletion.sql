ALTER TABLE content_audit DROP CONSTRAINT content_audit_action_check;
ALTER TABLE content_audit ADD CONSTRAINT content_audit_action_check
    CHECK(action IN ('SAVE','PUBLISH','HIDDEN','ARCHIVED','PUBLISHED','KEEP_EDIT','TAKE_SOURCE','EDITED','DELETE'));
