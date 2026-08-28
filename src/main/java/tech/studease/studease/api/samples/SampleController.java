package tech.studease.studease.api.samples;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.studease.studease.api.samples.dto.SampleListDto;
import tech.studease.studease.application.samples.SampleService;

@RestController
@RequestMapping("/api/v1/admin/samples")
@RequiredArgsConstructor
public class SampleController {

  private final SampleService sampleService;

  @GetMapping("/{testId}")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<SampleListDto> getSamplesByTestId(@PathVariable UUID testId) {
    return ResponseEntity.ok(sampleService.findByTestId(testId));
  }
}
