package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.jdbc.Sql;
import tech.studease.studease.common.event.TestSessionExpirySweeper;
import tech.studease.studease.domain.answers.Choice;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.sessions.ResponseEntry;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.sessions.TestSessionRepository;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.users.Authority;
import tech.studease.studease.domain.users.Authority.AuthorityName;
import tech.studease.studease.domain.users.AuthorityRepository;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.UserRepository;
import tech.studease.studease.support.PostgresIntegrationTest;

/** The DB sweep that replaced the static timer map + 1s tick. */
@Sql(scripts = "/sql/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TestSessionExpiryIntegrationTest extends PostgresIntegrationTest {

  @MockBean private SimpMessagingTemplate messagingTemplate;

  @Autowired private UserRepository userRepository;
  @Autowired private AuthorityRepository authorityRepository;
  @Autowired private CollectionRepository collectionRepository;
  @Autowired private TestRepository testRepository;
  @Autowired private TestSessionRepository testSessionRepository;
  @Autowired private TestSessionExpirySweeper sweeper;

  private Long expiredSessionId;
  private Long runningSessionId;

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
                .email("a@studease.test")
                .firstName("A")
                .lastName("A")
                .password("x")
                .balance(0)
                .isActive(true)
                .authorities(Set.of(role))
                .build());

    Test test = new Test();
    test.setName("T");
    test.setOpenDate(LocalDateTime.now().minusHours(2));
    test.setDeadline(LocalDateTime.now().plusHours(2));
    test.setMinutesToComplete(30);
    test.setAuthor(author);
    Question q = choiceQuestion(test);
    test.setQuestions(Set.of(q));
    test.setSamples(Set.of());
    test.setSessions(List.of());
    Test saved = testRepository.save(test);

    expiredSessionId = session(saved, q, "past", LocalDateTime.now().minusMinutes(1)).getId();
    runningSessionId = session(saved, q, "future", LocalDateTime.now().plusMinutes(10)).getId();
  }

  @org.junit.jupiter.api.Test
  void sweepForceEndsExpiredSessionsAndLeavesRunningOnesAlone() {
    sweeper.sweep();

    TestSession expired = testSessionRepository.findById(expiredSessionId).orElseThrow();
    assertThat(expired.getFinishedAt()).isNotNull();
    assertThat(expired.getMark()).isNotNull();

    TestSession running = testSessionRepository.findById(runningSessionId).orElseThrow();
    assertThat(running.getFinishedAt()).isNull();
  }

  private TestSession session(Test test, Question question, String name, LocalDateTime endsAt) {
    TestSession session =
        TestSession.builder()
            .studentGroup("G")
            .studentName(name)
            .startedAt(endsAt.minusMinutes(30))
            .endsAt(endsAt)
            .currentQuestionIndex(0)
            .test(test)
            .build();
    session.setResponses(
        List.of(
            ResponseEntry.builder()
                .question(question)
                .answers(List.of())
                .testSession(session)
                .build()));
    return testSessionRepository.save(session);
  }

  private Question choiceQuestion(Test test) {
    Question question =
        Question.builder()
            .content("q")
            .points(2)
            .type(QuestionType.SINGLE_CHOICE)
            .test(test)
            .build();
    question.setAnswers(
        List.of(Choice.builder().content("r").isCorrect(true).question(question).build()));
    return question;
  }
}
