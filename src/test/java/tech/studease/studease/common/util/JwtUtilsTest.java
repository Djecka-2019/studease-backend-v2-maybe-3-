package tech.studease.studease.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tech.studease.studease.common.config.properties.JwtProperties;

class JwtUtilsTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

  private final JwtUtils jwtUtils = new JwtUtils(new JwtProperties(SECRET, 60_000L));

  @Test
  void roundTripsTheSubject() {
    String token = jwtUtils.generateToken(auth("teacher@studease.test"));
    assertThat(jwtUtils.extractSubject(token)).isEqualTo("teacher@studease.test");
  }

  @Test
  void rejectsATokenSignedWithAnotherKey() {
    JwtUtils other =
        new JwtUtils(new JwtProperties("a-completely-different-secret-key-0123456789", 60_000L));
    String foreignToken = other.generateToken(auth("teacher@studease.test"));

    assertThatThrownBy(() -> jwtUtils.extractSubject(foreignToken))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsAnExpiredToken() {
    JwtUtils shortLived = new JwtUtils(new JwtProperties(SECRET, -1_000L));
    String expired = shortLived.generateToken(auth("teacher@studease.test"));

    assertThatThrownBy(() -> jwtUtils.extractSubject(expired)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsGarbage() {
    assertThatThrownBy(() -> jwtUtils.extractSubject("not.a.jwt")).isInstanceOf(JwtException.class);
  }

  @Test
  void resolvesOnlyBearerHeaders() {
    assertThat(jwtUtils.resolveBearerToken("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
    assertThat(jwtUtils.resolveBearerToken("bearer abc")).isNull();
    assertThat(jwtUtils.resolveBearerToken("Basic abc")).isNull();
    assertThat(jwtUtils.resolveBearerToken(null)).isNull();
  }

  private static Authentication auth(String name) {
    return new UsernamePasswordAuthenticationToken(name, "pw");
  }
}
