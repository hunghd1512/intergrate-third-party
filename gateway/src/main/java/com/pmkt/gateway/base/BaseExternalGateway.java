package com.pmkt.gateway.base;

import com.pmkt.gateway.config.GatewayConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base for all third-party gateway integrations.
 *
 * <p>Implements the fixed template method flow:
 * <pre>
 * validateLicense() → acquireRateLimit() → logRequest() → callVendor() → logResult()
 *                                              ↑
 *                                    circuitBreaker wraps callVendor
 * </pre>
 *
 * <p>Each subclass only needs to:
 * <ul>
 *   <li>Extend this class with concrete {@code Req} / {@code Res} types</li>
 *   <li>Implement {@link #vendorName()} — human-readable vendor identifier</li>
 *   <li>Implement {@link #callVendor(Object)} — vendor-specific HTTP/SDK call</li>
 * </ul>
 *
 * <p>Cross-cutting concerns NOT shared across subclasses:
 * <ul>
 *   <li>Authentication mechanism (cert, API key, OAuth token)</li>
 *   <li>Retry strategy (some ops like ký số must never retry)</li>
 *   <li>Circuit breaker threshold (business-critical ops need tighter limits)</li>
 *   <li>Rate limit quota (each vendor has different caps)</li>
 * </ul>
 *
 * @param <Req> the domain request type
 * @param <Res> the domain response type
 */
public abstract class BaseExternalGateway<Req, Res> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final CircuitBreaker circuitBreaker;
    private final GatewayRateLimiter rateLimiter;
    private final AuditLogger auditLogger;
    private final LicenseValidator licenseValidator;

    protected BaseExternalGateway(GatewayConfig config) {
        this.circuitBreaker    = buildCircuitBreaker(config);
        this.rateLimiter      = new GatewayRateLimiter(config.rateLimitPerSecond());
        this.auditLogger      = new AuditLogger(config.serviceName());
        this.licenseValidator = buildLicenseValidator(config);
    }

    /**
     * Template method — executes the fixed gateway flow.
     * Subclasses must NOT override this method.
     */
    public final Res execute(Req request) {
        String traceId = auditLogger.generateTraceId();
        long startMs = System.currentTimeMillis();

        licenseValidator.validate();
        rateLimiter.acquire();
        auditLogger.logRequest(request, traceId);

        try {
            Res result = circuitBreaker.executeSupplier(() -> callVendor(request));
            auditLogger.logSuccess(result, traceId, System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMs;
            auditLogger.logFailure(e, traceId, duration);

            // Unwrap resilience4j CallNotPermittedException
            Throwable cause = e instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
                ? new ExternalGatewayException(vendorName(), "Circuit breaker is OPEN — vendor unavailable")
                : e;

            throw cause instanceof ExternalGatewayException
                ? (ExternalGatewayException) cause
                : new ExternalGatewayException(vendorName(), cause);
        }
    }

    /**
     * Vendor-specific call — implemented by each subclass adapter.
     */
    protected abstract Res callVendor(Req request);

    /** Human-readable vendor name for logging and error messages. */
    protected abstract String vendorName();

    /** Subclass-specific auth mechanism injected at construction. */
    protected abstract void applyAuth(io.github.resilience4j.httpclient.Resilience4jConfig.HttpRequest.Builder builder);

    // ─── Protected builders (called by subclasses in their constructors) ─────

    protected CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    protected AuditLogger auditLogger() {
        return auditLogger;
    }

    protected LicenseValidator licenseValidator() {
        return licenseValidator;
    }

    protected GatewayRateLimiter rateLimiter() {
        return rateLimiter;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private CircuitBreaker buildCircuitBreaker(GatewayConfig config) {
        CircuitBreakerConfig cbConfig = config.circuitBreakerConfig() != null
            ? config.circuitBreakerConfig()
            : CircuitBreakerConfig.ofDefaults();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig);
        return registry.circuitBreaker(vendorName());
    }

    private LicenseValidator buildLicenseValidator(GatewayConfig config) {
        return new LicenseValidator(
            config.serviceName(),
            config.licenseKey(),
            java.time.LocalDate.now().plusYears(1),  // overridden per-service
            -1  // unlimited by default
        );
    }
}
