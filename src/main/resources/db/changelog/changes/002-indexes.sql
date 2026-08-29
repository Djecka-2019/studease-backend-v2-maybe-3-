--liquibase formatted sql

--  Secondary indexes. The schema had NONE before this changeset: `ddl-auto=update` creates
--  foreign-key CONSTRAINTS, and PostgreSQL does not index FK columns automatically, so every
--  hot query below was a sequential scan that got slower with every exam ever taken.
--
--  Every index here is NON-UNIQUE on purpose. The uniqueness that the domain actually wants --
--  test_session(test_id, student_group, student_name) and users(email) -- is deferred to the
--  attempt-token changeset, which reworks student identity and can deal with pre-existing
--  duplicate rows deliberately. Adding a UNIQUE index here would either fail the deploy or
--  silently require deleting a student's exam attempt.
--
--  Plain CREATE INDEX (not CONCURRENTLY): these tables hold thousands to low-hundreds-of-
--  thousands of rows, so each build is sub-second, and staying inside the transaction keeps
--  each change atomic and rollback-able. Revisit CONCURRENTLY if response_entry grows past ~10M.

--changeset studease:002-indexes-test-session
--comment The student hot path -- every current-question/next-question/current-session/finish hits this.
CREATE INDEX IF NOT EXISTS idx_test_session_test_student
    ON public.test_session (test_id, student_group, student_name);
--rollback DROP INDEX IF EXISTS public.idx_test_session_test_student;

--changeset studease:002-indexes-test-session-sweeper
--comment Partial indexes for TestSessionExpirySweeper; only unfinished sessions are ever scanned.
CREATE INDEX IF NOT EXISTS idx_test_session_unfinished
    ON public.test_session (ends_at) WHERE finished_at IS NULL;
--rollback DROP INDEX IF EXISTS public.idx_test_session_unfinished;

--changeset studease:002-indexes-response-entry
--comment Session -> responses -> question/answers traversal on every answer submission.
CREATE INDEX IF NOT EXISTS idx_response_entry_test_session
    ON public.response_entry (test_session_id);
CREATE INDEX IF NOT EXISTS idx_response_entry_question
    ON public.response_entry (question_id);
--rollback DROP INDEX IF EXISTS public.idx_response_entry_test_session;
--rollback DROP INDEX IF EXISTS public.idx_response_entry_question;

--changeset studease:002-indexes-response-entry-answers
--comment The @ManyToMany join table has no primary key, so neither column was indexed.
CREATE INDEX IF NOT EXISTS idx_response_entry_answers_entry
    ON public.response_entry_answers (response_entry_id);
CREATE INDEX IF NOT EXISTS idx_response_entry_answers_answer
    ON public.response_entry_answers (answers_id);
--rollback DROP INDEX IF EXISTS public.idx_response_entry_answers_entry;
--rollback DROP INDEX IF EXISTS public.idx_response_entry_answers_answer;

--changeset studease:002-indexes-question-answer
--comment Question.answers fetch, and question lookup by test / collection.
CREATE INDEX IF NOT EXISTS idx_answer_question ON public.answer (question_id);
CREATE INDEX IF NOT EXISTS idx_question_test ON public.question (test_id);
CREATE INDEX IF NOT EXISTS idx_question_collection ON public.question (collection_id);
--rollback DROP INDEX IF EXISTS public.idx_answer_question;
--rollback DROP INDEX IF EXISTS public.idx_question_test;
--rollback DROP INDEX IF EXISTS public.idx_question_collection;

--changeset studease:002-indexes-sample
--comment Sample lookup by test, and the collection question-bank draw at session start.
CREATE INDEX IF NOT EXISTS idx_sample_test ON public.sample (test_id);
CREATE INDEX IF NOT EXISTS idx_sample_collection ON public.sample (collection_id);
--rollback DROP INDEX IF EXISTS public.idx_sample_test;
--rollback DROP INDEX IF EXISTS public.idx_sample_collection;

--changeset studease:002-indexes-ownership
--comment Author-scoped admin list queries, and the per-request findByEmail in the auth filter.
CREATE INDEX IF NOT EXISTS idx_test_author ON public.test (author_user_reference);
CREATE INDEX IF NOT EXISTS idx_collection_author ON public.collection (author_user_reference);
CREATE INDEX IF NOT EXISTS idx_users_email ON public.users (email);
--rollback DROP INDEX IF EXISTS public.idx_test_author;
--rollback DROP INDEX IF EXISTS public.idx_collection_author;
--rollback DROP INDEX IF EXISTS public.idx_users_email;
