package tech.studease.studease.api.sessions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One answer submission. The attempt is identified by the {@code X-Attempt-Token} header, never by
 * the body: this DTO used to carry {@code Credentials}, which let anyone who knew a classmate's
 * name submit answers as them.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class ResponseEntryRequestDto {

  /**
   * Which question of this attempt is being answered, from {@code CurrentQuestionDto}. Required:
   * the server no longer guesses "the first unanswered one", which mis-attributed retries.
   */
  @NotNull(message = "responseEntryId is mandatory")
  private Long responseEntryId;

  /** Chosen option ids, for SINGLE_CHOICE / MULTIPLE_CHOICES / MATCHING. */
  private List<Long> answerIds;

  /**
   * Free-text answer for an ESSAY question. Bounded to the width of response_entry.essay_answer.
   */
  @Size(max = 10_000, message = "Answer must be at most 10000 characters")
  private String answerContent;
}
