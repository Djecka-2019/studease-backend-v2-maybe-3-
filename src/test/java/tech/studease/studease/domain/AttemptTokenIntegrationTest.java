package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.jdbc.Sql;
import tech.studease.studease.api.sessions.dto.CurrentQuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.StartTestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.common.event.TestSessionExpirySweeper;
import tech.studease.studease.domain.answers.Choice;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.sessions.exception.InvalidAttemptTokenException;
import tech.studease.studease.domain.sessions.exception.TestSessionAlreadyExistsException;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.users.Authority;
import tech.studease.studease.domain.users.Authority.AuthorityName;
import tech.studease.studease.domain.users.AuthorityRepository;
import tech.studease.studease.domain.users.Credentials;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.UserRepository;
import tech.studease.studease.support.PostgresIntegrationTest;

/**
 * The attempt token, which replaced body credentials as the student's identity.
 *
 * <p>Previously {@code /api/v1/tests/**} was {@code permitAll} and every call carried {@code
 * {studentGroup, studentName}} in its body, so knowing a testId and a classmate's name was enough
 * to read their questions, submit answers as them, or finish their attempt.
 */
@Sql(scripts = "/sql/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AttemptTokenIntegrationTest extends PostgresIntegrationTest {

  @MockBean private TestSessionExpirySweeper sweeper;
  @MockBean private SimpMessagingTemplate messagingTemplate;

  @Autowired private UserRepository userRepository;
  @Autowired private AuthorityRepository authorityRepository;
  @Autowired private TestRepository testRepository;
  @Autowired private TestSessionService testSessionService;

  private UUID testId;
  private UUID otherTestId;

  @BeforeEach
  void setUp() {
    Authority role =
        authorityRepository
            .findByAuthority(AuthorityName.ROLE_ADMIN)
            .orElseGet(
                () ->
                    authorityRepository.save(
                        Authority.builder().authority(AuthorityName.ROLE_ADMIN).build()));
    User author =
        userRepository.save(
            User.builder()
                .email("author@studease.test")
                .firstName("A")
                .lastName("A")
                .password("x")
                .balance(0)
                .isActive(true)
                .authorities(Set.of(role))
                .build());

    testId = persistTest(author, "Quiz one");
    otherTestId = persistTest(author, "Quiz two");
  }

  private UUID persistTest(User author, String name) {
    Test test = new Test();
    test.setName(name);
    test.setOpenDate(LocalDateTime.now().minusHours(1));
    test.setDeadline(LocalDateTime.now().plusHours(1));
    test.setMinutesToComplete(30);
    test.setAuthor(author);
    test.setQuestions(
        Set.of(choiceQuestion(test, name + " q1"), choiceQuestion(test, name + " q2")));
    test.setSamples(Set.of());
    test.setSessions(List.of());
    return testRepository.save(test).getId();
  }

  private Question choiceQuestion(Test test, String content) {
    Question question =
        Question.builder()
            .content(content)
            .points(2)
            .type(QuestionType.SINGLE_CHOICE)
            .test(test)
            .build();
    question.setAnswers(
        new ArrayList<>(
            List.of(
                Choice.builder().content("right").isCorrect(true).question(question).build(),
                Choice.builder().content("wrong").isCorrect(false).question(question).build())));
    return question;
  }

  @org.junit.jupiter.api.Test
  void startIssuesATokenAndAWebSocketKey() {
    StartTestSessionDto started =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));

    assertThat(started.getAttemptToken()).isNotBlank().hasSizeGreaterThan(32);
    assertThat(started.getSessionKey()).isNotNull();
    assertThat(started.getCurrentQuestion().getResponseEntryId()).isNotNull();
  }

  @org.junit.jupiter.api.Test
  void aMissingOrUnknownTokenIsRejected() {
    assertThatThrownBy(() -> testSessionService.getCurrentQuestion(testId, null))
        .isInstanceOf(InvalidAttemptTokenException.class);
    assertThatThrownBy(() -> testSessionService.getCurrentQuestion(testId, "   "))
        .isInstanceOf(InvalidAttemptTokenException.class);
    assertThatThrownBy(() -> testSessionService.getCurrentQuestion(testId, "not-a-real-token"))
        .isInstanceOf(InvalidAttemptTokenException.class);
  }

  @org.junit.jupiter.api.Test
  void aTokenIsBoundToTheTestItWasIssuedFor() {
    StartTestSessionDto started =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));

    assertThatThrownBy(
            () -> testSessionService.getCurrentQuestion(otherTestId, started.getAttemptToken()))
        .as("a token for one test must not work against another")
        .isInstanceOf(InvalidAttemptTokenException.class);
  }

  @org.junit.jupiter.api.Test
  void knowingAClassmatesNameNoLongerGrantsAccessToTheirAttempt() {
    StartTestSessionDto alice =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));
    StartTestSessionDto bob =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Bob"));

    // Bob holds a valid token, but it names his own attempt, not Alice's.
    TestSessionDto asBob = testSessionService.findByAttemptToken(testId, bob.getAttemptToken());
    assertThat(asBob.getStudentName()).isEqualTo("Bob");

    TestSessionDto asAlice = testSessionService.findByAttemptToken(testId, alice.getAttemptToken());
    assertThat(asAlice.getStudentName()).isEqualTo("Alice");
    assertThat(asAlice.getSessionKey()).isNotEqualTo(asBob.getSessionKey());
  }

  @org.junit.jupiter.api.Test
  void answersCannotBeAimedAtAnotherStudentsResponseEntry() {
    StartTestSessionDto alice =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));
    StartTestSessionDto bob =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Bob"));

    Long aliceEntry = alice.getCurrentQuestion().getResponseEntryId();

    assertThatThrownBy(
            () ->
                testSessionService.nextQuestion(
                    testId,
                    bob.getAttemptToken(),
                    ResponseEntryRequestDto.builder()
                        .responseEntryId(aliceEntry)
                        .answerIds(List.of(1L))
                        .build()))
        .as("response entries are resolved within the caller's own attempt")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @org.junit.jupiter.api.Test
  void aSecondStartForTheSameStudentIsRejectedRatherThanCreatingATwinAttempt() {
    testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));

    assertThatThrownBy(
            () -> testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice")))
        .as("the unique index, not a racy existsBy check, is what enforces this")
        .isInstanceOf(TestSessionAlreadyExistsException.class);
  }

  @org.junit.jupiter.api.Test
  void finishIsIdempotentRatherThanReplayable() {
    StartTestSessionDto started =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));
    CurrentQuestionDto question = started.getCurrentQuestion();
    ResponseEntryRequestDto answer =
        ResponseEntryRequestDto.builder()
            .responseEntryId(question.getResponseEntryId())
            .answerIds(List.of(question.getQuestion().getAnswers().get(0).getId()))
            .build();

    TestSessionDto first =
        testSessionService.finishTestSession(testId, started.getAttemptToken(), answer);
    assertThat(first.getFinishedAt()).isNotNull();

    TestSessionDto replayed =
        testSessionService.finishTestSession(testId, started.getAttemptToken(), answer);

    // Truncated: the first response carries the in-memory LocalDateTime.now() with nanosecond
    // precision, while the replay reads it back from a timestamp(6) column.
    assertThat(replayed.getFinishedAt().truncatedTo(ChronoUnit.SECONDS))
        .as("replaying finish must not move the completion time or re-mark the attempt")
        .isEqualTo(first.getFinishedAt().truncatedTo(ChronoUnit.SECONDS));
  }

  @org.junit.jupiter.api.Test
  void answeringAFinishedAttemptIsRejected() {
    StartTestSessionDto started =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));
    CurrentQuestionDto question = started.getCurrentQuestion();
    ResponseEntryRequestDto answer =
        ResponseEntryRequestDto.builder()
            .responseEntryId(question.getResponseEntryId())
            .answerIds(List.of(question.getQuestion().getAnswers().get(0).getId()))
            .build();
    testSessionService.finishTestSession(testId, started.getAttemptToken(), answer);

    assertThatThrownBy(
            () -> testSessionService.nextQuestion(testId, started.getAttemptToken(), answer))
        .isInstanceOf(IllegalStateException.class);
  }

  @org.junit.jupiter.api.Test
  void reAnsweringAnEntryUpdatesItInsteadOfSlidingOntoTheNextQuestion() {
    // The server used to target "the first response with no answers yet", so a retried submission
    // landed on the FOLLOWING question and silently dropped one answer. The client now names the
    // entry, so a retry is idempotent.
    StartTestSessionDto started =
        testSessionService.startTestSession(testId, new Credentials("CS-1", "Alice"));
    CurrentQuestionDto first = started.getCurrentQuestion();
    String token = started.getAttemptToken();
    Long firstEntry = first.getResponseEntryId();

    CurrentQuestionDto afterFirstAnswer =
        testSessionService.nextQuestion(testId, token, answerFor(first, 0));
    assertThat(afterFirstAnswer.getQuestionNumber()).isEqualTo(2);

    // Retry the SAME entry with a different choice, as a flaky connection would.
    CurrentQuestionDto afterRetry =
        testSessionService.nextQuestion(testId, token, answerFor(first, 1));

    assertThat(afterRetry.getResponseEntryId())
        .as("the retry must not consume the second question")
        .isEqualTo(afterFirstAnswer.getResponseEntryId());

    TestSessionDto session = testSessionService.findByAttemptToken(testId, token);
    assertThat(session.getCurrentQuestionIndex())
        .as("exactly one of the two questions has been answered")
        .isEqualTo(1);
    assertThat(firstEntry).isNotEqualTo(afterRetry.getResponseEntryId());
  }

  private static ResponseEntryRequestDto answerFor(CurrentQuestionDto question, int optionIndex) {
    return ResponseEntryRequestDto.builder()
        .responseEntryId(question.getResponseEntryId())
        .answerIds(List.of(question.getQuestion().getAnswers().get(optionIndex).getId()))
        .build();
  }
}
