package tech.studease.studease.application.samples.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.api.samples.dto.SampleListDto;
import tech.studease.studease.application.samples.SampleService;
import tech.studease.studease.application.samples.mapper.SampleMapper;
import tech.studease.studease.common.security.CurrentUser;
import tech.studease.studease.domain.samples.SampleRepository;
import tech.studease.studease.domain.tests.Test;
import tech.studease.studease.domain.tests.TestRepository;
import tech.studease.studease.domain.tests.exception.TestNotFoundException;

@Service
@RequiredArgsConstructor
public class SampleServiceImpl implements SampleService {

  private final SampleRepository sampleRepository;
  private final TestRepository testRepository;
  private final SampleMapper sampleMapper;
  private final CurrentUser currentUser;

  @Override
  @Transactional(readOnly = true)
  public SampleListDto findByTestId(UUID testId) {
    Test test =
        testRepository.findById(testId).orElseThrow(() -> new TestNotFoundException(testId));
    if (!test.getAuthor().getEmail().equals(currentUser.requireEmail())) {
      throw new TestNotFoundException(testId);
    }
    return sampleMapper.toSampleListDto(sampleRepository.findByTestId(testId));
  }
}
