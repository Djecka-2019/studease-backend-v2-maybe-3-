package tech.studease.studease.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.studease.studease.api.answers.dto.AnswerDto;
import tech.studease.studease.api.questions.dto.QuestionDto;
import tech.studease.studease.api.samples.dto.SampleDto;
import tech.studease.studease.api.tests.dto.TestDto;

class ValidatorsTest {

  private final AnswerValidator answerValidator = new AnswerValidator();
  private final AnswersValidator answersValidator = new AnswersValidator();
  private final QuestionSetValidator questionSetValidator = new QuestionSetValidator();
  private final QuestionTypeValidator questionTypeValidator = new QuestionTypeValidator();

  @Test
  void answerValidator_acceptsAChoiceAnswer() {
    AnswerDto choice = AnswerDto.builder().content("Paris").isCorrect(true).build();
    assertThat(answerValidator.isValid(choice, null)).isTrue();
  }

  @Test
  void answerValidator_acceptsAMatchingPair() {
    AnswerDto pair = AnswerDto.builder().leftOption("2+2").rightOption("4").build();
    assertThat(answerValidator.isValid(pair, null)).isTrue();
  }

  @Test
  void answerValidator_rejectsMixedOrIncompleteShapes() {
    assertThat(answerValidator.isValid(AnswerDto.builder().content("x").build(), null)).isFalse();
    assertThat(
            answerValidator.isValid(
                AnswerDto.builder().content("x").isCorrect(true).leftOption("y").build(), null))
        .isFalse();
    assertThat(answerValidator.isValid(null, null)).isFalse();
  }

  @Test
  void answersValidator_essayNeedsNoAnswers() {
    assertThat(answersValidator.isValid(QuestionDto.builder().type("essay").build(), null))
        .isTrue();
  }

  @Test
  void answersValidator_matchingNeedsAtLeastTwoPairs() {
    QuestionDto ok =
        QuestionDto.builder().type("matching").answers(List.of(pair(), pair())).build();
    QuestionDto tooFew = QuestionDto.builder().type("matching").answers(List.of(pair())).build();
    assertThat(answersValidator.isValid(ok, null)).isTrue();
    assertThat(answersValidator.isValid(tooFew, null)).isFalse();
  }

  @Test
  void answersValidator_choiceNeedsAtLeastOneCorrectOption() {
    QuestionDto withCorrect =
        QuestionDto.builder()
            .type("single_choice")
            .answers(List.of(choice(true), choice(false)))
            .build();
    QuestionDto noneCorrect =
        QuestionDto.builder()
            .type("single_choice")
            .answers(List.of(choice(false), choice(false)))
            .build();
    assertThat(answersValidator.isValid(withCorrect, null)).isTrue();
    assertThat(answersValidator.isValid(noneCorrect, null)).isFalse();
  }

  @Test
  void answersValidator_toleratesNullIsCorrectWithoutThrowing() {
    QuestionDto dto =
        QuestionDto.builder()
            .type("single_choice")
            .answers(List.of(AnswerDto.builder().content("a").build(), choice(true)))
            .build();
    assertThat(answersValidator.isValid(dto, null)).isTrue();
  }

  @Test
  void questionSetValidator_needsQuestionsOrSamples() {
    assertThat(
            questionSetValidator.isValid(
                TestDto.builder().questions(List.of(QuestionDto.builder().build())).build(), null))
        .isTrue();
    assertThat(
            questionSetValidator.isValid(
                TestDto.builder().samples(List.of(SampleDto.builder().build())).build(), null))
        .isTrue();
    assertThat(questionSetValidator.isValid(TestDto.builder().build(), null)).isFalse();
  }

  @Test
  void questionTypeValidator_acceptsKnownTypesCaseInsensitively() {
    assertThat(questionTypeValidator.isValid("essay", null)).isTrue();
    assertThat(questionTypeValidator.isValid("MULTIPLE_CHOICES", null)).isTrue();
    assertThat(questionTypeValidator.isValid("single_choice", null)).isTrue();
  }

  @Test
  void questionTypeValidator_rejectsUnknownAndToleratesNull() {
    assertThat(questionTypeValidator.isValid("multiple_choice", null)).isFalse();
    assertThat(questionTypeValidator.isValid("", null)).isFalse();
    assertThat(questionTypeValidator.isValid(null, null)).isTrue();
  }

  private static AnswerDto choice(boolean correct) {
    return AnswerDto.builder().content("opt").isCorrect(correct).build();
  }

  private static AnswerDto pair() {
    return AnswerDto.builder().leftOption("l").rightOption("r").build();
  }
}
