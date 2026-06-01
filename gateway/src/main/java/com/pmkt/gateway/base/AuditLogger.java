package com.pmkt.gateway.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Structured audit logger for all gateway requests.
 * Outputs JSON-formatted log lines with consistent fields for SIEM ingestion.
 */
public class AuditLogger {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String serviceName;

    public AuditLogger(String serviceName) {
        this.serviceName = serviceName;
    }

    public void logRequest(Object request, String traceId) {
        try {
            AuditEntry entry = new AuditEntry(
                traceId,
                "REQUEST",
                serviceName,
                Instant.now(),
                null,
                null,
                null,
                serialize(request)
            );
            auditLog.info(mapper.writeValueAsString(entry));
        } catch (Exception e) {
            auditLog.error("Failed to serialize audit request", e);
        }
    }

    public void logSuccess(Object response, String traceId, long durationMs) {
        try {
            AuditEntry entry = new AuditEntry(
                traceId,
                "SUCCESS",
                serviceName,
                Instant.now(),
                durationMs,
                null,
                null,
                serialize(response)
            );
            auditLog.info(mapper.writeValueAsString(entry));
        } catch (Exception e) {
            auditLog.error("Failed to serialize audit success", e);
        }
    }

    public void logFailure(Throwable error, String traceId, long durationMs) {
        try {
            AuditEntry entry = new AuditEntry(
                traceId,
                "FAILURE",
                serviceName,
                Instant.now(),
                durationMs,
                error.getClass().getSimpleName(),
                error.getMessage(),
                null
            );
            auditLog.warn(mapper.writeValueAsString(entry));
        } catch (Exception e) {
            auditLog.error("Failed to serialize audit failure", e);
        }
    }

    public String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String serialize(Object obj) {
        if (obj == null) return null;
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    public record AuditEntry(
        String traceId,
        String eventType,
        String serviceName,
        Instant timestamp,
        Long durationMs,
        String errorType,
        String errorMessage,
        String payload
    ) {}
}
