--liquibase formatted sql

--  Per-attempt identity. Until now a student was whoever the request body claimed to be:
--  {studentGroup, studentName} on every call, on endpoints that are permitAll. Anyone who knew a
--  testId could read or mutate any classmate's attempt, and the WebSocket timer topic keyed on a
--  sequential id was enumerable. This adds
--
--    attempt_token_hash - SHA-256 of a 256-bit random token handed to the client once, at start.
--                         Only the hash is stored, so a database leak does not yield live tokens.
--    session_key        - a random UUID used as the WebSocket destination segment, so destinations
--                         stop being guessable by counting.
--    version            - optimistic locking, so two concurrent submissions for one attempt
--                         conflict loudly (409) instead of silently losing one.
--
--  Requires PostgreSQL 13+ for gen_random_uuid().

--changeset studease:004-attempt-token-columns
--comment Token hash, WebSocket session key and optimistic-lock version on the attempt.
ALTER TABLE public.test_session ADD COLUMN IF NOT EXISTS attempt_token_hash character varying(64);
ALTER TABLE public.test_session ADD COLUMN IF NOT EXISTS session_key uuid;
ALTER TABLE public.test_session ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0 NOT NULL;
--rollback ALTER TABLE public.test_session DROP COLUMN IF EXISTS attempt_token_hash;
--rollback ALTER TABLE public.test_session DROP COLUMN IF EXISTS session_key;
--rollback ALTER TABLE public.test_session DROP COLUMN IF EXISTS version;

--changeset studease:004-session-key-backfill
--comment Existing attempts predate the key, so give each one before the column goes NOT NULL.
UPDATE public.test_session SET session_key = gen_random_uuid() WHERE session_key IS NULL;
ALTER TABLE public.test_session ALTER COLUMN session_key SET NOT NULL;
--rollback ALTER TABLE public.test_session ALTER COLUMN session_key DROP NOT NULL;

--changeset studease:004-attempt-token-indexes
--comment Token lookup is the hot student query now; both columns must be unique and indexed.
CREATE UNIQUE INDEX IF NOT EXISTS idx_test_session_attempt_token
    ON public.test_session (attempt_token_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_test_session_key ON public.test_session (session_key);
--rollback DROP INDEX IF EXISTS public.idx_test_session_attempt_token;
--rollback DROP INDEX IF EXISTS public.idx_test_session_key;

--  Deferred here from 002 on purpose. The domain wants one attempt per student per test, but
--  startTestSession only ever did a check-then-insert with nothing behind it, so a double-clicked
--  start could create two attempts with different sampled questions. Making the index UNIQUE is
--  what actually closes that race -- the service now relies on the constraint instead of the check.
--
--  If duplicates already exist the precondition HALTs the deploy rather than picking a winner and
--  deleting somebody's exam. Find them with:
--
--      SELECT test_id, student_group, student_name, COUNT(*), array_agg(id)
--      FROM test_session
--      GROUP BY test_id, student_group, student_name HAVING COUNT(*) > 1;
--
--  then decide per row which attempt is real. Run that query BEFORE deploying.

--changeset studease:004-session-identity-unique
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM (SELECT 1 FROM public.test_session GROUP BY test_id, student_group, student_name HAVING COUNT(*) > 1) duplicates
--comment One attempt per student per test, enforced by the database rather than by a racy check.
DROP INDEX IF EXISTS public.idx_test_session_test_student;
CREATE UNIQUE INDEX IF NOT EXISTS idx_test_session_test_student
    ON public.test_session (test_id, student_group, student_name);
--rollback DROP INDEX IF EXISTS public.idx_test_session_test_student;
--rollback CREATE INDEX IF NOT EXISTS idx_test_session_test_student ON public.test_session (test_id, student_group, student_name);
