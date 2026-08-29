package tech.studease.studease.api.sessions.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything a client needs to run an attempt, returned once by {@code POST
 * /api/v1/tests/{testId}/start}.
 *
 * <p>The {@code attemptToken} is shown exactly once and cannot be retrieved again: it is stored
 * only as a hash. A client that loses it cannot resume the attempt, so persist it (session storage)
 * before navigating.
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class StartTestSessionDto {

  /** Send as the {@code X-Attempt-Token} header on every subsequent call for this attempt. */
  private String attemptToken;

  /** WebSocket destination segment: subscribe to {@code /topic/testSession/{sessionKey}}. */
  private UUID sessionKey;

  /** Wall-clock deadline; clients count down locally from this rather than per-tick pushes. */
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd.MM.yyyy HH:mm:ss")
  private LocalDateTime endsAt;

  private CurrentQuestionDto currentQuestion;
}
