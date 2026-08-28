package tech.studease.studease.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import tech.studease.studease.domain.questions.QuestionType;

@RequiredArgsConstructor
public class QuestionTypeValidator implements ConstraintValidator<ValidQuestionType, String> {

  @Override
  public boolean isValid(String questionType, ConstraintValidatorContext context) {
    if (questionType == null) {
      return true;
    }
    return Stream.of(QuestionType.values())
        .map(QuestionType::getDisplayName)
        .anyMatch(displayName -> displayName.equals(questionType.toLowerCase()));
  }
}
