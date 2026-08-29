package tech.studease.studease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.support.PostgresIntegrationTest;

/**
 * Guards the indexes added in {@code 002-indexes.sql}. Before that changeset the schema had no
 * secondary indexes at all — {@code ddl-auto=update} creates foreign-key constraints, and
 * PostgreSQL does not index FK columns — so the student hot path sequentially scanned {@code
 * test_session} on every request.
 *
 * <p>This test fails if a future migration drops one, or if the changelog stops being applied.
 */
class SchemaIndexIntegrationTest extends PostgresIntegrationTest {

  @Autowired private EntityManager entityManager;

  @Test
  @Transactional(readOnly = true)
  void migrationsCreateTheHotPathIndexes() {
    @SuppressWarnings("unchecked")
    List<String> indexes =
        entityManager
            .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
            .getResultList();

    assertThat(indexes)
        .contains(
            "idx_test_session_test_student",
            "idx_test_session_unfinished",
            "idx_response_entry_test_session",
            "idx_response_entry_question",
            "idx_response_entry_answers_entry",
            "idx_response_entry_answers_answer",
            "idx_answer_question",
            "idx_question_test",
            "idx_question_collection",
            "idx_sample_test",
            "idx_sample_collection",
            "idx_test_author",
            "idx_collection_author",
            "idx_users_email");
  }

  @Test
  @Transactional(readOnly = true)
  void studentSessionLookupUsesAnIndexRatherThanASequentialScan() {
    // The query behind every current-question / next-question / current-session / finish call.
    @SuppressWarnings("unchecked")
    List<String> plan =
        entityManager
            .createNativeQuery(
                "EXPLAIN SELECT * FROM test_session"
                    + " WHERE test_id = '00000000-0000-0000-0000-000000000001'"
                    + " AND student_group = 'g' AND student_name = 'n'")
            .getResultList();

    // An empty table is always seq-scanned, so assert the planner *can* use the index by
    // disabling seq scan: if the index were missing this would still say "Seq Scan".
    entityManager.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
    @SuppressWarnings("unchecked")
    List<String> forcedPlan =
        entityManager
            .createNativeQuery(
                "EXPLAIN SELECT * FROM test_session"
                    + " WHERE test_id = '00000000-0000-0000-0000-000000000001'"
                    + " AND student_group = 'g' AND student_name = 'n'")
            .getResultList();

    assertThat(String.join("\n", forcedPlan))
        .as("planner must have idx_test_session_test_student available; full plan was:\n%s", plan)
        .contains("idx_test_session_test_student");
  }
}
