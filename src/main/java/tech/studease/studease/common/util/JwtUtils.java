package tech.studease.studease.common.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tech.studease.studease.common.config.properties.JwtProperties;

@Component
public class JwtUtils {

  private static final String BEARER_PREFIX = "Bearer ";

  private final SecretKey signingKey;
  private final long expirationMillis;

  public JwtUtils(JwtProperties properties) {
    this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    this.expirationMillis = properties.expiration();
  }

  public String generateToken(Authentication authentication) {
    Date now = new Date();
    return Jwts.builder()
        .subject(authentication.getName())
        .issuedAt(now)
        .expiration(new Date(now.getTime() + expirationMillis))
        .signWith(signingKey)
        .compact();
  }

  public String extractSubject(String token) {
    return Jwts.parser()
        .verifyWith(signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
  }

  public String resolveBearerToken(String authorizationHeader) {
    if (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
      return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }
    return null;
  }
}
