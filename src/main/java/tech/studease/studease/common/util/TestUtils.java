package tech.studease.studease.common.util;

import java.util.List;
import java.util.Set;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.ResponseEntry;
import tech.studease.studease.domain.sessions.TestSession;

public class TestUtils {

  public static int getMaxScore(Set<Question> questions, Set<Sample> samples) {
    int questionsScore =
        questions == null ? 0 : questions.stream().mapToInt(Question::getPoints).sum();
    int samplesScore =
        samples == null
            ? 0
            : samples.stream().mapToInt((s) -> s.getPoints() * s.getQuestionsCount()).sum();
    return questionsScore + samplesScore;
  }

  public static int getQuestionsCount(Set<Question> questions, Set<Sample> samples) {
    int questionsCount = questions == null ? 0 : questions.size();
    int samplesCount =
        samples == null ? 0 : samples.stream().mapToInt(Sample::getQuestionsCount).sum();
    return questionsCount + samplesCount;
  }

  public static int getStartedSessions(List<TestSession> sessions) {
    return (int) sessions.stream().filter(session -> session.getFinishedAt() == null).count();
  }

  public static int getFinishedSessions(List<TestSession> sessions) {
    return (int) sessions.stream().filter(session -> session.getFinishedAt() != null).count();
  }

  public static int calculateMark(ResponseEntry responseEntry) {
    if (responseEntry.getQuestion().getType() == QuestionType.ESSAY) {
      return responseEntry.getQuestion().getPoints();
    }
    List<Long> correctAnswerIds =
        responseEntry.getQuestion().getAnswers().stream()
            .filter(Answer::getIsCorrect)
            .map(Answer::getId)
            .toList();
    if (correctAnswerIds.isEmpty()) {
      return 0;
    }
    List<Long> studentAnswerIds = responseEntry.getAnswers().stream().map(Answer::getId).toList();
    long correctCount = studentAnswerIds.stream().filter(correctAnswerIds::contains).count();
    if (responseEntry.getQuestion().getType() == QuestionType.MULTIPLE_CHOICES) {
      correctCount -= studentAnswerIds.size() - correctCount;
      correctCount = Math.max(correctCount, 0);
    }
    double percentageCorrect = (double) correctCount / correctAnswerIds.size();
    return (int) (percentageCorrect * responseEntry.getQuestion().getPoints());
  }
}
