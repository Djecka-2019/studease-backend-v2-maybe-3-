package tech.studease.studease.common.config;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import tech.studease.studease.common.security.AttemptTokens;
import tech.studease.studease.domain.sessions.TestSessionRepository;

/**
 * STOMP inbound hardening.
 *
 * <p>Clients may not publish: the application declares no {@code @MessageMapping} handlers, and the
 * simple broker would otherwise relay a forged {@code FORCE_END} straight to another student.
 *
 * <p>A subscription must name a valid attempt destination <em>and</em> prove ownership of it by
 * presenting the same {@code X-Attempt-Token} the REST flow uses. Shape validation alone was not
 * enough: the previous guard checked only that the destination looked like {@code
 * /queue/testSession/{digits}}, so any client could walk the sequential ids and watch other
 * students' attempts.
 */
@RequiredArgsConstructor
class StompInboundGuard implements ChannelInterceptor {

  private static final Pattern ATTEMPT_DESTINATION =
      Pattern.compile(
          "^/topic/testSession/"
              + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

  private static final String ATTEMPT_TOKEN_HEADER = "X-Attempt-Token";

  private final TestSessionRepository testSessionRepository;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    StompCommand command = accessor.getCommand();
    if (command == null) {
      return message;
    }
    switch (command) {
      case SEND ->
          throw new MessagingException("Publishing to the message broker is not permitted");
      case CONNECT -> rememberConnectToken(accessor);
      case SUBSCRIBE -> authorizeSubscription(accessor);
      default -> {
        // DISCONNECT / UNSUBSCRIBE / ACK / NACK pass through.
      }
    }
    return message;
  }

  /**
   * Carries a token given on CONNECT into the session so SUBSCRIBE frames need not repeat it.
   * Several STOMP clients cannot set per-subscription native headers.
   */
  private static void rememberConnectToken(StompHeaderAccessor accessor) {
    List<String> values = accessor.getNativeHeader(ATTEMPT_TOKEN_HEADER);
    if (values != null && !values.isEmpty() && accessor.getSessionAttributes() != null) {
      accessor.getSessionAttributes().put(ATTEMPT_TOKEN_HEADER, values.get(0));
    }
  }

  private void authorizeSubscription(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    Matcher matcher = destination == null ? null : ATTEMPT_DESTINATION.matcher(destination.strip());
    if (matcher == null || !matcher.matches()) {
      throw new MessagingException("Subscription to this destination is not allowed");
    }

    UUID sessionKey = UUID.fromString(matcher.group(1));
    String attemptToken = firstHeader(accessor, ATTEMPT_TOKEN_HEADER);
    if (attemptToken == null || attemptToken.isBlank()) {
      throw new MessagingException("An attempt token is required to subscribe");
    }
    if (!testSessionRepository.existsBySessionKeyAndAttemptTokenHash(
        sessionKey, AttemptTokens.hash(attemptToken))) {
      // Same message either way: do not reveal whether the destination exists.
      throw new MessagingException("Subscription to this destination is not allowed");
    }
  }

  /**
   * Reads a STOMP native header. The token may ride on either the SUBSCRIBE frame or the CONNECT
   * frame that opened the session, since clients differ in which they can set per subscription.
   */
  private static String firstHeader(StompHeaderAccessor accessor, String name) {
    List<String> values = accessor.getNativeHeader(name);
    if (values != null && !values.isEmpty()) {
      return values.get(0);
    }
    Object fromSession =
        accessor.getSessionAttributes() == null ? null : accessor.getSessionAttributes().get(name);
    return fromSession instanceof String token ? token : null;
  }
}
