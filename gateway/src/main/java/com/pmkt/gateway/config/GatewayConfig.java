package com.pmkt.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;

/**
 * Unified configuration contract for all external gateway integrations.
 * Every subclass Config class must provide values for these fields.
 */
public record GatewayConfig(
    String serviceName,
    String endpoint,
    String licenseKey,
    int timeoutSeconds,
    CircuitBreakerConfig circuitBreakerConfig,
    int rateLimitPerSecond
) {}
