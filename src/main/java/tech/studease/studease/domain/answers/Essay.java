package tech.studease.studease.domain.answers;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@SuperBuilder
/**
 * @deprecated No longer written. Student essay text lives on {@code ResponseEntry.essayAnswer};
 *     persisting it here parented it to the <em>shared</em> question, which is what leaked one
 *     student's answer to the next. Changeset {@code 003-essay-backfill} relocates and deletes
 *     every such row. The class is kept only so a straggler row left by a partially applied
 *     migration still maps instead of failing with an unknown discriminator value. Remove it once
 *     production has run 003 and {@code SELECT COUNT(*) FROM answer WHERE dtype = 'essay'} is 0.
 */
@Deprecated
@Entity
@DiscriminatorValue("essay")
public class Essay extends Answer {

  private String content;
}
