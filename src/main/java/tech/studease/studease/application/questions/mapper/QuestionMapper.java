package tech.studease.studease.application.questions.mapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import tech.studease.studease.api.answers.dto.AnswerDto;
import tech.studease.studease.api.questions.dto.QuestionDto;
import tech.studease.studease.api.questions.dto.QuestionListDto;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.answers.Choice;
import tech.studease.studease.domain.answers.MatchingPair;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionType;

@Mapper(componentModel = "spring")
public interface QuestionMapper {

  // --- DTO to Entity ---

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "type", source = "type", qualifiedByName = "toQuestionType")
  @Mapping(target = "answers", ignore = true)
  @Named("toQuestion")
  Question toQuestion(QuestionDto questionDto);

  @IterableMapping(qualifiedByName = "toQuestion")
  Set<Question> toQuestion(List<QuestionDto> questions);

  @Named("toQuestionType")
  default QuestionType toQuestionType(String type) {
    return QuestionType.valueOf(type.toUpperCase());
  }

  @AfterMapping
  default void setAnswers(@MappingTarget Question question, QuestionDto questionDto) {
    List<Answer> answers = new ArrayList<>();
    if (questionDto.getAnswers() == null) {
      // Valid for ESSAY: AnswersValidator does not require options for it.
      question.setAnswers(answers);
      return;
    }
    for (AnswerDto answerDto : questionDto.getAnswers()) {
      if (question.getType() == QuestionType.MATCHING) {
        questionDto
            .getAnswers()
            .forEach(
                option ->
                    answers.add(
                        MatchingPair.builder()
                            .leftOption(answerDto.getLeftOption())
                            .rightOption(option.getRightOption())
                            .isCorrect(answerDto.getRightOption().equals(option.getRightOption()))
                            .question(question)
                            .build()));
      } else {
        answers.add(
            Choice.builder()
                .content(answerDto.getContent())
                .isCorrect(answerDto.getIsCorrect())
                .question(question)
                .build());
      }
    }
    question.setAnswers(answers);
  }

  // --- Entity to DTO ---

  @Mapping(target = "type", source = "question.type", qualifiedByName = "toQuestionTypeString")
  @Mapping(target = "answers", ignore = true)
  QuestionDto toQuestionDto(Question question, @Context boolean isAdmin);

  List<QuestionDto> toQuestionDto(Set<Question> questions, @Context boolean isAdmin);

  default QuestionListDto toQuestionListDto(Set<Question> questions) {
    return QuestionListDto.builder().questions(toQuestionDto(questions, true)).build();
  }

  default QuestionListDto toQuestionListDto(List<Question> questions) {
    return QuestionListDto.builder()
        .questions(toQuestionDto(new HashSet<>(questions), true))
        .build();
  }

  @Named("toQuestionTypeString")
  default String toQuestionTypeString(QuestionType type) {
    return type.getDisplayName();
  }

  @AfterMapping
  default void setAnswerDtos(
      @MappingTarget QuestionDto questionDto, Question question, @Context boolean isAdmin) {
    // An essay question has no answer options to offer. The only rows that were ever attached to
    // one were students' submitted essays -- serialising them here is what showed student A's
    // answer to student B. Essay text now lives on ResponseEntry and is returned per attempt.
    if (question.getType() == QuestionType.ESSAY) {
      questionDto.setAnswers(List.of());
      return;
    }

    List<AnswerDto> answerDtos =
        question.getAnswers().stream()
            .map(answer -> toAnswerDto(answer, isAdmin))
            .filter(Objects::nonNull)
            .toList();
    questionDto.setAnswers(answerDtos);
  }

  /** Returns {@code null} for anything that is not a presentable option, so it is filtered out. */
  private static AnswerDto toAnswerDto(Answer answer, boolean isAdmin) {
    if (answer instanceof MatchingPair matchingPair) {
      return AnswerDto.builder()
          .id(matchingPair.getId())
          .leftOption(matchingPair.getLeftOption())
          .rightOption(matchingPair.getRightOption())
          .isCorrect(isAdmin ? matchingPair.getIsCorrect() : null)
          .build();
    }
    if (answer instanceof Choice choice) {
      return AnswerDto.builder()
          .id(choice.getId())
          .content(choice.getContent())
          .isCorrect(isAdmin ? choice.getIsCorrect() : null)
          .build();
    }
    // Legacy Essay rows during rollout, or any future subtype: not an option, never emitted.
    return null;
  }
}
