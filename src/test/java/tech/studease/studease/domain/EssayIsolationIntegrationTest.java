package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.common.event.TestSessionExpirySweeper;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
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

/**
 * Regression test for the reported cross-session contamination: "questions from user A were shown
 * to user B".
 *
 * <p>The cause was not a race. Essay answers were persisted as {@code Answer} rows parented to the
 * <em>shared</em> {@code Question}, and {@code QuestionMapper} serialised the whole {@code
 * question.answers} collection back to whoever was served that question next, so every student
 * received every earlier student's essay text as an "answer option". It reproduced single-threaded,
 * which is exactly what this test does.
 */
@Sql(scripts = "/sql/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EssayIsolationIntegrationTest extends PostgresIntegrationTest {

  private static final String SECRET_A = "Alice private essay about the Peloponnesian War";
  private static final String SECRET_B = "Bob completely different answer";

  @MockBean private TestSessionExpirySweeper sweeper;
  @MockBean private SimpMessagingTemplate messagingTemplate;

  @Autowired private UserRepository userRepository;
  @Autowired private AuthorityRepository authorityRepository;
  @Autowired private TestRepository testRepository;
  @Autowired private TestSessionService testSessionService;
  @Autowired private TestSessionRepository testSessionRepository;
  @Autowired private EntityManager entityManager;

  private UUID testId;

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

    Test test = new Test();
    test.setName("Essay quiz");
    test.setOpenDate(LocalDateTime.now().minusHours(1));
    test.setDeadline(LocalDateTime.now().plusHours(1));
    test.setMinutesToComplete(30);
    test.setAuthor(author);
    Question essay =
        Question.builder()
            .content("Explain the causes of the Peloponnesian War")
            .points(5)
            .type(QuestionType.ESSAY)
            .test(test)
            .build();
    essay.setAnswers(new ArrayList<>());
    test.setQuestions(Set.of(essay));
    test.setSamples(Set.of());
    test.setSessions(List.of());
    testId = testRepository.save(test).getId();
  }

  @org.junit.jupiter.api.Test
  void oneStudentsEssayIsNeverServedToAnother() {
    Credentials studentA = new Credentials("CS-1", "Alice");
    Credentials studentB = new Credentials("CS-1", "Bob");

    // A takes the test and submits a distinctive essay.
    submitEssay(studentA, SECRET_A);

    // B now starts the same test and is served the same shared question.
    StartTestSessionDto startedByB = testSessionService.startTestSession(testId, studentB);
    CurrentQuestionDto servedToB = startedByB.getCurrentQuestion();

    assertThat(servedToB.getQuestion().getAnswers())
        .as("an essay question carries no answer options; this list is where the essay leaked")
        .isEmpty();
    assertThat(servedToB.toString())
        .as("no part of the payload served to B may contain the other essay")
        .doesNotContain(SECRET_A);

    // Re-fetching mid-attempt must stay clean too.
    CurrentQuestionDto refetched =
        testSessionService.getCurrentQuestion(testId, startedByB.getAttemptToken());
    assertThat(refetched.getQuestion().getAnswers()).isEmpty();
    assertThat(refetched.toString()).doesNotContain(SECRET_A);
  }

  @org.junit.jupiter.api.Test
  void eachStudentReadsBackTheirOwnEssayOnly() {
    Credentials studentA = new Credentials("CS-1", "Alice");
    Credentials studentB = new Credentials("CS-1", "Bob");

    submitEssay(studentA, SECRET_A);
    submitEssay(studentB, SECRET_B);

    TestSessionListDto a = testSessionService.findByTestIdAndCredentials(testId, studentA);
    TestSessionListDto b = testSessionService.findByTestIdAndCredentials(testId, studentB);

    assertThat(a.getSessions().get(0).getResponses().get(0).getAnswerContent()).isEqualTo(SECRET_A);
    assertThat(b.getSessions().get(0).getResponses().get(0).getAnswerContent()).isEqualTo(SECRET_B);
    assertThat(a.toString()).doesNotContain(SECRET_B);
    assertThat(b.toString()).doesNotContain(SECRET_A);
  }

  @org.junit.jupiter.api.Test
  void essayAnswersNeverAccumulateOnTheSharedQuestion() {
    Credentials studentA = new Credentials("CS-1", "Alice");
    Credentials studentB = new Credentials("CS-1", "Bob");

    submitEssay(studentA, SECRET_A);
    submitEssay(studentB, SECRET_B);

    Number essayRows =
        (Number)
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM answer WHERE dtype = 'essay'")
                .getSingleResult();
    assertThat(essayRows.longValue())
        .as("student essays must no longer be rows in the shared answer table")
        .isZero();

    Number answersForEssayQuestions =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM answer a JOIN question q ON q.id = a.question_id"
                        + " WHERE q.type = :essayOrdinal")
                .setParameter("essayOrdinal", QuestionType.ESSAY.ordinal())
                .getSingleResult();
    assertThat(answersForEssayQuestions.longValue())
        .as("the shared essay question must gain no children as students answer it")
        .isZero();
  }

  @org.junit.jupiter.api.Test
  void forceEndingASessionWithAnUnansweredEssayDoesNotBlowUp() {
    // TestSessionMapper used to read the essay via getAnswers().getFirst() with no emptiness
    // check. A student who ran out of time without answering an essay therefore had no answer
    // row, so the expiry sweeper's force-end threw NoSuchElementException and the attempt was
    // never closed. This is the path that hits every unfinished session at the end of an exam.
    Credentials student = new Credentials("CS-1", "Carol");
    StartTestSessionDto started = testSessionService.startTestSession(testId, student);
    Long sessionId =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId("CS-1", "Carol", testId)
            .orElseThrow()
            .getId();

    assertThatCode(() -> testSessionService.forceEndTestSession(sessionId))
        .doesNotThrowAnyException();

    TestSessionDto session =
        testSessionService.findByAttemptToken(testId, started.getAttemptToken());
    assertThat(session.getFinishedAt()).isNotNull();
  }

  /** Starts an attempt, answers its single essay question, and finishes it. */
  private void submitEssay(Credentials credentials, String content) {
    StartTestSessionDto started = testSessionService.startTestSession(testId, credentials);
    testSessionService.finishTestSession(
        testId,
        started.getAttemptToken(),
        ResponseEntryRequestDto.builder()
            .responseEntryId(started.getCurrentQuestion().getResponseEntryId())
            .answerContent(content)
            .build());
  }
}
