# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies on their own cached layer.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar -x test -x spotlessCheck

# Split the fat jar into cacheable layers (deps change rarely, app code often).
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination extracted

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
