package com.pmkt.gateway.base;

/**
 * License validator enforcing per-service quota and expiry.
 * Thread-safe counter backed by an in-memory map (swap for Redis in distributed env).
 */
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LicenseValidator {

    private final String serviceName;
    private final String licenseKey;
    private final LocalDate expiresAt;
    private final long quotaLimit;
    private final boolean unlimited;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public LicenseValidator(String serviceName, String licenseKey,
                            LocalDate expiresAt, long quotaLimit) {
        this.serviceName = serviceName;
        this.licenseKey = licenseKey;
        this.expiresAt = expiresAt;
        this.quotaLimit = quotaLimit;
        this.unlimited = quotaLimit <= 0;
    }

    public void validate() {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw new LicenseExpiredException(serviceName);
        }

        if (LocalDate.now().isAfter(expiresAt)) {
            throw new LicenseExpiredException(serviceName);
        }

        if (!unlimited) {
            long current = counters.computeIfAbsent(licenseKey, k -> new AtomicLong(0))
                                   .incrementAndGet();
            if (current > quotaLimit) {
                counters.get(licenseKey).decrementAndGet();
                throw new QuotaExceededException(serviceName, current, quotaLimit);
            }
        }
    }

    public long getCurrentUsage() {
        AtomicLong counter = counters.get(licenseKey);
        return counter == null ? 0 : counter.get();
    }
}
