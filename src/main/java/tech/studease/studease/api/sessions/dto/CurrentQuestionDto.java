package tech.studease.studease.api.sessions.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tech.studease.studease.api.questions.dto.QuestionDto;

/**
 * The question a student is currently on, plus the identifier they must send back when answering
 * it.
 *
 * <p>{@code responseEntryId} exists because the server used to infer which question an answer
 * belonged to as "the first response with no answers yet". A retried or double-submitted request
 * therefore attached the answer to the wrong question and silently dropped one. The client now
 * names its target explicitly, which makes submissions idempotent.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class CurrentQuestionDto {

  /** Send this back as {@code responseEntryId} when answering. Unique to this attempt. */
  private Long responseEntryId;

  private QuestionDto question;

  /** 1-based position of this question within the attempt, for progress display. */
  private Integer questionNumber;

  private Integer totalQuestions;
}
