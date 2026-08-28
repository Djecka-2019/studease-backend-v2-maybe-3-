package tech.studease.studease.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.studease.studease.application.users.AuthService;
import tech.studease.studease.domain.users.exception.UserNotFoundException;

@RequiredArgsConstructor
public class HttpAuthTokenFilter extends OncePerRequestFilter {

  private final AuthService authService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws IOException, ServletException {

    try {
      authService.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
    } catch (UserNotFoundException ignored) {
    }

    filterChain.doFilter(request, response);
  }
}
