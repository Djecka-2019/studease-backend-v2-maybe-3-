package tech.studease.studease.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque bearer tokens identifying one student's attempt at one test.
 *
 * <p>Issued once by {@code POST /api/v1/tests/{testId}/start} and required on every later call for
 * that attempt. Only the SHA-256 of the token is persisted, so a database leak yields nothing
 * replayable. A plain hash is the right choice here rather than a password KDF: the input is 256
 * bits of {@link SecureRandom} output, so there is no dictionary to attack, and the lookup has to
 * stay a single indexed equality match on the student hot path.
 *
 * <p>Tokens must never be logged or echoed into error messages.
 */
public final class AttemptTokens {

  /** 256 bits, matching the SHA-256 output width. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private AttemptTokens() {}

  /** A fresh URL-safe token. Returned to the client once and never stored. */
  public static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** Lowercase hex SHA-256 of {@code token}; this is what is persisted and looked up. */
  public static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandated by the JLS; this cannot happen on a conformant JVM.
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
