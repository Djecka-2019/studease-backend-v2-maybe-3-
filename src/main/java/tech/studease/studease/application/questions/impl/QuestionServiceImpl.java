package tech.studease.studease.application.questions.impl;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.api.questions.dto.QuestionDto;
import tech.studease.studease.api.questions.dto.QuestionListDto;
import tech.studease.studease.application.questions.QuestionService;
import tech.studease.studease.application.questions.mapper.QuestionMapper;
import tech.studease.studease.common.security.CurrentUser;
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.collections.exception.CollectionNotFoundException;
import tech.studease.studease.domain.questions.Question;
import tech.studease.studease.domain.questions.QuestionRepository;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.tests.exception.TestNotFoundException;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

  private final QuestionRepository questionRepository;
  private final CollectionRepository collectionRepository;
  private final TestRepository testRepository;
  private final QuestionMapper questionMapper;
  private final CurrentUser currentUser;

  @Override
  @Transactional
  public QuestionListDto createAll(Long collectionId, List<QuestionDto> questionDtos) {
    String authorEmail = currentUser.requireEmail();
    Collection collection =
        collectionRepository
            .findByIdAndAuthorEmail(collectionId, authorEmail)
            .orElseThrow(() -> new CollectionNotFoundException(collectionId));

    Set<Question> questions = questionMapper.toQuestion(questionDtos);
    questions.forEach(question -> question.setCollection(collection));

    return questionMapper.toQuestionListDto(questionRepository.saveAll(questions));
  }

  @Override
  @Transactional(readOnly = true)
  public QuestionListDto findByTestId(UUID testId) {
    Test test =
        testRepository.findById(testId).orElseThrow(() -> new TestNotFoundException(testId));
    if (!test.getAuthor().getEmail().equals(currentUser.requireEmail())) {
      throw new TestNotFoundException(testId);
    }
    return questionMapper.toQuestionListDto(questionRepository.findByTestId(testId));
  }

  @Override
  @Transactional(readOnly = true)
  public QuestionListDto findByCollectionId(Long collectionId) {
    if (!collectionRepository.existsByIdAndAuthorEmail(collectionId, currentUser.requireEmail())) {
      throw new CollectionNotFoundException(collectionId);
    }
    return questionMapper.toQuestionListDto(questionRepository.findByCollectionId(collectionId));
  }
}
