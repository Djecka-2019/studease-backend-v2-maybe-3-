package tech.studease.studease.domain.sessions.exception;

/**
 * The {@code X-Attempt-Token} header was missing, malformed, or did not match a live attempt on the
 * test named in the path.
 *
 * <p>The message is deliberately uniform: distinguishing "no such token" from "token belongs to a
 * different test" would let a caller probe for valid tokens.
 */
public class InvalidAttemptTokenException extends RuntimeException {

  public InvalidAttemptTokenException() {
    super("Invalid or expired attempt token");
  }
}
