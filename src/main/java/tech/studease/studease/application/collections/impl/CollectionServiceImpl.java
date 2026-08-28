package tech.studease.studease.application.collections.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.studease.studease.api.collections.dto.CollectionDeleteRequestDto;
import tech.studease.studease.api.collections.dto.CollectionDto;
import tech.studease.studease.api.collections.dto.CollectionInfo;
import tech.studease.studease.api.collections.dto.CollectionListInfo;
import tech.studease.studease.application.collections.CollectionService;
import tech.studease.studease.application.collections.mapper.CollectionMapper;
import tech.studease.studease.common.security.CurrentUser;
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.collections.exception.CollectionAlreadyExistsException;
import tech.studease.studease.domain.collections.exception.CollectionInUseException;
import tech.studease.studease.domain.collections.exception.CollectionNotFoundException;
import tech.studease.studease.domain.samples.SampleRepository;
import tech.studease.studease.domain.users.User;

@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

  private final CollectionRepository collectionRepository;
  private final SampleRepository sampleRepository;
  private final CollectionMapper collectionMapper;
  private final CurrentUser currentUser;

  @Override
  @Transactional(readOnly = true)
  public CollectionListInfo findAll() {
    String authorEmail = currentUser.requireEmail();
    return collectionMapper.toCollectionListInfo(
        collectionRepository.findAllByAuthorEmail(authorEmail));
  }

  @Override
  @Transactional(readOnly = true)
  public CollectionInfo findById(Long collectionId) {
    String authorEmail = currentUser.requireEmail();
    Collection collection =
        collectionRepository
            .findByIdAndAuthorEmail(collectionId, authorEmail)
            .orElseThrow(() -> new CollectionNotFoundException(collectionId));
    return collectionMapper.toCollectionInfo(collection);
  }

  @Override
  @Transactional
  public CollectionInfo create(CollectionDto collectionDto) {
    User author = currentUser.require();
    if (collectionRepository.existsByNameAndAuthorEmail(
        collectionDto.getName(), author.getEmail())) {
      throw new CollectionAlreadyExistsException(collectionDto.getName());
    }
    Collection collection = collectionMapper.toCollection(collectionDto);
    collection.setAuthor(author);
    return collectionMapper.toCollectionInfo(collectionRepository.save(collection));
  }

  @Override
  @Transactional
  public void deleteById(Long collectionId) {
    String authorEmail = currentUser.requireEmail();
    if (!collectionRepository.existsByIdAndAuthorEmail(collectionId, authorEmail)) {
      throw new CollectionNotFoundException(collectionId);
    }
    if (sampleRepository.existsByCollectionIdAndTestIsNotNull(collectionId)) {
      throw new CollectionInUseException(collectionId);
    }
    collectionRepository.deleteById(collectionId);
  }

  @Override
  @Transactional
  public void deleteAllByIds(CollectionDeleteRequestDto request) {
    String authorEmail = currentUser.requireEmail();
    List<Collection> collections = collectionRepository.findAllById(request.getCollectionIds());
    if (collections.isEmpty()) {
      return;
    }
    collections.forEach(
        c -> {
          if (!c.getAuthor().getEmail().equals(authorEmail)) {
            throw new CollectionNotFoundException(c.getId());
          }
          if (sampleRepository.existsByCollectionIdAndTestIsNotNull(c.getId())) {
            throw new CollectionInUseException(c.getId());
          }
        });
    collectionRepository.deleteAll(collections);
  }
}
