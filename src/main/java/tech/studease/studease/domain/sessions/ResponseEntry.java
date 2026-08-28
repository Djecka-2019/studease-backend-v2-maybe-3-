package tech.studease.studease.domain.sessions;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.questions.Question;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Entity
public class ResponseEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "question_id",
      foreignKey =
          @ForeignKey(
              foreignKeyDefinition =
                  "FOREIGN KEY (question_id) REFERENCES question(id) ON DELETE CASCADE"))
  private Question question;

  @ManyToMany(fetch = FetchType.LAZY)
  @BatchSize(size = 100)
  @JoinTable(
      name = "response_entry_answers",
      joinColumns =
          @JoinColumn(
              name = "response_entry_id",
              foreignKey =
                  @ForeignKey(
                      foreignKeyDefinition =
                          "FOREIGN KEY (response_entry_id) REFERENCES response_entry(id) ON DELETE CASCADE")),
      inverseJoinColumns =
          @JoinColumn(
              name = "answers_id",
              foreignKey =
                  @ForeignKey(
                      foreignKeyDefinition =
                          "FOREIGN KEY (answers_id) REFERENCES answer(id) ON DELETE CASCADE")))
  private List<Answer> answers;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "test_session_id",
      foreignKey =
          @ForeignKey(
              foreignKeyDefinition =
                  "FOREIGN KEY (test_session_id) REFERENCES test_session(id) ON DELETE CASCADE"))
  private TestSession testSession;
}
