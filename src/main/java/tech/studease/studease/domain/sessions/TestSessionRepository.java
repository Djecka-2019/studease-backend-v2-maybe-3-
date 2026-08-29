package tech.studease.studease.domain.sessions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestSessionRepository extends JpaRepository<TestSession, Long> {

  @NonNull
  @EntityGraph(attributePaths = {"test", "test.questions", "test.samples", "responses"})
  Optional<TestSession> findById(@NonNull Long id);

  @EntityGraph(attributePaths = {"test", "responses"})
  Optional<TestSession> findTestSessionByStudentGroupAndStudentNameAndTestId(
      String studentGroup, String studentName, UUID testId);

  /** The student hot path: one indexed equality match on idx_test_session_attempt_token. */
  @EntityGraph(attributePaths = {"test", "responses"})
  Optional<TestSession> findByAttemptTokenHash(String attemptTokenHash);

  /**
   * Used by the STOMP guard to check that a subscriber owns the destination they are asking for.
   * Deliberately fetches nothing but the row itself.
   */
  boolean existsBySessionKeyAndAttemptTokenHash(UUID sessionKey, String attemptTokenHash);

  @EntityGraph(attributePaths = {"responses"})
  List<TestSession> findTestSessionsByTestId(UUID testId);

  List<TestSession> findByFinishedAtIsNull();
}
