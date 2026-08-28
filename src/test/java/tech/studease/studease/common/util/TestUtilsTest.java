package tech.studease.studease.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.answers.Choice;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;
import tech.studease.studease.domain.sessions.ResponseEntry;

class TestUtilsTest {

  @Test
  void singleChoiceFullyCorrectScoresFullPoints() {
    Answer right = choice(1L, true);
    Answer wrong = choice(2L, false);
    ResponseEntry entry =
        response(QuestionType.SINGLE_CHOICE, 4, List.of(right, wrong), List.of(right));

    assertThat(TestUtils.calculateMark(entry)).isEqualTo(4);
  }

  @Test
  void singleChoiceWrongScoresZero() {
    Answer right = choice(1L, true);
    Answer wrong = choice(2L, false);
    ResponseEntry entry =
        response(QuestionType.SINGLE_CHOICE, 4, List.of(right, wrong), List.of(wrong));

    assertThat(TestUtils.calculateMark(entry)).isZero();
  }

  @Test
  void multipleChoicePartialCreditWithoutWrongPicks() {
    Answer c1 = choice(1L, true);
    Answer c2 = choice(2L, true);
    Answer c3 = choice(3L, false);
    ResponseEntry entry =
        response(QuestionType.MULTIPLE_CHOICES, 10, List.of(c1, c2, c3), List.of(c1));

    assertThat(TestUtils.calculateMark(entry)).isEqualTo(5); // 1 of 2 correct, no penalty
  }

  @Test
  void multipleChoiceWrongPicksArePenalised() {
    Answer c1 = choice(1L, true);
    Answer c2 = choice(2L, true);
    Answer c3 = choice(3L, false);
    ResponseEntry entry =
        response(QuestionType.MULTIPLE_CHOICES, 10, List.of(c1, c2, c3), List.of(c1, c3));

    assertThat(TestUtils.calculateMark(entry)).isZero(); // (1 correct - 1 wrong) / 2 -> 0
  }

  @Test
  void essayAlwaysScoresQuestionPoints() {
    ResponseEntry entry = response(QuestionType.ESSAY, 7, List.of(), List.of());
    assertThat(TestUtils.calculateMark(entry)).isEqualTo(7);
  }

  @Test
  void questionWithNoCorrectAnswerScoresZeroInsteadOfNaN() {
    Answer a1 = choice(1L, false);
    Answer a2 = choice(2L, false);
    ResponseEntry entry = response(QuestionType.SINGLE_CHOICE, 5, List.of(a1, a2), List.of(a1));

    assertThat(TestUtils.calculateMark(entry)).isZero();
  }

  private static Choice choice(Long id, boolean correct) {
    return Choice.builder().id(id).isCorrect(correct).build();
  }

  private static ResponseEntry response(
      QuestionType type, int points, List<Answer> allAnswers, List<Answer> studentAnswers) {
    Question question = Question.builder().points(points).type(type).answers(allAnswers).build();
    return ResponseEntry.builder().question(question).answers(studentAnswers).build();
  }
}
