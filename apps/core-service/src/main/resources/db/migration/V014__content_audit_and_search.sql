CREATE TABLE content_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK(action IN ('SAVE','PUBLISH','HIDDEN','ARCHIVED','PUBLISHED')),
    revision BIGINT NOT NULL,
    actor TEXT NOT NULL,
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX content_audit_document_idx ON content_audit(namespace,document_id,id DESC);
CREATE TRIGGER content_audit_immutable BEFORE UPDATE OR DELETE ON content_audit
    FOR EACH ROW EXECUTE FUNCTION reject_snapshot_mutation();

CREATE TABLE content_search_term (
    namespace VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('HEADWORD','FORM')),
    term TEXT NOT NULL,
    display_form TEXT NOT NULL,
    PRIMARY KEY(namespace,document_id,kind,term,display_form),
    FOREIGN KEY(namespace,document_id) REFERENCES content_document(namespace,id)
);
CREATE INDEX content_search_prefix_idx ON content_search_term(namespace,term text_pattern_ops,document_id);

CREATE FUNCTION protect_assessment_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.submitted_at IS NOT NULL OR
       ROW(NEW.id,NEW.learner_id,NEW.domain,NEW.catalog_id,NEW.mode,NEW.snapshot,NEW.created_at,NEW.expires_at)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.learner_id,OLD.domain,OLD.catalog_id,OLD.mode,OLD.snapshot,OLD.created_at,OLD.expires_at) THEN
        RAISE EXCEPTION 'Immutable assessment snapshot/result';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER assessment_session_snapshot_immutable BEFORE UPDATE ON assessment_session
    FOR EACH ROW EXECUTE FUNCTION protect_assessment_session();
