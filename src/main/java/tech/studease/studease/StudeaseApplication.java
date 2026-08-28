package tech.studease.studease;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StudeaseApplication {

  public static void main(String[] args) {
    SpringApplication.run(StudeaseApplication.class, args);
  }
}
