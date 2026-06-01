package com.pmkt.gateway.kyso;

import com.pmkt.gateway.config.GatewayConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Configuration properties for the Viettel CA KySo gateway.
 * Loaded from application.yml under prefix "gateway.ky-so".
 */
public record KySoConfig(
    String endpoint,
    String certificatePath,
    String certificatePassword,
    String licenseKey,
    int timeoutSeconds,
    int rateLimitPerSecond,
    LocalDateTime licenseExpiresAt,
    long quotaLimit
) implements GatewayConfig {

    private static final String SERVICE_NAME = "viettel-kyso-ca";

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(30)           // Aggressive: 30% fail rate triggers OPEN
            .waitDurationInOpenState(Duration.ofSeconds(60))  // Wait 60s before half-open
            .slidingWindowSize(10)             // Count last 10 calls
            .minimumNumberOfCalls(5)           // Need 5 calls before evaluating
            .slowCallRateThreshold(50)         // 50% slow calls → OPEN
            .slowCallDurationThreshold(Duration.ofSeconds(timeoutSeconds))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
    }

    // Static factory for unit testing
    public static KySoConfig testConfig() {
        return new KySoConfig(
            "https://ca-test.viettel.vn/api/sign",
            "/certs/kyso.p12",
            "changeit",
            "test-license-key",
            30,
            10,
            java.time.LocalDateTime.now().plusYears(1),
            -1
        );
    }
}
