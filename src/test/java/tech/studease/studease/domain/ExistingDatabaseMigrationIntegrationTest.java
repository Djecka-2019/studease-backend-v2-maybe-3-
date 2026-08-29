package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The production rollout path, and the riskiest step in adopting Liquibase: the live database
 * <em>already has</em> the schema, built by {@code ddl-auto=update}, and it holds real student
 * work. Applying the baseline there would fail on "table already exists", and a careless essay
 * migration would destroy submitted answers.
 *
 * <p>This test seeds a container with the pre-Liquibase schema <em>and legacy essay data</em>
 * before Spring starts, then boots the application against it and asserts that
 *
 * <ul>
 *   <li>{@code 001-baseline} is recorded as {@code MARK_RAN} — skipped, not executed;
 *   <li>{@code 002-indexes} really did run, so the indexes exist;
 *   <li>{@code 003} moved every student's essay onto their own attempt, losing none of it;
 *   <li>Hibernate's {@code ddl-auto=validate} still accepts the result.
 * </ul>
 *
 * <p>Together with {@link SchemaIndexIntegrationTest} (the fresh-database path) this covers both
 * sides of the rollout, so the first production deploy needs no manual {@code changelog-sync}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ExistingDatabaseMigrationIntegrationTest {

  private static final String ESSAY_ONE = "First student essay that must survive the migration";
  private static final String ESSAY_TWO = "Second student essay, same question, different author";

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
    execute(readBaselineDdl());
    execute(legacyEssayData());
  }

  /**
   * Two students answered the same shared essay question. Under the old model both essays were rows
   * in {@code answer} parented to that one question — which is exactly what leaked. The migration
   * has to hand each essay back to the attempt that produced it.
   */
  private static String legacyEssayData() {
    return """
        INSERT INTO public.test (id, name, open_date, deadline, minutes_to_complete)
        VALUES ('11111111-1111-1111-1111-111111111111', 'Legacy quiz',
                now() - interval '1 hour', now() + interval '1 hour', 30);

        INSERT INTO public.question (id, content, points, type, test_id)
        VALUES (500, 'Legacy essay question', 5, 3,
                '11111111-1111-1111-1111-111111111111');

        INSERT INTO public.test_session
            (id, student_group, student_name, started_at, ends_at, current_question_index, test_id)
        VALUES (600, 'CS-9', 'Student One', now(), now() + interval '30 minutes', 0,
                '11111111-1111-1111-1111-111111111111'),
               (601, 'CS-9', 'Student Two', now(), now() + interval '30 minutes', 0,
                '11111111-1111-1111-1111-111111111111');

        INSERT INTO public.response_entry (id, responses_order, question_id, test_session_id)
        VALUES (700, 0, 500, 600),
               (701, 0, 500, 601);

        INSERT INTO public.answer (id, is_correct, question_id, dtype, content)
        VALUES (800, true, 500, 'essay', '%s'),
               (801, true, 500, 'essay', '%s');

        INSERT INTO public.response_entry_answers (answers_id, response_entry_id)
        VALUES (800, 700), (801, 701);
        """
        .formatted(ESSAY_ONE, ESSAY_TWO);
  }

  private static void execute(String sql) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String single : sql.split(";")) {
        if (!single.isBlank()) {
          statement.execute(single);
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("could not seed the pre-Liquibase database", ex);
    }
  }

  /** Strips the Liquibase directives from the baseline changelog, leaving executable DDL. */
  private static String readBaselineDdl() {
    try (InputStream in =
        ExistingDatabaseMigrationIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream("db/changelog/changes/001-baseline.sql")) {
      if (in == null) {
        throw new IllegalStateException("001-baseline.sql not on the test classpath");
      }
      StringBuilder ddl = new StringBuilder();
      new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.trim().startsWith("--"))
          .forEach(line -> ddl.append(line).append('\n'));
      return ddl.toString();
    } catch (IOException ex) {
      throw new IllegalStateException("could not read 001-baseline.sql", ex);
    }
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.xml");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Autowired private EntityManager entityManager;

  @Test
  @Transactional(readOnly = true)
  void baselineIsMarkedRanAndLaterChangesetsAreApplied() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery("SELECT id, exectype FROM databasechangelog ORDER BY orderexecuted")
            .getResultList();

    assertThat(rows)
        .as("the baseline must be skipped on a database that already has the schema")
        .anySatisfy(
            row -> {
              assertThat(row[0]).isEqualTo("001-baseline");
              assertThat(row[1]).isEqualTo("MARK_RAN");
            });

    assertThat(rows)
        .as("every index changeset must have actually executed")
        .filteredOn(row -> String.valueOf(row[0]).startsWith("002-indexes"))
        .isNotEmpty()
        .allSatisfy(row -> assertThat(row[1]).isEqualTo("EXECUTED"));

    @SuppressWarnings("unchecked")
    List<String> indexes =
        entityManager
            .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
            .getResultList();
    assertThat(indexes)
        .containsAll(
            Arrays.asList(
                "idx_test_session_test_student",
                "idx_test_session_unfinished",
                "idx_response_entry_test_session",
                "idx_answer_question",
                "idx_users_email"));
  }

  @Test
  @Transactional(readOnly = true)
  void everyLegacyEssayIsHandedBackToItsOwnAttempt() {
    assertThat(essayAnswerOf(700))
        .as("the first student's submitted work must survive the migration verbatim")
        .isEqualTo(ESSAY_ONE);
    assertThat(essayAnswerOf(701))
        .as("and must not be confused with the other student's, on the same shared question")
        .isEqualTo(ESSAY_TWO);
  }

  @Test
  @Transactional(readOnly = true)
  void theSharedEssayRowsAreGoneAfterBackfill() {
    assertThat(countOf("SELECT COUNT(*) FROM answer WHERE dtype = 'essay'"))
        .as("the leaking rows must be removed once their text has been relocated")
        .isZero();
    assertThat(
            countOf(
                "SELECT COUNT(*) FROM response_entry_answers rea"
                    + " JOIN answer a ON a.id = rea.answers_id WHERE a.dtype = 'essay'"))
        .as("and so must their join rows")
        .isZero();
  }

  private String essayAnswerOf(long responseEntryId) {
    return (String)
        entityManager
            .createNativeQuery("SELECT essay_answer FROM response_entry WHERE id = :id")
            .setParameter("id", responseEntryId)
            .getSingleResult();
  }

  private long countOf(String sql) {
    return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
  }
}
