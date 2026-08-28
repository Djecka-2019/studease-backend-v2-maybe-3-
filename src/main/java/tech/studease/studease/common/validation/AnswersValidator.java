package tech.studease.studease.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import tech.studease.studease.api.questions.dto.QuestionDto;

public class AnswersValidator implements ConstraintValidator<ValidAnswers, QuestionDto> {

  @Override
  public boolean isValid(QuestionDto questionDto, ConstraintValidatorContext context) {
    if (questionDto == null || "essay".equalsIgnoreCase(questionDto.getType())) {
      return true;
    }
    if (questionDto.getAnswers() != null && questionDto.getAnswers().size() >= 2) {
      if ("matching".equalsIgnoreCase(questionDto.getType())) {
        return true;
      }
      return questionDto.getAnswers().stream()
          .anyMatch(answer -> Boolean.TRUE.equals(answer.getIsCorrect()));
    }
    return false;
  }
}
