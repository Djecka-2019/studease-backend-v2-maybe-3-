package tech.studease.studease.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tech.studease.studease.application.users.AuthService;
import tech.studease.studease.common.config.properties.CorsProperties;
import tech.studease.studease.common.error.ErrorResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private static final String HEADER_X_REQUESTED_WITH = "X-Requested-With";
  private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

  // Reachable without a JWT; everything else requires authentication. "/api/v1/tests/**" is the
  // student test-taking flow whose per-attempt-token hardening is tracked separately.
  private static final String[] PUBLIC_ENDPOINTS = {
    "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/tests/**", "/ws/**", "/error"
  };

  private final CorsProperties corsProperties;
  private final ObjectMapper objectMapper;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuthenticationManager authenticationManager(
      AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
  }

  @Bean
  public HttpAuthTokenFilter authenticationJwtTokenFilter(AuthService authService) {
    return new HttpAuthTokenFilter(authService);
  }

  @Bean
  public FilterRegistrationBean<HttpAuthTokenFilter> httpAuthTokenFilterRegistration(
      HttpAuthTokenFilter filter) {
    FilterRegistrationBean<HttpAuthTokenFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(corsProperties.allowedOriginPatterns());
    configuration.setAllowedMethods(
        List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name()));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.ORIGIN,
            HttpHeaders.ACCEPT,
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
            HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
            HEADER_X_REQUESTED_WITH));
    configuration.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public AuthenticationEntryPoint unauthorizedHandler() {
    return (request, response, authException) ->
        writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required", request);
  }

  @Bean
  public AccessDeniedHandler forbiddenHandler() {
    return (request, response, accessDeniedException) ->
        writeError(
            response, HttpStatus.FORBIDDEN, "User is forbidden to access this resource", request);
  }

  /**
   * Actuator runs on its own internal management port (not published by the compose file); allow
   * the exposed endpoints through so the container health check and metric scrapers can reach them.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, HttpAuthTokenFilter authenticationJwtTokenFilter) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(
            headers ->
                headers
                    .frameOptions(FrameOptionsConfig::sameOrigin)
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(unauthorizedHandler())
                    .accessDeniedHandler(forbiddenHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    http.addFilterBefore(authenticationJwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private void writeError(
      HttpServletResponse response, HttpStatus status, String message, HttpServletRequest request)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ErrorResponse.builder()
            .status(status.value())
            .error(status.getReasonPhrase())
            .message(message)
            .path(request.getRequestURI())
            .build());
  }
}
