package tech.studease.studease.application.questions.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tech.studease.studease.api.questions.dto.QuestionListDto;
import tech.studease.studease.application.questions.OpenAIService;
import tech.studease.studease.application.questions.exception.QuestionGenerationException;
import tech.studease.studease.domain.questions.QuestionType;

@Service
public class OpenAIServiceImpl implements OpenAIService {

  private static final int MAX_QUESTIONS = 20;
  private static final int MAX_THEME_LENGTH = 200;

  private final ChatClient chatClient;

  @Value("classpath:templates/get-questions-for-test.st")
  private Resource questionsPrompt;

  public OpenAIServiceImpl(ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
  }

  @Override
  public QuestionListDto generateQuestions(
      String theme, QuestionType questionType, int points, int questionsCount) {
    String safeTheme = sanitizeTheme(theme);
    int safeCount = Math.clamp(questionsCount, 1, MAX_QUESTIONS);

    QuestionListDto questions;
    try {
      questions =
          chatClient
              .prompt()
              .user(
                  userSpec ->
                      userSpec
                          .text(questionsPrompt)
                          .param("theme", safeTheme)
                          .param("questionType", questionType.getDisplayName())
                          .param("difficulty", points < 2 ? "easy" : points < 4 ? "medium" : "hard")
                          .param("questionsCount", String.valueOf(safeCount)))
              .call()
              .entity(QuestionListDto.class);
    } catch (QuestionGenerationException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new QuestionGenerationException("The question generator is currently unavailable", ex);
    }

    if (questions == null
        || questions.getQuestions() == null
        || questions.getQuestions().isEmpty()) {
      throw new QuestionGenerationException("The question generator returned no questions");
    }

    questions.getQuestions().forEach(question -> question.setPoints(points));
    return questions;
  }

  private static String sanitizeTheme(String theme) {
    String cleaned =
        theme.strip().replaceAll("[\\p{Cntrl}]+", " ").replace("{", "(").replace("}", ")");
    return cleaned.length() > MAX_THEME_LENGTH ? cleaned.substring(0, MAX_THEME_LENGTH) : cleaned;
  }
}
