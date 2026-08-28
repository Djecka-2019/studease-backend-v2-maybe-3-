package tech.studease.studease.application.samples.mapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;
import tech.studease.studease.api.samples.dto.SampleDto;
import tech.studease.studease.api.samples.dto.SampleListDto;
import tech.studease.studease.common.security.CurrentUser;
import tech.studease.studease.domain.collections.Collection;
import tech.studease.studease.domain.collections.CollectionRepository;
import tech.studease.studease.domain.collections.exception.CollectionNotFoundException;
import tech.studease.studease.domain.samples.Sample;

@Mapper(componentModel = "spring")
public abstract class SampleMapper {

  @Autowired private CollectionRepository collectionRepository;
  @Autowired private CurrentUser currentUser;

  public Set<Sample> toSample(List<SampleDto> sampleDtos) {
    if (sampleDtos == null || sampleDtos.isEmpty()) {
      return Set.of();
    }
    return sampleDtos.stream()
        .map(
            (sampleDto) -> {
              String authorEmail = currentUser.requireEmail();
              Collection collection =
                  collectionRepository
                      .findByIdAndAuthorEmail(sampleDto.getCollectionId(), authorEmail)
                      .orElseThrow(
                          () -> new CollectionNotFoundException(sampleDto.getCollectionId()));
              long matchingQuestionsCount =
                  collection.getQuestions().stream()
                      .filter(question -> question.getPoints().equals(sampleDto.getPoints()))
                      .count();
              if (sampleDto.getQuestionsCount() > matchingQuestionsCount) {
                throw new IllegalArgumentException(
                    "Questions count in sample exceeds the number of matching questions in the collection");
              }
              return Sample.builder()
                  .points(sampleDto.getPoints())
                  .questionsCount(sampleDto.getQuestionsCount())
                  .collection(collection)
                  .build();
            })
        .collect(Collectors.toSet());
  }

  public Collection toCollection(Long collectionId) {
    return Collection.builder().id(collectionId).build();
  }

  @Mapping(target = "collectionId", source = "collection.id")
  public abstract SampleDto toSampleDto(Sample sample);

  public abstract List<SampleDto> toSampleDto(List<Sample> sample);

  public SampleListDto toSampleListDto(List<Sample> sample) {
    return SampleListDto.builder().samples(toSampleDto(sample)).build();
  }
}
