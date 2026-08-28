package tech.studease.studease.domain.collections;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

  @EntityGraph(attributePaths = {"questions"})
  List<Collection> findAllByAuthorEmail(String authorEmail);

  @EntityGraph(attributePaths = {"questions", "samples"})
  Optional<Collection> findByIdAndAuthorEmail(Long id, String authorEmail);

  boolean existsByNameAndAuthorEmail(String name, String authorEmail);

  boolean existsByIdAndAuthorEmail(Long id, String authorEmail);
}
