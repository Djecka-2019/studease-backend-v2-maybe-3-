package tech.studease.studease.domain.tests;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.sessions.TestSession;
import tech.studease.studease.domain.users.User;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Entity
public class Test {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String name;

  private LocalDateTime openDate;
  private LocalDateTime deadline;
  private Integer minutesToComplete;
  private Integer maximumScore;

  @OneToMany(
      mappedBy = "test",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @BatchSize(size = 100)
  private List<TestSession> sessions;

  @OneToMany(
      mappedBy = "test",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @BatchSize(size = 100)
  private Set<Question> questions;

  @OneToMany(
      mappedBy = "test",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @BatchSize(size = 100)
  private Set<Sample> samples;

  @ManyToOne(fetch = FetchType.LAZY)
  private User author;
}
