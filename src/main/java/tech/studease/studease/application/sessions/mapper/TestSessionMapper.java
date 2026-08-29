package tech.studease.studease.application.sessions.mapper;

import java.util.List;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import tech.studease.studease.api.sessions.dto.ResponseEntryDto;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;
import tech.studease.studease.application.questions.mapper.QuestionMapper;
import tech.studease.studease.domain.answers.Answer;
import tech.studease.studease.domain.sessions.ResponseEntry;
import tech.studease.studease.domain.sessions.TestSession;

@Mapper(componentModel = "spring")
public abstract class TestSessionMapper {

  @Autowired protected QuestionMapper questionMapper;

  public TestSessionDto toTestSessionDto(
      TestSession testSession, boolean includeResponses, boolean isAdmin) {
    return TestSessionDto.builder()
        .sessionId(testSession.getId().toString())
        .studentGroup(testSession.getStudentGroup())
        .studentName(testSession.getStudentName())
        .startedAt(testSession.getStartedAt())
        .endsAt(testSession.getEndsAt())
        .finishedAt(testSession.getFinishedAt())
        .currentQuestionIndex(testSession.getCurrentQuestionIndex())
        .responses(
            includeResponses ? toResponseEntryDto(testSession.getResponses(), isAdmin) : null)
        .mark(isAdmin ? testSession.getMark() : null)
        .build();
  }

  public List<TestSessionDto> toTestSessionDto(
      List<TestSession> testSessions, boolean includeResponses, boolean isAdmin) {
    return testSessions.stream()
        .map(testSession -> toTestSessionDto(testSession, includeResponses, isAdmin))
        .toList();
  }

  public TestSessionListDto toTestSessionListDto(TestSession testSession) {
    return TestSessionListDto.builder()
        .sessions(List.of(toTestSessionDto(testSession, true, true)))
        .build();
  }

  public TestSessionListDto toTestSessionListDto(List<TestSession> testSession) {
    return TestSessionListDto.builder().sessions(toTestSessionDto(testSession, true, true)).build();
  }

  public List<ResponseEntryDto> toResponseEntryDto(List<ResponseEntry> responses, boolean isAdmin) {
    return responses.stream()
        .map(
            responseEntry ->
                ResponseEntryDto.builder()
                    .question(questionMapper.toQuestionDto(responseEntry.getQuestion(), isAdmin))
                    .answerIds(
                        responseEntry.getAnswers().stream()
                            .map(Answer::getId)
                            .collect(Collectors.toList()))
                    // Reads the attempt's own field. The old expression called getFirst() on the
                    // answers list without checking it was populated, so finishing a test with any
                    // essay left blank threw NoSuchElementException -> 500.
                    .answerContent(responseEntry.getEssayAnswer())
                    .build())
        .collect(Collectors.toList());
  }
}
