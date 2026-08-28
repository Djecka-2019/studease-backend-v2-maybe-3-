package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import tech.studease.studease.api.questions.dto.QuestionListDto;
import tech.studease.studease.api.samples.dto.SampleListDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.api.tests.dto.TestInfo;
import tech.studease.studease.api.tests.dto.TestListInfo;
import tech.studease.studease.application.questions.QuestionService;
import tech.studease.studease.application.samples.SampleService;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.application.tests.TestService;
import tech.studease.studease.common.util.CsvGeneratorUtils;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.answers.Choice;
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.ResponseEntry;
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

/**
 * Exercises every author-facing read path end to end against real PostgreSQL, with the persisting
 * transaction closed before the service call so lazy loading is genuinely tested. These must stay
 * green across the EAGER -> LAZY migration.
 */
@Sql(scripts = "/sql/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReadPathsIntegrationTest extends PostgresIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private AuthorityRepository authorityRepository;
  @Autowired private CollectionRepository collectionRepository;
  @Autowired private TestRepository testRepository;
  @Autowired private TestSessionRepository testSessionRepository;

  @Autowired private TestService testService;
  @Autowired private QuestionService questionService;
  @Autowired private SampleService sampleService;
  @Autowired private TestSessionService testSessionService;

  private UUID testId;
  private Long collectionId;

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
                .firstName("Ann")
                .lastName("Author")
                .password("x")
                .balance(0)
                .isActive(true)
                .authorities(Set.of(role))
                .build());

    Collection collection = new Collection();
    collection.setName("Algebra");
    collection.setAuthor(author);
    Question poolQuestion = choiceQuestion("2 + 2 = ?", 3, collection, null);
    collection.setQuestions(Set.of(poolQuestion));
    collection.setSamples(Set.of());
    collectionId = collectionRepository.save(collection).getId();

    Test test = new Test();
    test.setName("Midterm");
    test.setOpenDate(LocalDateTime.now().minusDays(1));
    test.setDeadline(LocalDateTime.now().plusDays(1));
    test.setMinutesToComplete(30);
    test.setAuthor(author);
    Question directQuestion = choiceQuestion("Capital of France?", 2, null, test);
    test.setQuestions(Set.of(directQuestion));

    Sample sample = Sample.builder().points(3).questionsCount(1).collection(collection).build();
    sample.setTest(test);
    test.setSamples(Set.of(sample));
    test.setSessions(List.of());
    Test saved = testRepository.save(test);
    testId = saved.getId();

    Question savedQuestion = directQuestion; // managed by the cascade above; ids now populated
    Answer correct =
        savedQuestion.getAnswers().stream().filter(Answer::getIsCorrect).findFirst().orElseThrow();

    TestSession session =
        TestSession.builder()
            .studentGroup("CS-1")
            .studentName("Sam Student")
            .startedAt(LocalDateTime.now().minusMinutes(20))
            .finishedAt(LocalDateTime.now().minusMinutes(5))
            .currentQuestionIndex(1)
            .mark(2)
            .test(saved)
            .build();
    ResponseEntry response =
        ResponseEntry.builder()
            .question(savedQuestion)
            .answers(List.of(correct))
            .testSession(session)
            .build();
    session.setResponses(List.of(response));
    testSessionRepository.save(session);

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(author, null, author.getAuthorities()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @org.junit.jupiter.api.Test
  void findAllTestsAggregatesCountsWithoutOpenSession() {
    TestListInfo result = testService.findAll();

    assertThat(result.getTests()).hasSize(1);
    TestInfo info = result.getTests().get(0);
    assertThat(info.getName()).isEqualTo("Midterm");
    assertThat(info.getQuestionsCount()).isEqualTo(2); // 1 direct + 1 sample
    assertThat(info.getMaxScore()).isEqualTo(5); // 2 + (3 * 1)
    assertThat(info.getFinishedSessions()).isEqualTo(1);
    assertThat(info.getStartedSessions()).isZero();
  }

  @org.junit.jupiter.api.Test
  void findTestByIdReturnsInfo() {
    TestInfo info = testService.findById(testId);
    assertThat(info.getName()).isEqualTo("Midterm");
    assertThat(info.getQuestionsCount()).isEqualTo(2);
  }

  @org.junit.jupiter.api.Test
  void findQuestionsByTestIdInitializesAnswers() {
    QuestionListDto result = questionService.findByTestId(testId);
    assertThat(result.getQuestions()).hasSize(1);
    assertThat(result.getQuestions().get(0).getAnswers()).isNotEmpty();
  }

  @org.junit.jupiter.api.Test
  void findQuestionsByCollectionIdInitializesAnswers() {
    QuestionListDto result = questionService.findByCollectionId(collectionId);
    assertThat(result.getQuestions()).hasSize(1);
    assertThat(result.getQuestions().get(0).getAnswers()).isNotEmpty();
  }

  @org.junit.jupiter.api.Test
  void findSamplesByTestIdMapsCollectionId() {
    SampleListDto result = sampleService.findByTestId(testId);
    assertThat(result.getSamples()).hasSize(1);
    assertThat(result.getSamples().get(0).getCollectionId()).isEqualTo(collectionId);
  }

  @org.junit.jupiter.api.Test
  void findSessionsByTestIdBuildsResponses() {
    TestSessionListDto result = testSessionService.findByTestId(testId);
    assertThat(result.getSessions()).hasSize(1);
    assertThat(result.getSessions().get(0).getResponses()).hasSize(1);
    assertThat(result.getSessions().get(0).getMark()).isEqualTo(2);
  }

  @org.junit.jupiter.api.Test
  void findSessionByCredentials() {
    TestSessionListDto result =
        testSessionService.findByTestIdAndCredentials(
            testId, new Credentials("CS-1", "Sam Student"));
    assertThat(result.getSessions()).hasSize(1);
  }

  @org.junit.jupiter.api.Test
  void csvExportIsGenerated() {
    String csv = CsvGeneratorUtils.generateCsv(testSessionService.findByTestId(testId));
    assertThat(csv).startsWith("Credentials,Mark,StartedAt,FinishedAt,Time");
    assertThat(csv).contains("CS-1 Sam Student");
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
