package tech.studease.studease.common.event;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.common.event.message.TimerMessage;
import tech.studease.studease.common.event.message.TimerMessageType;

/**
 * Single place that owns the STOMP destination and payload shape for test-session timer events.
 *
 * <p>The destination is keyed on the attempt's random {@code sessionKey}, not on its primary key.
 * The primary key is a sequential {@code Long}, so the old {@code /queue/testSession/{id}}
 * destination could be enumerated: anyone could subscribe to every id in turn and receive other
 * students' force-end payloads, which carry their full question list and answers. {@code
 * StompInboundGuard} additionally requires the subscriber to prove they own the attempt.
 */
@Component
@RequiredArgsConstructor
public class TestSessionTimerBroadcaster {

  public static final String DESTINATION_PREFIX = "/topic/testSession/";

  private final SimpMessagingTemplate messagingTemplate;

  /** Time-remaining resync; clients count down locally between these. */
  public void sendTick(UUID sessionKey, long secondsLeft) {
    messagingTemplate.convertAndSend(
        destination(sessionKey),
        TimerMessage.of(TimerMessageType.TIMER, (int) Math.max(secondsLeft, 0)));
  }

  public void sendForceEnd(UUID sessionKey, TestSessionDto testSession) {
    messagingTemplate.convertAndSend(
        destination(sessionKey), TimerMessage.of(TimerMessageType.FORCE_END, 0, testSession));
  }

  public static String destination(UUID sessionKey) {
    return DESTINATION_PREFIX + sessionKey;
  }
}
