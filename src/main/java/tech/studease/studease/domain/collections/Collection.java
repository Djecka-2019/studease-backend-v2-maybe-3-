package tech.studease.studease.domain.collections;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.samples.Sample;
import tech.studease.studease.domain.users.User;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Entity
public class Collection {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  @OneToMany(
      mappedBy = "collection",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @BatchSize(size = 100)
  private Set<Question> questions;

  @OneToMany(
      mappedBy = "collection",
      fetch = FetchType.LAZY,
      orphanRemoval = true,
      cascade = CascadeType.ALL)
  @BatchSize(size = 100)
  private Set<Sample> samples;

  @ManyToOne(fetch = FetchType.LAZY)
  private User author;
}
