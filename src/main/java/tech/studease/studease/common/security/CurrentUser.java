package tech.studease.studease.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.exception.TokenExpiredException;

/**
 * Resolves the authenticated {@link User} for the current request. Injectable replacement for the
 * former static {@code JwtUtils.getUserFromAuthentication()} so services no longer reach into
 * {@link SecurityContextHolder} directly.
 */
@Component
public class CurrentUser {

  public User require() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof User user) {
      return user;
    }
    throw new TokenExpiredException();
  }

  public String requireEmail() {
    return require().getEmail();
  }
}
