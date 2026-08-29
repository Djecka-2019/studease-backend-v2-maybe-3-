package tech.studease.studease.api.sessions;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.studease.studease.api.sessions.dto.CurrentQuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.StartTestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.tests.dto.TestInfo;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.application.tests.TestService;
import tech.studease.studease.domain.users.Credentials;

/**
 * The student test-taking flow. Unauthenticated in the Spring Security sense, but every call after
 * {@link #startTest} must present the opaque attempt token that it issues.
 *
 * <p>Identity used to come from {@code {studentGroup, studentName}} in each request body, so anyone
 * who knew a testId and a classmate's name could read or mutate their attempt.
 */
@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
public class TestPassingController {

  /** Bearer credential for one attempt. Issued once by {@code /start}; never logged. */
  public static final String ATTEMPT_TOKEN_HEADER = "X-Attempt-Token";

  private final TestService testService;
  private final TestSessionService testSessionService;

  @GetMapping("{testId}")
  public ResponseEntity<TestInfo> getTestById(@PathVariable UUID testId) {
    return ResponseEntity.ok(testService.findByIdForStudent(testId));
  }

  /** Issues the attempt token. The only student endpoint that accepts body credentials. */
  @PostMapping("{testId}/start")
  public ResponseEntity<StartTestSessionDto> startTest(
      @PathVariable UUID testId, @RequestBody Credentials credentials) {
    return ResponseEntity.ok(testSessionService.startTestSession(testId, credentials));
  }

  @GetMapping("{testId}/current-question")
  public ResponseEntity<CurrentQuestionDto> getCurrentQuestion(
      @PathVariable UUID testId, @RequestHeader(ATTEMPT_TOKEN_HEADER) String attemptToken) {
    return ResponseEntity.ok(testSessionService.getCurrentQuestion(testId, attemptToken));
  }

  @GetMapping("{testId}/current-session")
  public ResponseEntity<TestSessionDto> getTestSession(
      @PathVariable UUID testId, @RequestHeader(ATTEMPT_TOKEN_HEADER) String attemptToken) {
    return ResponseEntity.ok(testSessionService.findByAttemptToken(testId, attemptToken));
  }

  @PostMapping("{testId}/next-question")
  public ResponseEntity<CurrentQuestionDto> getNextQuestion(
      @PathVariable UUID testId,
      @RequestHeader(ATTEMPT_TOKEN_HEADER) String attemptToken,
      @Valid @RequestBody ResponseEntryRequestDto requestDto) {
    return ResponseEntity.ok(testSessionService.nextQuestion(testId, attemptToken, requestDto));
  }

  @PostMapping("{testId}/finish")
  public ResponseEntity<TestSessionDto> finishTest(
      @PathVariable UUID testId,
      @RequestHeader(ATTEMPT_TOKEN_HEADER) String attemptToken,
      @Valid @RequestBody ResponseEntryRequestDto requestDto) {
    return ResponseEntity.ok(
        testSessionService.finishTestSession(testId, attemptToken, requestDto));
  }
}
