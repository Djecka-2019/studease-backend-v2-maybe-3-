package tech.studease.studease.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that must run against a real PostgreSQL instance (schema DDL, lazy loading,
 * fetch joins). One container is shared across the whole suite. Skips automatically when Docker is
 * unavailable so {@code ./gradlew build} still succeeds locally; CI always has Docker.
 *
 * <p>The schema here is built by <strong>Liquibase</strong> and then verified by Hibernate's {@code
 * ddl-auto=validate}. That pairing is the regression test for the migrations themselves: if a
 * changeset ever drifts from the entity model, every test in this hierarchy fails to start.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    // Build the schema the way production does, then make Hibernate prove the entity model
    // still matches it. Overrides the H2 defaults in src/test/resources/application.properties.
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.xml");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }
}
