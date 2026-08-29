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
 * <em>already has</em> the schema, built years ago by {@code ddl-auto=update}. Applying the
 * baseline there would fail on "table already exists".
 *
 * <p>This test seeds a container with the pre-Liquibase schema <em>before</em> Spring starts, then
 * boots the application against it and asserts that
 *
 * <ul>
 *   <li>{@code 001-baseline} is recorded as {@code MARK_RAN} — skipped, not executed;
 *   <li>{@code 002-indexes} really did run, so the indexes exist;
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

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
    seedPreLiquibaseSchema();
  }

  /**
   * Replays the baseline DDL directly, standing in for a database that {@code ddl-auto=update}
   * built before Liquibase existed. Liquibase's own bookkeeping tables are deliberately absent.
   */
  private static void seedPreLiquibaseSchema() {
    String ddl = readBaselineDdl();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String sql : ddl.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("could not seed the pre-Liquibase schema", ex);
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
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.trim().startsWith("--"))
          .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'), (a, b) -> a)
          .toString();
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
  void baselineIsMarkedRanAndIndexesAreStillApplied() {
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
}
