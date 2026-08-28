package tech.studease.studease.domain.questions;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {

  @EntityGraph(attributePaths = {"answers"})
  Set<Question> findByTestId(UUID testId);

  @EntityGraph(attributePaths = {"answers"})
  Set<Question> findByCollectionId(Long collectionId);
}
