package tech.studease.studease.api.sessions;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.studease.studease.api.questions.dto.QuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.tests.dto.TestInfo;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.application.tests.TestService;
import tech.studease.studease.domain.users.Credentials;

@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
public class TestPassingController {

  private final TestService testService;
  private final TestSessionService testSessionService;

  @GetMapping("{testId}")
  public ResponseEntity<TestInfo> getTestById(@PathVariable UUID testId) {
    return ResponseEntity.ok(testService.findByIdForStudent(testId));
  }

  @PostMapping("{testId}/start")
  public ResponseEntity<QuestionDto> startTest(
      @PathVariable UUID testId, @RequestBody Credentials credentials) {
    return ResponseEntity.ok(testSessionService.startTestSession(testId, credentials));
  }

  @PostMapping("{testId}/current-question")
  public ResponseEntity<QuestionDto> getCurrentQuestion(
      @PathVariable UUID testId, @RequestBody Credentials credentials) {
    return ResponseEntity.ok(testSessionService.getCurrentQuestion(testId, credentials));
  }

  @PostMapping("{testId}/current-session")
  public ResponseEntity<TestSessionDto> getTestSession(
      @PathVariable UUID testId, @RequestBody Credentials credentials) {
    return ResponseEntity.ok(
        testSessionService.findByTestIdAndCredentialsForStudent(testId, credentials));
  }

  @PostMapping("{testId}/next-question")
  public ResponseEntity<QuestionDto> getNextQuestion(
      @PathVariable UUID testId, @Valid @RequestBody ResponseEntryRequestDto requestDto) {
    return ResponseEntity.ok(testSessionService.nextQuestion(testId, requestDto));
  }

  @PostMapping("{testId}/finish")
  public ResponseEntity<TestSessionDto> finishTest(
      @PathVariable UUID testId, @Valid @RequestBody ResponseEntryRequestDto requestDto) {
    return ResponseEntity.ok(testSessionService.finishTestSession(testId, requestDto));
  }
}
