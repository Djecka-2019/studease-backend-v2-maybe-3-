package tech.studease.studease.common.error;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static tech.studease.studease.common.util.ValidationUtils.getErrorResponseOfFieldErrors;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tech.studease.studease.application.questions.exception.QuestionGenerationException;
import tech.studease.studease.domain.collections.exception.CollectionAlreadyExistsException;
import tech.studease.studease.domain.collections.exception.CollectionInUseException;
import tech.studease.studease.domain.sessions.exception.TestSessionAlreadyExistsException;
import tech.studease.studease.domain.users.exception.TokenExpiredException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleBadCredentialsException(WebRequest request) {
    return build(UNAUTHORIZED, "Wrong password", request);
  }

  @ExceptionHandler({TokenExpiredException.class, AuthorizationDeniedException.class})
  public ResponseEntity<ErrorResponse> handleAuthorizationException(
      RuntimeException exc, WebRequest request) {
    return build(UNAUTHORIZED, exc.getMessage(), request);
  }

  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFoundException(
      EntityNotFoundException exc, WebRequest request) {
    return build(NOT_FOUND, exc.getMessage(), request);
  }

  @ExceptionHandler({
    CollectionAlreadyExistsException.class,
    IllegalArgumentException.class,
    TestSessionAlreadyExistsException.class,
    IllegalStateException.class,
    CollectionInUseException.class
  })
  public ResponseEntity<ErrorResponse> handleBadRequestException(
      RuntimeException exc, WebRequest request) {
    return build(BAD_REQUEST, exc.getMessage(), request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException exc, WebRequest request) {
    return build(BAD_REQUEST, exc.getMessage(), request);
  }

  @ExceptionHandler(QuestionGenerationException.class)
  public ResponseEntity<ErrorResponse> handleQuestionGeneration(
      QuestionGenerationException exc, WebRequest request) {
    log.warn("Question generation failed", exc);
    return build(HttpStatus.BAD_GATEWAY, exc.getMessage(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception exc, WebRequest request) {
    log.error("Unhandled exception", exc);
    return build(INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exc,
      @NonNull HttpHeaders headers,
      @NonNull HttpStatusCode status,
      @NonNull WebRequest request) {
    return ResponseEntity.status(BAD_REQUEST)
        .body(getErrorResponseOfFieldErrors(exc.getBindingResult().getAllErrors(), request));
  }

  private static ResponseEntity<ErrorResponse> build(
      HttpStatus status, String message, WebRequest request) {
    return ResponseEntity.status(status)
        .body(
            ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(cleanPath(request))
                .build());
  }

  private static String cleanPath(WebRequest request) {
    String description = request.getDescription(false);
    return description.startsWith("uri=") ? description.substring(4) : description;
  }
}
