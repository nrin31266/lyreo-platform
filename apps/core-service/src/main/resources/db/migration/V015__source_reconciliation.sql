ALTER TABLE content_audit DROP CONSTRAINT content_audit_action_check;
ALTER TABLE content_audit ADD CONSTRAINT content_audit_action_check
    CHECK(action IN ('SAVE','PUBLISH','HIDDEN','ARCHIVED','PUBLISHED','KEEP_EDIT','TAKE_SOURCE','EDITED'));

CREATE TABLE content_source_binding (
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    domain VARCHAR(32) NOT NULL,
    base_release_id UUID NOT NULL REFERENCES dataset_release(id),
    identity JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY(namespace,document_id),
    FOREIGN KEY(namespace,document_id) REFERENCES content_document(namespace,id)
);
CREATE TABLE content_source_alias (
    namespace VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    document_id UUID NOT NULL,
    PRIMARY KEY(namespace,source_id),
    FOREIGN KEY(namespace,document_id) REFERENCES content_source_binding(namespace,document_id)
);
CREATE TABLE dataset_content_conflict (
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    target_id UUID,
    revision BIGINT NOT NULL,
    base_fingerprint VARCHAR(64) NOT NULL,
    target_fingerprint VARCHAR(64),
    base_content JSONB NOT NULL,
    target_content JSONB,
    resolution VARCHAR(16) CHECK(resolution IN ('KEEP_EDIT','TAKE_SOURCE','EDITED')),
    resolved_by TEXT,
    resolved_at TIMESTAMPTZ,
    reason TEXT,
    PRIMARY KEY(release_id,namespace,document_id),
    FOREIGN KEY(namespace,document_id) REFERENCES content_document(namespace,id),
    CHECK((resolution IS NULL AND resolved_at IS NULL) OR (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX dataset_content_conflict_open_idx ON dataset_content_conflict(release_id) WHERE resolution IS NULL;
