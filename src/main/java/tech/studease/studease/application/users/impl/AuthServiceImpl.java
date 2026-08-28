package tech.studease.studease.application.users.impl;

import io.jsonwebtoken.JwtException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tech.studease.studease.api.users.dto.UserDto;
import tech.studease.studease.api.users.dto.UserJwtTokenDto;
import tech.studease.studease.api.users.dto.UserLoginRequestDto;
import tech.studease.studease.api.users.dto.UserRegisterRequestDto;
import tech.studease.studease.application.users.AuthService;
import tech.studease.studease.application.users.mapper.UserMapper;
import tech.studease.studease.common.security.CurrentUser;
import tech.studease.studease.common.util.JwtUtils;
import tech.studease.studease.domain.users.Authority;
import tech.studease.studease.domain.users.Authority.AuthorityName;
import tech.studease.studease.domain.users.AuthorityRepository;
import tech.studease.studease.domain.users.User;
import tech.studease.studease.domain.users.UserRepository;
import tech.studease.studease.domain.users.exception.AuthorityNotFoundException;
import tech.studease.studease.domain.users.exception.UserAlreadyExistsException;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final AuthorityRepository authorityRepository;
  private final AuthenticationManager authenticationManager;
  private final JwtUtils jwtUtils;
  private final PasswordEncoder passwordEncoder;
  private final UserDetailsService userDetailsService;
  private final UserMapper userMapper;
  private final CurrentUser currentUser;

  @Override
  public UserJwtTokenDto register(UserRegisterRequestDto userRequestDto) {
    if (userRepository.existsByEmail(userRequestDto.getEmail())) {
      throw new UserAlreadyExistsException(userRequestDto.getEmail());
    }

    Authority userAuthority =
        authorityRepository
            .findByAuthority(AuthorityName.ROLE_USER)
            .orElseThrow(() -> new AuthorityNotFoundException(AuthorityName.ROLE_USER));
    User user =
        User.builder()
            .email(userRequestDto.getEmail())
            .firstName(userRequestDto.getFirstName())
            .lastName(userRequestDto.getLastName())
            .password(passwordEncoder.encode(userRequestDto.getPassword()))
            .balance(0)
            .isActive(true)
            .authorities(Set.of(userAuthority))
            .build();

    userRepository.save(user);

    return login(new UserLoginRequestDto(userRequestDto.getEmail(), userRequestDto.getPassword()));
  }

  @Override
  public UserJwtTokenDto login(UserLoginRequestDto userRequestDto) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                userRequestDto.getEmail(), userRequestDto.getPassword()));
    return new UserJwtTokenDto(jwtUtils.generateToken(authentication));
  }

  @Override
  public UserDto getCurrentUser() {
    return userMapper.toUserDto(currentUser.require());
  }

  @Override
  public void authenticate(String authorizationHeader) {
    String token = jwtUtils.resolveBearerToken(authorizationHeader);
    if (token == null) {
      return;
    }

    String username;
    try {
      username = jwtUtils.extractSubject(token);
    } catch (JwtException ex) {
      return;
    }

    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
