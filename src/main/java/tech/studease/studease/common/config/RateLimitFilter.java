package tech.studease.studease.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.studease.studease.common.config.properties.RateLimitProperties;
import tech.studease.studease.common.error.ErrorResponse;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private static final int MAX_TRACKED_CLIENTS = 100_000;

  private enum Scope {
    AUTH,
    STUDENT,
    AI_GENERATE
  }

  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    Scope scope = resolveScope(request);
    if (!properties.enabled() || scope == null) {
      filterChain.doFilter(request, response);
      return;
    }

    Bucket bucket = bucketFor(scope, request.getRemoteAddr());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      response.setHeader("X-Rate-Limit-Remaining", Long.toString(probe.getRemainingTokens()));
      filterChain.doFilter(request, response);
      return;
    }

    long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ErrorResponse.builder()
            .status(HttpStatus.TOO_MANY_REQUESTS.value())
            .error(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase())
            .message("Too many requests, retry in " + retryAfter + "s")
            .path(request.getRequestURI())
            .build());
  }

  private Bucket bucketFor(Scope scope, String clientIp) {
    if (buckets.size() > MAX_TRACKED_CLIENTS) {
      buckets.clear();
    }
    return buckets.computeIfAbsent(scope + "|" + clientIp, key -> newBucket(scope));
  }

  private Bucket newBucket(Scope scope) {
    Bandwidth limit =
        switch (scope) {
          case AUTH ->
              Bandwidth.builder()
                  .capacity(properties.authPerMinute())
                  .refillGreedy(properties.authPerMinute(), Duration.ofMinutes(1))
                  .build();
          case STUDENT ->
              Bandwidth.builder()
                  .capacity(properties.studentPerMinute())
                  .refillGreedy(properties.studentPerMinute(), Duration.ofMinutes(1))
                  .build();
          case AI_GENERATE ->
              Bandwidth.builder()
                  .capacity(properties.aiGeneratePerHour())
                  .refillGreedy(properties.aiGeneratePerHour(), Duration.ofHours(1))
                  .build();
        };
    return Bucket.builder().addLimit(limit).build();
  }

  private static Scope resolveScope(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri.startsWith("/api/v1/auth/")) {
      return Scope.AUTH;
    }
    if (uri.startsWith("/api/v1/admin/questions/generate")) {
      return Scope.AI_GENERATE;
    }
    if (uri.startsWith("/api/v1/tests/")) {
      return Scope.STUDENT;
    }
    return null;
  }
}
