package tech.studease.studease.common.config;

import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * Minimal STOMP inbound hardening: the app has no {@code @MessageMapping} handlers, so clients have
 * no reason to publish anything, and subscriptions are limited to a single per-session timer topic.
 * Per-subscription authorization (binding a subscription to its owner) lands with the student
 * attempt-token change.
 */
class StompInboundGuard implements ChannelInterceptor {

  private static final Pattern ALLOWED_SUBSCRIPTION =
      Pattern.compile("^/(queue|topic)/testSession/\\d+$");

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
      case SUBSCRIBE -> {
        String destination = accessor.getDestination();
        if (destination == null || !ALLOWED_SUBSCRIPTION.matcher(destination).matches()) {
          throw new MessagingException("Subscription to '" + destination + "' is not allowed");
        }
      }
      default -> {
        // CONNECT / DISCONNECT / UNSUBSCRIBE / ACK / NACK pass through.
      }
    }
    return message;
  }
}
