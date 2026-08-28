package tech.studease.studease.application.sessions.impl;

import static tech.studease.studease.common.util.TestUtils.getMaxScore;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.api.questions.dto.QuestionDto;
import tech.studease.studease.api.sessions.dto.ResponseEntryRequestDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.application.questions.mapper.QuestionMapper;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.application.sessions.mapper.TestSessionMapper;
import tech.studease.studease.common.event.TestSessionTimerBroadcaster;
import tech.studease.studease.common.util.TestUtils;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.answers.AnswerRepository;
import tech.studease.studease.domain.answers.Essay;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.ResponseEntry;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.sessions.TestSessionRepository;
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
  private final AnswerRepository answerRepository;
  private final QuestionMapper questionMapper;
  private final TestSessionMapper testSessionMapper;
  private final TestSessionTimerBroadcaster timerBroadcaster;

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

  @Override
  @Transactional(readOnly = true)
  public TestSessionDto findByTestIdAndCredentialsForStudent(UUID testId, Credentials credentials) {
    TestSession testSession =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId(
                credentials.studentGroup(), credentials.studentName(), testId)
            .orElseThrow(
                () ->
                    new TestSessionNotFoundException(
                        credentials.studentGroup(), credentials.studentName()));
    return testSessionMapper.toTestSessionDto(testSession, false, false);
  }

  @Override
  public QuestionDto startTestSession(UUID testId, Credentials credentials) {
    String studentGroup = credentials.studentGroup();
    String studentName = credentials.studentName();
    if (testSessionRepository.existsByStudentGroupAndStudentNameAndTestId(
        studentGroup, studentName, testId)) {
      throw new TestSessionAlreadyExistsException(studentGroup, studentName);
    }

    Test test =
        testRepository.getTestById(testId).orElseThrow(() -> new TestNotFoundException(testId));

    if (test.getOpenDate().isAfter(LocalDateTime.now())) {
      throw new IllegalStateException("Test is not open yet");
    }
    if (test.getDeadline().isBefore(LocalDateTime.now())) {
      throw new IllegalStateException("Test is closed");
    }

    LocalDateTime startedAt = LocalDateTime.now();
    TestSession testSession =
        TestSession.builder()
            .studentGroup(studentGroup)
            .studentName(studentName)
            .startedAt(startedAt)
            .endsAt(startedAt.plusMinutes(test.getMinutesToComplete()))
            .currentQuestionIndex(0)
            .test(test)
            .build();

    List<ResponseEntry> responses = new ArrayList<>();
    addTestQuestions(responses, test.getQuestions(), testSession);
    addSampleQuestions(responses, test.getSamples(), testSession);
    Collections.shuffle(responses);
    testSession.setResponses(responses);

    testSessionRepository.save(testSession);
    timerBroadcaster.sendTick(
        testSession.getId(), Duration.between(startedAt, testSession.getEndsAt()).toSeconds());

    return questionMapper.toQuestionDto(nextResponseEntry(testSession).getQuestion(), false);
  }

  @Override
  public QuestionDto getCurrentQuestion(UUID testId, Credentials credentials) {
    TestSession testSession =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId(
                credentials.studentGroup(), credentials.studentName(), testId)
            .orElseThrow(
                () ->
                    new TestSessionNotFoundException(
                        credentials.studentGroup(), credentials.studentName()));
    return questionMapper.toQuestionDto(nextResponseEntry(testSession).getQuestion(), false);
  }

  @Override
  public QuestionDto nextQuestion(UUID testId, ResponseEntryRequestDto responseEntryRequestDto) {
    TestSession testSession = updateWithAnswer(testId, responseEntryRequestDto);

    testSession.setCurrentQuestionIndex(answeredCount(testSession));
    testSessionRepository.save(testSession);

    return questionMapper.toQuestionDto(nextResponseEntry(testSession).getQuestion(), false);
  }

  /** The single source of truth for quiz position: how many responses have been answered so far. */
  private static int answeredCount(TestSession testSession) {
    return (int) testSession.getResponses().stream().filter(r -> !r.getAnswers().isEmpty()).count();
  }

  @Override
  public TestSessionDto finishTestSession(
      UUID testId, ResponseEntryRequestDto responseEntryRequestDto) {
    TestSession testSession = updateWithAnswer(testId, responseEntryRequestDto);

    testSession.setFinishedAt(LocalDateTime.now());

    Test test =
        testRepository.findById(testId).orElseThrow(() -> new TestNotFoundException(testId));
    testSession.setMark(getMarkForSession(testSession, test));

    return testSessionMapper.toTestSessionDto(testSessionRepository.save(testSession), true, false);
  }

  @Override
  public TestSessionDto forceEndTestSession(Long testSessionId) {
    TestSession testSession =
        testSessionRepository
            .findById(testSessionId)
            .orElseThrow(() -> new TestSessionNotFoundException(testSessionId));

    testSession.setFinishedAt(LocalDateTime.now());
    testSession.setMark(getMarkForSession(testSession, testSession.getTest()));

    return testSessionMapper.toTestSessionDto(testSessionRepository.save(testSession), true, false);
  }

  private int getMarkForSession(TestSession testSession, Test test) {
    int mark;
    if (test.getMaximumScore() == null) {
      mark = testSession.getResponses().stream().mapToInt(TestUtils::calculateMark).sum();
    } else {
      mark =
          testSession.getResponses().stream().mapToInt(TestUtils::calculateMark).sum()
              * test.getMaximumScore()
              / getMaxScore(test.getQuestions(), test.getSamples());
    }
    return mark;
  }

  private void saveAnswers(TestSession testSession, List<Long> answerIds) {
    ResponseEntry responseEntry = nextResponseEntry(testSession);
    if (responseEntry.getQuestion().getType() == QuestionType.ESSAY) {
      throw new IllegalArgumentException("Answer must be a text");
    }

    List<Answer> answers = new ArrayList<>(responseEntry.getQuestion().getAnswers());
    answers =
        answers.stream().filter(a -> answerIds.contains(a.getId())).collect(Collectors.toList());
    if (answers.isEmpty()) {
      throw new IllegalArgumentException("Answers must not be empty");
    }

    if (responseEntry.getQuestion().getType() == QuestionType.SINGLE_CHOICE && answers.size() > 1) {
      throw new IllegalArgumentException("Only one answer is allowed for SINGLE_CHOICE questions");
    }

    responseEntry.setAnswers(answers);
  }

  private TestSession updateWithAnswer(
      UUID testId, ResponseEntryRequestDto responseEntryRequestDto) {
    Credentials credentials = responseEntryRequestDto.getCredentials();
    TestSession testSession =
        testSessionRepository
            .findTestSessionByStudentGroupAndStudentNameAndTestId(
                credentials.studentGroup(), credentials.studentName(), testId)
            .orElseThrow(
                () ->
                    new TestSessionNotFoundException(
                        credentials.studentGroup(), credentials.studentName()));

    if (responseEntryRequestDto.getAnswerContent() != null) {
      saveAnswers(testSession, responseEntryRequestDto.getAnswerContent());
    } else {
      saveAnswers(testSession, responseEntryRequestDto.getAnswerIds());
    }
    return testSession;
  }

  private void saveAnswers(TestSession testSession, String answerContent) {
    ResponseEntry responseEntry = nextResponseEntry(testSession);
    if (responseEntry.getQuestion().getType() != QuestionType.ESSAY) {
      throw new IllegalArgumentException("Answer must not be a text");
    }

    List<Answer> existing = responseEntry.getAnswers();
    if (existing != null && !existing.isEmpty() && existing.get(0) instanceof Essay essay) {
      essay.setContent(answerContent);
      return;
    }

    Essay essayAnswer =
        Essay.builder()
            .isCorrect(true)
            .content(answerContent)
            .question(responseEntry.getQuestion())
            .build();
    answerRepository.save(essayAnswer);
    responseEntry.setAnswers(new ArrayList<>(List.of(essayAnswer)));
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

  private ResponseEntry nextResponseEntry(TestSession testSession) {
    return testSession.getResponses().stream()
        .filter(r -> r.getAnswers().isEmpty())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No more questions"));
  }
}
