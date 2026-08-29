plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "tech.studease"
version = "0.0.1-SNAPSHOT"
description = "studease"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    buildInfo()
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.liquibase.core)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.mapstruct)
    implementation(libs.bucket4j.core)
    implementation(libs.commons.csv)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    annotationProcessor(libs.mapstruct.processor)

    developmentOnly(libs.spring.boot.devtools)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Only the executable bootJar is published; the "-plain" library jar is not used.
tasks.named<Jar>("jar") {
    enabled = false
}

// Stable artifact name so the Dockerfile does not depend on the project version.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Lets SchemaBaselineExportTool be run on demand:
    //   ./gradlew test --tests "*SchemaBaselineExportTool*" -Dschema.export=true
    systemProperty("schema.export", System.getProperty("schema.export") ?: "false")

    // docker-java does not negotiate the Docker API version; it sends its own default, which is
    // below the 1.40 minimum enforced by Docker Engine 29.x. Without this the daemon answers /info
    // with a 400 and every Testcontainers test SKIPS silently (disabledWithoutDocker = true).
    // 1.41 is Docker 20.10+, so it is safe on older CI runners too. Ambient env wins if set.
    val dockerApiVersion = System.getenv("DOCKER_API_VERSION") ?: "1.41"
    environment("DOCKER_API_VERSION", dockerApiVersion)
    systemProperty("api.version", dockerApiVersion)
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.28.0")
    }
}
