package tech.studease.studease.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.studease.studease.common.config.properties.RateLimitProperties;

@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

  private final RateLimitProperties rateLimitProperties;
  private final ObjectMapper objectMapper;

  @Bean
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
    FilterRegistrationBean<RateLimitFilter> registration =
        new FilterRegistrationBean<>(new RateLimitFilter(rateLimitProperties, objectMapper));
    registration.addUrlPatterns("/api/v1/auth/*", "/api/v1/tests/*", "/api/v1/admin/questions/*");
    registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
    return registration;
  }
}
