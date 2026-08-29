package tech.studease.studease.api.sessions.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tech.studease.studease.domain.users.Credentials;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class ResponseEntryRequestDto {

  private Credentials credentials;

  private List<Long> answerIds;

  /**
   * Free-text answer for an ESSAY question. Bounded to the width of response_entry.essay_answer.
   */
  @Size(max = 10_000, message = "Answer must be at most 10000 characters")
  private String answerContent;
}
