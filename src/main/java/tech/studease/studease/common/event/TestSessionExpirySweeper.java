package tech.studease.studease.common.event;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.studease.studease.application.sessions.TestSessionService;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.sessions.TestSessionRepository;

/**
 * Periodically reconciles running test sessions against the wall clock: resends the remaining time
 * (clients count down locally between ticks) and force-ends sessions whose {@code endsAt} has
 * passed. State lives entirely in the database, so a restart or redeploy never drops a live timer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestSessionExpirySweeper {

  private final TestSessionRepository testSessionRepository;
  private final TestSessionService testSessionService;
  private final TestSessionTimerBroadcaster broadcaster;

  @Scheduled(fixedDelayString = "${app.test-session.sweep-interval-ms:15000}")
  public void sweep() {
    LocalDateTime now = LocalDateTime.now();
    for (TestSession session : testSessionRepository.findByFinishedAtIsNull()) {
      if (session.getEndsAt() == null) {
        continue;
      }
      long secondsLeft = Duration.between(now, session.getEndsAt()).toSeconds();
      if (secondsLeft > 0) {
        broadcaster.sendTick(session.getSessionKey(), secondsLeft);
      } else {
        forceEnd(session);
      }
    }
  }

  private void forceEnd(TestSession session) {
    try {
      broadcaster.sendForceEnd(
          session.getSessionKey(), testSessionService.forceEndTestSession(session.getId()));
    } catch (RuntimeException ex) {
      log.warn("Failed to force-end expired test session {}", session.getId(), ex);
    }
  }
}
