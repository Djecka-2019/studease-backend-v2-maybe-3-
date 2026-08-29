--liquibase formatted sql

--  Moves a student's essay text off the SHARED question and onto their own attempt.
--
--  Before this change, TestSessionServiceImpl saved each essay as an `answer` row whose
--  question_id pointed at the shared Question. Question.answers is a @OneToMany on that column, so
--  every essay ever written for a question accumulated there -- and QuestionMapper serialised the
--  whole collection back to every student who was later served that question. That is the
--  cross-session leak: student B literally received student A's essay text as an "answer option".
--
--  Two further consequences disappear with it: an admin editing the question orphan-removed every
--  student's submitted essay, and the `answer` table grew by (students x essay questions) forever.
--
--  Backfill and cleanup run as ONE changeset so they share a transaction: if the backfill is
--  wrong, the deletes roll back with it and no student's work is lost.

--changeset studease:003-essay-answer-column
--comment Per-attempt essay text. 10k chars is ~1500 words; the old answer.content was varchar(255).
ALTER TABLE public.response_entry ADD COLUMN IF NOT EXISTS essay_answer character varying(10000);
--rollback ALTER TABLE public.response_entry DROP COLUMN IF EXISTS essay_answer;

--changeset studease:003-essay-backfill
--comment Copy each student's essay onto their own response_entry, then drop the shared rows.
UPDATE public.response_entry re
SET essay_answer = a.content
FROM public.response_entry_answers rea
     JOIN public.answer a ON a.id = rea.answers_id
WHERE rea.response_entry_id = re.id
  AND a.dtype = 'essay';

DELETE FROM public.response_entry_answers rea
USING public.answer a
WHERE a.id = rea.answers_id
  AND a.dtype = 'essay';

DELETE FROM public.answer WHERE dtype = 'essay';
--rollback SELECT 'irreversible: the shared essay rows this changeset removed were the leak' AS note;
