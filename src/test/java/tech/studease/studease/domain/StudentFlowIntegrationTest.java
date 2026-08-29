package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.sessions.TestSessionRepository;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.users.Authority;
import tech.studease.studease.domain.users.Authority.AuthorityName;
import tech.studease.studease.domain.users.AuthorityRepository;
import tech.studease.studease.domain.users.Credentials;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.UserRepository;
import tech.studease.studease.support.PostgresIntegrationTest;

/** The unauthenticated student test-taking flow, end to end against PostgreSQL. */
@Sql(scripts = "/sql/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StudentFlowIntegrationTest extends PostgresIntegrationTest {

  @MockBean private TestSessionExpirySweeper sweeper;
  @MockBean private SimpMessagingTemplate messagingTemplate;

  @Autowired private UserRepository userRepository;
  @Autowired private AuthorityRepository authorityRepository;
  @Autowired private CollectionRepository collectionRepository;
  @Autowired private TestRepository testRepository;
  @Autowired private TestSessionRepository testSessionRepository;
  @Autowired private TestSessionService testSessionService;

  private UUID testId;
  private Long sessionId;

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

    Collection collection = new Collection();
    collection.setName("Pool");
    collection.setAuthor(author);
    Question pooled = choiceQuestion("pool q", 3, collection, null);
    collection.setQuestions(Set.of(pooled));
    collection.setSamples(Set.of());
    collectionRepository.save(collection);

    Test test = new Test();
    test.setName("Quiz");
    test.setOpenDate(LocalDateTime.now().minusHours(1));
    test.setDeadline(LocalDateTime.now().plusHours(1));
    test.setMinutesToComplete(30);
    test.setAuthor(author);
    Question direct = choiceQuestion("direct q", 2, null, test);
    test.setQuestions(Set.of(direct));
    Sample sample = Sample.builder().points(3).questionsCount(1).collection(collection).build();
    sample.setTest(test);
    test.setSamples(Set.of(sample));
    test.setSessions(List.of());
    testId = testRepository.save(test).getId();
  }

  @org.junit.jupiter.api.Test
  void startAnswerAndFinishASession() {
    Credentials credentials = new Credentials("CS-1", "Student One");

    StartTestSessionDto started = testSessionService.startTestSession(testId, credentials);
    assertThat(started.getAttemptToken()).isNotBlank();
    assertThat(started.getSessionKey()).isNotNull();
    assertThat(started.getEndsAt()).isNotNull();

    String token = started.getAttemptToken();
    CurrentQuestionDto first = started.getCurrentQuestion();
    assertThat(first.getQuestion().getContent()).isNotBlank();
    assertThat(first.getResponseEntryId()).isNotNull();
    assertThat(first.getQuestionNumber()).isEqualTo(1);
    assertThat(first.getTotalQuestions()).isEqualTo(2);

    sessionId =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId("CS-1", "Student One", testId)
            .orElseThrow()
            .getId();

    CurrentQuestionDto second = testSessionService.nextQuestion(testId, token, answerFor(first));
    assertThat(second.getQuestion().getContent()).isNotBlank();
    assertThat(second.getQuestionNumber()).isEqualTo(2);

    TestSessionDto finished =
        testSessionService.finishTestSession(testId, token, answerFor(second));
    assertThat(finished.getFinishedAt()).isNotNull();

    TestSession persisted = testSessionRepository.findById(sessionId).orElseThrow();
    assertThat(persisted.getFinishedAt()).isNotNull();
    assertThat(persisted.getMark()).isNotNull();
  }

  @org.junit.jupiter.api.Test
  void forceEndClosesAnOpenSession() {
    Credentials credentials = new Credentials("CS-2", "Student Two");
    testSessionService.startTestSession(testId, credentials);
    TestSession open =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId("CS-2", "Student Two", testId)
            .orElseThrow();

    TestSessionDto ended = testSessionService.forceEndTestSession(open.getId());

    assertThat(ended.getFinishedAt()).isNotNull();

    TestSession persisted = testSessionRepository.findById(open.getId()).orElseThrow();
    assertThat(persisted.getFinishedAt()).isNotNull();
    assertThat(persisted.getMark()).isNotNull();
  }

  private ResponseEntryRequestDto answerFor(CurrentQuestionDto current) {
    // isCorrect is null for students, so this falls through to the first option -- which is all
    // this test needs: it exercises the flow, not the marking (see TestUtilsTest for that).
    List<Long> answerIds =
        current.getQuestion().getAnswers().stream()
            .filter(a -> Boolean.TRUE.equals(a.getIsCorrect()))
            .map(a -> a.getId())
            .toList();
    return ResponseEntryRequestDto.builder()
        .responseEntryId(current.getResponseEntryId())
        .answerIds(
            answerIds.isEmpty()
                ? List.of(current.getQuestion().getAnswers().get(0).getId())
                : answerIds)
        .build();
  }

  private Question choiceQuestion(String content, int points, Collection collection, Test test) {
    Question question =
        Question.builder()
            .content(content)
            .points(points)
            .type(QuestionType.SINGLE_CHOICE)
            .collection(collection)
            .test(test)
            .build();
    question.setAnswers(
        List.of(
            Choice.builder().content("right").isCorrect(true).question(question).build(),
            Choice.builder().content("wrong").isCorrect(false).question(question).build()));
    return question;
  }
}
