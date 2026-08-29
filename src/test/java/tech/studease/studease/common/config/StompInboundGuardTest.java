package tech.studease.studease.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import tech.studease.studease.common.security.AttemptTokens;
import tech.studease.studease.domain.sessions.TestSessionRepository;

/**
 * Subscription authorisation for the live-timer socket.
 *
 * <p>The previous guard validated only the <em>shape</em> of a destination ({@code
 * /queue/testSession/{digits}}). Attempt ids are sequential, so any client could walk them and
 * receive other students' force-end payloads, which carry their full question list and answers.
 * Ownership is now proven with the same attempt token the REST flow uses.
 */
class StompInboundGuardTest {

  private static final String TOKEN = "an-attempt-token";
  private static final UUID SESSION_KEY = UUID.fromString("6f1e7b3a-1111-2222-3333-444455556666");

  private TestSessionRepository repository;
  private StompInboundGuard guard;
  private MessageChannel channel;

  @BeforeEach
  void setUp() {
    repository = mock(TestSessionRepository.class);
    guard = new StompInboundGuard(repository);
    channel = mock(MessageChannel.class);
  }

  @Test
  void clientsMayNotPublish() {
    // No @MessageMapping handlers exist, and the simple broker would otherwise relay a forged
    // FORCE_END straight to another student.
    assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SEND, null, null), channel))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void theOldEnumerableDestinationIsRejected() {
    assertThatThrownBy(
            () ->
                guard.preSend(
                    frame(StompCommand.SUBSCRIBE, "/queue/testSession/1", TOKEN), channel))
        .as("sequential-id destinations are what allowed one student to watch another")
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void anArbitraryDestinationIsRejected() {
    assertThatThrownBy(
            () -> guard.preSend(frame(StompCommand.SUBSCRIBE, "/topic/everything", TOKEN), channel))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void aValidDestinationWithoutATokenIsRejected() {
    assertThatThrownBy(
            () -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination(), null), channel))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void aTokenForSomeoneElsesAttemptIsRejected() {
    when(repository.existsBySessionKeyAndAttemptTokenHash(any(), any())).thenReturn(false);

    assertThatThrownBy(
            () -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination(), TOKEN), channel))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  void theOwnerMaySubscribe() {
    when(repository.existsBySessionKeyAndAttemptTokenHash(
            eq(SESSION_KEY), eq(AttemptTokens.hash(TOKEN))))
        .thenReturn(true);

    assertThatCode(
            () -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination(), TOKEN), channel))
        .doesNotThrowAnyException();
  }

  private static String destination() {
    return "/topic/testSession/" + SESSION_KEY;
  }

  private static Message<byte[]> frame(StompCommand command, String destination, String token) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    if (destination != null) {
      accessor.setDestination(destination);
    }
    if (token != null) {
      accessor.setNativeHeader("X-Attempt-Token", token);
    }
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
