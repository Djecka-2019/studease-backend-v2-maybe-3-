package tech.studease.studease.domain.sessions;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import tech.studease.studease.domain.tests.Test;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Entity
public class TestSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String studentGroup;
  private String studentName;

  private LocalDateTime startedAt;

  /** When the timer runs out ({@code startedAt + test.minutesToComplete}). */
  private LocalDateTime endsAt;

  private LocalDateTime finishedAt;

  private Integer currentQuestionIndex;

  private Integer mark;

  /**
   * SHA-256 (hex) of the opaque attempt token handed to the student once, at start. The token
   * itself is never stored, so a database leak cannot be replayed against the API. Null only for
   * attempts that predate the token.
   */
  @Column(length = 64, unique = true)
  private String attemptTokenHash;

  /**
   * Public, unguessable identifier for this attempt, used as the WebSocket destination segment. The
   * primary key is a sequential {@code Long}: broadcasting on it let anyone subscribe to {@code
   * /queue/testSession/{n}} for every n and watch other students' attempts.
   */
  @Column(nullable = false, unique = true)
  private UUID sessionKey;

  /**
   * Optimistic lock. Two concurrent submissions for one attempt -- a double-click, a retry on a
   * flaky connection -- used to silently last-write-wins; now the loser gets a 409 and can retry.
   */
  @Version private Long version;

  @OneToMany(
      mappedBy = "testSession",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @OrderColumn
  @BatchSize(size = 100)
  private List<ResponseEntry> responses;

  @ManyToOne(fetch = FetchType.LAZY)
  private Test test;

  /**
   * Every attempt gets a WebSocket key, whether or not the caller supplied one. Leaving this to
   * each construction site is how a NOT NULL column ends up violated by some forgotten path.
   */
  @PrePersist
  void assignSessionKey() {
    if (sessionKey == null) {
      sessionKey = UUID.randomUUID();
    }
  }
}
