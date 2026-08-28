package tech.studease.studease.domain.tests;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestRepository extends JpaRepository<Test, UUID> {

  @EntityGraph(attributePaths = {"author"})
  @NonNull
  List<Test> findAllById(@NonNull Iterable<UUID> ids);

  @EntityGraph(attributePaths = {"sessions"})
  List<Test> findByAuthorEmail(String email);

  @EntityGraph(attributePaths = {"questions", "samples", "author"})
  @NonNull
  Optional<Test> findById(@NonNull UUID testId);

  @EntityGraph(attributePaths = {"questions", "samples"})
  Optional<Test> getTestById(@NonNull UUID testId);
}
