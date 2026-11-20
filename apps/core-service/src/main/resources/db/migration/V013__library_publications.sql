-- Source releases remain immutable; only edited/new documents allocate runtime rows.
CREATE TABLE content_document (
    namespace VARCHAR(40) NOT NULL,
    id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    draft JSONB NOT NULL CHECK (jsonb_typeof(draft) = 'object'),
    publication_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN','ARCHIVED')),
    updated_by TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(namespace,id)
);
CREATE TABLE content_publication (
    id UUID PRIMARY KEY,
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    content JSONB NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    reason TEXT NOT NULL,
    published_by TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(namespace,document_id) REFERENCES content_document(namespace,id),
    UNIQUE(namespace,document_id,revision),
    UNIQUE(id,namespace,document_id)
);
ALTER TABLE content_document ADD CONSTRAINT content_publication_owner_fk
    FOREIGN KEY(publication_id,namespace,id) REFERENCES content_publication(id,namespace,document_id);
CREATE INDEX content_document_list_idx ON content_document(namespace,status,updated_at DESC,id);
CREATE INDEX content_publication_history_idx ON content_publication(namespace,document_id,published_at DESC);
CREATE TRIGGER content_publication_immutable BEFORE UPDATE OR DELETE ON content_publication
    FOR EACH ROW EXECUTE FUNCTION reject_snapshot_mutation();

CREATE TABLE content_asset (
    id UUID PRIMARY KEY,
    sha256 VARCHAR(64) NOT NULL,
    storage_key TEXT NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes > 0),
    filename TEXT NOT NULL,
    synthetic BOOLEAN NOT NULL DEFAULT false,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO content_asset(id,sha256,storage_key,mime_type,size_bytes,filename,created_by)
SELECT a.id,b.sha256,b.storage_object_key,b.mime_type,b.size_bytes,a.package_path,'dataset-import'
FROM release_media_asset a JOIN media_blob b ON b.sha256=a.blob_sha256;

CREATE TABLE assessment_session (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id),
    domain VARCHAR(16) NOT NULL CHECK(domain IN ('grammar','toeic')),
    catalog_id UUID,
    mode VARCHAR(16) NOT NULL,
    snapshot JSONB NOT NULL,
    answers JSONB NOT NULL DEFAULT '{}',
    result JSONB,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    submitted_at TIMESTAMPTZ
);
CREATE INDEX assessment_session_history_idx ON assessment_session(learner_id,domain,created_at DESC,id);

CREATE TABLE content_ai_request (
    id UUID PRIMARY KEY,
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    base_revision BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    input JSONB NOT NULL,
    output JSONB,
    job_id UUID NOT NULL REFERENCES background_job(id),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE dataset_operation (
    id UUID PRIMARY KEY,
    domain VARCHAR(32) NOT NULL,
    package_version VARCHAR(32) NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK(operation IN ('CHECK','APPLY','ACTIVATE','ROLLBACK')),
    job_id UUID NOT NULL REFERENCES background_job(id),
    actor TEXT NOT NULL,
    report JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
