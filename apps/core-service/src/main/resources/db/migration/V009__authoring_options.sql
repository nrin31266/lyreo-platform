ALTER TABLE lesson_authoring ADD COLUMN generation_options jsonb NOT NULL DEFAULT
'{"activities":["DICTATION","SHADOWING"],"annotations":["TRANSLATION","LEXICAL","GRAMMAR","ENTITY_HINTS","DICTATION_HINTS","LEARNING_TIPS","THOUGHT_GROUPS"],"locale":"vi-VN","density":"AUTO","vocabularyLimit":12,"grammarLimit":5,"pronunciationStrategy":"ON_DEMAND","accent":"US"}'::jsonb;

ALTER TABLE lesson_authoring_job DROP CONSTRAINT lesson_authoring_job_operation_check;
ALTER TABLE lesson_authoring_job ADD CONSTRAINT lesson_authoring_job_operation_check CHECK (operation IN ('PREPARE','ALIGN','SEMANTIC','IPA'));
