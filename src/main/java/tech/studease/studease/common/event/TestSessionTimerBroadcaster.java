package tech.studease.studease.common.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.common.event.message.TimerMessage;
import tech.studease.studease.common.event.message.TimerMessageType;

/** Single place that owns the STOMP destination and payload shape for test-session timer events. */
@Component
@RequiredArgsConstructor
public class TestSessionTimerBroadcaster {

  private final SimpMessagingTemplate messagingTemplate;

  /** Time-remaining resync; clients count down locally between these. */
  public void sendTick(Long testSessionId, long secondsLeft) {
    messagingTemplate.convertAndSend(
        destination(testSessionId),
        TimerMessage.of(TimerMessageType.TIMER, (int) Math.max(secondsLeft, 0)));
  }

  public void sendForceEnd(Long testSessionId, TestSessionDto testSession) {
    messagingTemplate.convertAndSend(
        destination(testSessionId), TimerMessage.of(TimerMessageType.FORCE_END, 0, testSession));
  }

  private static String destination(Long testSessionId) {
    return "/queue/testSession/" + testSessionId;
  }
}
