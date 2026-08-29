package tech.studease.studease.application.sessions;

import java.util.UUID;
import tech.studease.studease.api.sessions.dto.CurrentQuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.StartTestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.domain.users.Credentials;

/**
 * Student attempts.
 *
 * <p>Only {@link #startTestSession} takes {@link Credentials}; every later call is authorised by
 * the opaque attempt token it returns. The {@code testId} is still passed alongside the token and
 * verified against the attempt, so a token for one test cannot be used against another.
 */
public interface TestSessionService {

  // --- admin ---

  TestSessionListDto findByTestId(UUID testId);

  TestSessionListDto findByTestIdAndCredentials(UUID testId, Credentials credentials);

  TestSessionDto forceEndTestSession(Long testSessionId);

  // --- student ---

  StartTestSessionDto startTestSession(UUID testId, Credentials credentials);

  TestSessionDto findByAttemptToken(UUID testId, String attemptToken);

  CurrentQuestionDto getCurrentQuestion(UUID testId, String attemptToken);

  CurrentQuestionDto nextQuestion(
      UUID testId, String attemptToken, ResponseEntryRequestDto responseEntryRequestDto);

  TestSessionDto finishTestSession(
      UUID testId, String attemptToken, ResponseEntryRequestDto responseEntryRequestDto);
}
