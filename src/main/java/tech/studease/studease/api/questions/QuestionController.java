package tech.studease.studease.api.questions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.studease.studease.api.questions.dto.QuestionListDto;
import tech.studease.studease.application.questions.OpenAIService;
import tech.studease.studease.application.questions.QuestionService;
import tech.studease.studease.domain.questions.QuestionType;

@RestController
@RequestMapping("/api/v1/admin/questions")
@RequiredArgsConstructor
@Validated
public class QuestionController {

  private final OpenAIService openAIService;
  private final QuestionService questionService;

  @GetMapping("/generate")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<QuestionListDto> generateQuestions(
      @RequestParam @NotBlank @Size(max = 200) String theme,
      @RequestParam @NotBlank String questionType,
      @RequestParam @Min(1) @Max(5) int points,
      @RequestParam(defaultValue = "1") @Min(1) @Max(20) int questionsCount) {
    return ResponseEntity.ok(
        openAIService.generateQuestions(
            theme, QuestionType.valueOf(questionType.toUpperCase()), points, questionsCount));
  }

  @GetMapping("/by-test/{testId}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<QuestionListDto> getQuestionsByTestId(@PathVariable UUID testId) {
    return ResponseEntity.ok(questionService.findByTestId(testId));
  }

  @GetMapping("/by-collection/{collectionId}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<QuestionListDto> getQuestionsByCollectionId(
      @PathVariable Long collectionId) {
    return ResponseEntity.ok(questionService.findByCollectionId(collectionId));
  }

  @PostMapping("/by-collection/{collectionId}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<QuestionListDto> addQuestionsToCollection(
      @PathVariable Long collectionId, @RequestBody @Valid QuestionListDto questionListDto) {
    return ResponseEntity.status(HttpStatus.CREATED.value())
        .body(questionService.createAll(collectionId, questionListDto.getQuestions()));
  }
}
