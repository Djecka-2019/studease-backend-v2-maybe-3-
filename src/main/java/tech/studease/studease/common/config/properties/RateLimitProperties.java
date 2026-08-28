package tech.studease.studease.common.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10") @Positive int authPerMinute,
    @DefaultValue("60") @Positive int studentPerMinute,
    @DefaultValue("30") @Positive int aiGeneratePerHour) {}
