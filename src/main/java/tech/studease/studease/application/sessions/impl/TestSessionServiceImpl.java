package tech.studease.studease.application.sessions.impl;

import static tech.studease.studease.common.util.TestUtils.getMaxScore;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.studease.studease.api.sessions.dto.CurrentQuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.StartTestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.application.questions.mapper.QuestionMapper;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.application.sessions.mapper.TestSessionMapper;
import tech.studease.studease.common.event.TestSessionTimerBroadcaster;
import tech.studease.studease.common.security.AttemptTokens;
import tech.studease.studease.common.util.TestUtils;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.ResponseEntry;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.sessions.TestSessionRepository;
import tech.studease.studease.domain.sessions.exception.InvalidAttemptTokenException;
import tech.studease.studease.domain.sessions.exception.TestSessionAlreadyExistsException;
import tech.studease.studease.domain.sessions.exception.TestSessionNotFoundException;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.tests.exception.TestNotFoundException;
import tech.studease.studease.domain.users.Credentials;

@Service
@RequiredArgsConstructor
@Transactional
public class TestSessionServiceImpl implements TestSessionService {

  private final TestSessionRepository testSessionRepository;
  private final TestRepository testRepository;
  private final QuestionMapper questionMapper;
  private final TestSessionMapper testSessionMapper;
  private final TestSessionTimerBroadcaster timerBroadcaster;

  // --- admin reads ---

  @Override
  @Transactional(readOnly = true)
  public TestSessionListDto findByTestId(UUID testId) {
    return testSessionMapper.toTestSessionListDto(
        testSessionRepository.findTestSessionsByTestId(testId));
  }

  @Override
  @Transactional(readOnly = true)
  public TestSessionListDto findByTestIdAndCredentials(UUID testId, Credentials credentials) {
    TestSession testSession =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId(
                credentials.studentGroup(), credentials.studentName(), testId)
            .orElseThrow(
                () ->
                    new TestSessionNotFoundException(
                        credentials.studentGroup(), credentials.studentName()));
    return testSessionMapper.toTestSessionListDto(testSession);
  }

  // --- student flow ---

  @Override
  public StartTestSessionDto startTestSession(UUID testId, Credentials credentials) {
    String studentGroup = credentials.studentGroup();
    String studentName = credentials.studentName();

    Test test =
        testRepository.getTestById(testId).orElseThrow(() -> new TestNotFoundException(testId));

    if (test.getOpenDate().isAfter(LocalDateTime.now())) {
      throw new IllegalStateException("Test is not open yet");
    }
    if (test.getDeadline().isBefore(LocalDateTime.now())) {
      throw new IllegalStateException("Test is closed");
    }

    LocalDateTime startedAt = LocalDateTime.now();
    String attemptToken = AttemptTokens.newToken();
    TestSession testSession =
        TestSession.builder()
            .studentGroup(studentGroup)
            .studentName(studentName)
            .startedAt(startedAt)
            .endsAt(startedAt.plusMinutes(test.getMinutesToComplete()))
            .currentQuestionIndex(0)
            .attemptTokenHash(AttemptTokens.hash(attemptToken))
            .sessionKey(UUID.randomUUID())
            .test(test)
            .build();

    List<ResponseEntry> responses = new ArrayList<>();
    addTestQuestions(responses, test.getQuestions(), testSession);
    addSampleQuestions(responses, test.getSamples(), testSession);
    Collections.shuffle(responses);
    testSession.setResponses(responses);

    // One attempt per student per test is enforced by a unique index, not by a preceding
    // existsBy(...) check: that check-then-insert had nothing behind it, so two concurrent starts
    // both passed it and created attempts with different sampled questions.
    try {
      testSessionRepository.saveAndFlush(testSession);
    } catch (DataIntegrityViolationException ex) {
      throw new TestSessionAlreadyExistsException(studentGroup, studentName);
    }

    // Outside the transaction: a STOMP send has no business holding a database connection, and
    // broadcasting for a transaction that later rolls back would be a lie.
    UUID sessionKey = testSession.getSessionKey();
    long secondsLeft = Duration.between(startedAt, testSession.getEndsAt()).toSeconds();
    afterCommit(() -> timerBroadcaster.sendTick(sessionKey, secondsLeft));

    return StartTestSessionDto.builder()
        .attemptToken(attemptToken)
        .sessionKey(sessionKey)
        .endsAt(testSession.getEndsAt())
        .currentQuestion(toCurrentQuestion(testSession, nextResponseEntry(testSession)))
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public TestSessionDto findByAttemptToken(UUID testId, String attemptToken) {
    return testSessionMapper.toTestSessionDto(requireAttempt(testId, attemptToken), true, false);
  }

  @Override
  @Transactional(readOnly = true)
  public CurrentQuestionDto getCurrentQuestion(UUID testId, String attemptToken) {
    TestSession testSession = requireAttempt(testId, attemptToken);
    return toCurrentQuestion(testSession, nextResponseEntry(testSession));
  }

  @Override
  public CurrentQuestionDto nextQuestion(
      UUID testId, String attemptToken, ResponseEntryRequestDto responseEntryRequestDto) {
    TestSession testSession = requireAttempt(testId, attemptToken);
    if (testSession.getFinishedAt() != null) {
      throw new IllegalStateException("This attempt is already finished");
    }
    applyAnswer(testSession, responseEntryRequestDto);
    testSession.setCurrentQuestionIndex(answeredCount(testSession));
    return toCurrentQuestion(testSession, nextResponseEntry(testSession));
  }

  @Override
  public TestSessionDto finishTestSession(
      UUID testId, String attemptToken, ResponseEntryRequestDto responseEntryRequestDto) {
    TestSession testSession = requireAttempt(testId, attemptToken);

    // Replayable before: a second finish overwrote finishedAt and recomputed the mark.
    if (testSession.getFinishedAt() != null) {
      return testSessionMapper.toTestSessionDto(testSession, true, false);
    }

    applyAnswer(testSession, responseEntryRequestDto);
    testSession.setFinishedAt(LocalDateTime.now());
    testSession.setCurrentQuestionIndex(answeredCount(testSession));
    testSession.setMark(getMarkForSession(testSession, testSession.getTest()));

    return testSessionMapper.toTestSessionDto(testSession, true, false);
  }

  @Override
  public TestSessionDto forceEndTestSession(Long testSessionId) {
    TestSession testSession =
        testSessionRepository
            .findById(testSessionId)
            .orElseThrow(() -> new TestSessionNotFoundException(testSessionId));

    if (testSession.getFinishedAt() == null) {
      testSession.setFinishedAt(LocalDateTime.now());
      testSession.setMark(getMarkForSession(testSession, testSession.getTest()));
    }
    return testSessionMapper.toTestSessionDto(testSession, true, false);
  }

  // --- internals ---

  /**
   * Resolves the attempt a token names and checks it belongs to the test in the path, so a token
   * for one test cannot be used against another. An unknown token and a mismatched one fail
   * identically: telling them apart would let a caller probe for valid tokens.
   */
  private TestSession requireAttempt(UUID testId, String attemptToken) {
    if (attemptToken == null || attemptToken.isBlank()) {
      throw new InvalidAttemptTokenException();
    }
    TestSession testSession =
        testSessionRepository
            .findByAttemptTokenHash(AttemptTokens.hash(attemptToken))
            .orElseThrow(InvalidAttemptTokenException::new);
    if (!Objects.equals(testSession.getTest().getId(), testId)) {
      throw new InvalidAttemptTokenException();
    }
    return testSession;
  }

  private void applyAnswer(TestSession testSession, ResponseEntryRequestDto request) {
    ResponseEntry responseEntry = requireResponseEntry(testSession, request.getResponseEntryId());
    if (request.getAnswerContent() != null) {
      saveEssayAnswer(responseEntry, request.getAnswerContent());
    } else {
      saveChoiceAnswers(responseEntry, request.getAnswerIds());
    }
  }

  /**
   * The entry the client named, scoped to this attempt. The server used to infer the target as "the
   * first response with no answers yet", so a retried request attached its answer to the next
   * question instead and silently dropped a submission.
   */
  private static ResponseEntry requireResponseEntry(TestSession testSession, Long responseEntryId) {
    return testSession.getResponses().stream()
        .filter(entry -> Objects.equals(entry.getId(), responseEntryId))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "responseEntryId " + responseEntryId + " is not part of this attempt"));
  }

  private void saveChoiceAnswers(ResponseEntry responseEntry, List<Long> answerIds) {
    if (responseEntry.getQuestion().getType() == QuestionType.ESSAY) {
      throw new IllegalArgumentException("Answer must be a text");
    }
    if (answerIds == null || answerIds.isEmpty()) {
      throw new IllegalArgumentException("Answers must not be empty");
    }

    List<Answer> answers =
        responseEntry.getQuestion().getAnswers().stream()
            .filter(answer -> answerIds.contains(answer.getId()))
            .collect(Collectors.toList());
    if (answers.isEmpty()) {
      throw new IllegalArgumentException("Answers must not be empty");
    }
    if (responseEntry.getQuestion().getType() == QuestionType.SINGLE_CHOICE && answers.size() > 1) {
      throw new IllegalArgumentException("Only one answer is allowed for SINGLE_CHOICE questions");
    }

    responseEntry.setAnswers(answers);
  }

  private void saveEssayAnswer(ResponseEntry responseEntry, String answerContent) {
    if (responseEntry.getQuestion().getType() != QuestionType.ESSAY) {
      throw new IllegalArgumentException("Answer must not be a text");
    }
    // Straight onto the student's own response entry. This used to create an Answer row parented
    // to the SHARED question, which leaked every student's essay to everyone else who was later
    // served that question, and let an admin's question edit orphan-remove submitted work.
    responseEntry.setEssayAnswer(answerContent);
  }

  private CurrentQuestionDto toCurrentQuestion(TestSession testSession, ResponseEntry entry) {
    List<ResponseEntry> responses = testSession.getResponses();
    return CurrentQuestionDto.builder()
        .responseEntryId(entry.getId())
        .question(questionMapper.toQuestionDto(entry.getQuestion(), false))
        .questionNumber(responses.indexOf(entry) + 1)
        .totalQuestions(responses.size())
        .build();
  }

  private int getMarkForSession(TestSession testSession, Test test) {
    int rawMark = testSession.getResponses().stream().mapToInt(TestUtils::calculateMark).sum();
    if (test.getMaximumScore() == null) {
      return rawMark;
    }
    int maxScore = getMaxScore(test.getQuestions(), test.getSamples());
    return maxScore == 0 ? 0 : rawMark * test.getMaximumScore() / maxScore;
  }

  private void addTestQuestions(
      List<ResponseEntry> responses, Set<Question> testQuestions, TestSession testSession) {
    for (Question question : testQuestions) {
      responses.add(
          ResponseEntry.builder()
              .question(question)
              .answers(new ArrayList<>())
              .testSession(testSession)
              .build());
    }
  }

  private void addSampleQuestions(
      List<ResponseEntry> responses, Set<Sample> samples, TestSession testSession) {
    Random random = new Random();
    for (Sample sample : samples) {
      List<Question> selectedQuestions =
          sample.getCollection().getQuestions().stream()
              .filter(q -> q.getPoints().equals(sample.getPoints()))
              .collect(Collectors.toList());
      if (selectedQuestions.size() < sample.getQuestionsCount()) {
        throw new IllegalStateException(
            "Sample requires "
                + sample.getQuestionsCount()
                + " questions worth "
                + sample.getPoints()
                + " points but its collection has only "
                + selectedQuestions.size());
      }
      for (int i = 0; i < sample.getQuestionsCount(); i++) {
        Question question = selectedQuestions.remove(random.nextInt(selectedQuestions.size()));
        responses.add(
            ResponseEntry.builder()
                .question(question)
                .answers(new ArrayList<>())
                .testSession(testSession)
                .build());
      }
    }
  }

  private static ResponseEntry nextResponseEntry(TestSession testSession) {
    return testSession.getResponses().stream()
        .filter(entry -> !isAnswered(entry))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No more questions"));
  }

  /** The single source of truth for quiz position: how many responses have been answered so far. */
  private static int answeredCount(TestSession testSession) {
    return (int)
        testSession.getResponses().stream().filter(TestSessionServiceImpl::isAnswered).count();
  }

  /**
   * Whether the student has responded to this entry. Choice and matching answers land in the {@code
   * answers} join table; an essay lands in {@code essayAnswer} and never touches it, so checking
   * only the collection would serve an answered essay question forever.
   */
  private static boolean isAnswered(ResponseEntry responseEntry) {
    return !responseEntry.getAnswers().isEmpty() || responseEntry.getEssayAnswer() != null;
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
