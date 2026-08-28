package tech.studease.studease.common.util;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tech.studease.studease.api.sessions.dto.TestSessionDto;
import tech.studease.studease.api.sessions.dto.TestSessionListDto;

public final class CsvGeneratorUtils {

  private static final CSVFormat FORMAT =
      CSVFormat.DEFAULT
          .builder()
          .setHeader("Credentials", "Mark", "StartedAt", "FinishedAt", "Time")
          .build();

  private CsvGeneratorUtils() {}

  public static String generateCsv(TestSessionListDto testSessionListDto) {
    StringWriter out = new StringWriter();
    try (CSVPrinter printer = new CSVPrinter(out, FORMAT)) {
      for (TestSessionDto session : testSessionListDto.getSessions()) {
        printer.printRecord(
            neutralize(session.getStudentGroup() + " " + session.getStudentName()),
            session.getMark(),
            session.getStartedAt(),
            session.getFinishedAt(),
            formatDuration(session.getStartedAt(), session.getFinishedAt()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to generate sessions CSV", e);
    }
    return out.toString();
  }

  /** Prevents spreadsheet formula injection by prefixing risky leading characters with a quote. */
  private static String neutralize(String value) {
    String text = Objects.toString(value, "");
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      return "'" + text;
    }
    return text;
  }

  private static String formatDuration(LocalDateTime start, LocalDateTime end) {
    if (start == null || end == null) {
      return "";
    }
    Duration duration = Duration.between(start, end);
    long hours = duration.toHours();
    long minutes = duration.toMinutes() % 60;
    long seconds = duration.getSeconds() % 60;
    return String.format("%02dh %02dm %02ds", hours, minutes, seconds);
  }
}
