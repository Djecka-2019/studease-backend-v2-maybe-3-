package tech.studease.studease.common.config;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.common.config.properties.AdminProperties;
import tech.studease.studease.domain.users.Authority;
import tech.studease.studease.domain.users.Authority.AuthorityName;
import tech.studease.studease.domain.users.AuthorityRepository;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.UserRepository;

@Component
@RequiredArgsConstructor
public class DataLoader implements ApplicationListener<ApplicationReadyEvent> {

  private final UserRepository userRepository;
  private final AuthorityRepository authorityRepository;
  private final PasswordEncoder passwordEncoder;
  private final AdminProperties adminProperties;

  @Override
  @Transactional
  public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
    Authority userAuthority = createAuthorityIfNotFound(AuthorityName.ROLE_USER);
    Authority adminAuthority = createAuthorityIfNotFound(AuthorityName.ROLE_ADMIN);
    if (!userRepository.existsByEmail(adminProperties.email())) {
      User user =
          User.builder()
              .email(adminProperties.email())
              .firstName("Admin")
              .lastName("Admin")
              .password(passwordEncoder.encode(adminProperties.password()))
              .balance(1_000_000)
              .isActive(true)
              .authorities(Set.of(userAuthority, adminAuthority))
              .build();

      userRepository.save(user);
    }
  }

  private Authority createAuthorityIfNotFound(AuthorityName authority) {
    return authorityRepository
        .findByAuthority(authority)
        .orElseGet(
            () -> authorityRepository.save(Authority.builder().authority(authority).build()));
  }
}
