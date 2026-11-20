-- Keep imported content and the installed source pointers used by existing publications.
DROP TABLE dataset_activation_history;
DROP TABLE dataset_content_conflict;

ALTER TABLE grammar_subtopic_version DROP COLUMN theory;
