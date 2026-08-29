package tech.studease.studease.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dumps the schema that Hibernate's {@code ddl-auto=create} produces for the current entity model,
 * from a real PostgreSQL 16 instance, via {@code pg_dump --schema-only}.
 *
 * <p>This is a development tool, not a test. It exists so the Liquibase baseline is derived from
 * the schema Hibernate actually emits rather than from hand-guessed column names. Run it with:
 *
 * <pre>./gradlew test --tests "*SchemaBaselineExportTool*" -Dschema.export=true</pre>
 *
 * <p>and diff {@code build/schema-baseline.sql} against {@code
 * src/main/resources/db/changelog/changes/001-baseline.xml}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "schema.export", matches = "true")
class SchemaBaselineExportTool {

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
    // Force a full create from the entity model, and keep Liquibase out of the way.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    registry.add("spring.liquibase.enabled", () -> "false");
  }

  @Autowired private org.springframework.core.env.Environment environment;

  @Test
  void dumpSchema() throws Exception {
    // Touching the context is enough: Hibernate has already run ddl-auto=create by now.
    org.junit.jupiter.api.Assertions.assertNotNull(environment);

    Container.ExecResult result =
        POSTGRES.execInContainer(
            "pg_dump",
            "--schema-only",
            "--no-owner",
            "--no-privileges",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            POSTGRES.getDatabaseName());

    if (result.getExitCode() != 0) {
      throw new IllegalStateException("pg_dump failed: " + result.getStderr());
    }

    Path out = Path.of("build", "schema-baseline.sql");
    Files.createDirectories(out.getParent());
    Files.writeString(out, result.getStdout());

    System.out.println("=== " + out.toAbsolutePath() + " ===");
    System.out.println(result.getStdout());
  }
}
