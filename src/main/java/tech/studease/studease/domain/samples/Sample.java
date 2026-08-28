package tech.studease.studease.domain.samples;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.tests.Test;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Entity
public class Sample {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Integer points;

  private Integer questionsCount;

  @ManyToOne(fetch = FetchType.LAZY)
  private Collection collection;

  @ManyToOne(fetch = FetchType.LAZY)
  private Test test;
}
