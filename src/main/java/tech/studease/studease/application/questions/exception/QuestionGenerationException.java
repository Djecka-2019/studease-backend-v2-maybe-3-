package tech.studease.studease.application.questions.exception;

public class QuestionGenerationException extends RuntimeException {

  public QuestionGenerationException(String message, Throwable cause) {
    super(message, cause);
  }

  public QuestionGenerationException(String message) {
    super(message);
  }
}
