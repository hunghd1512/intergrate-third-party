package com.pmkt.gateway.base;

public class RateLimitExceededException extends RuntimeException {

    private final String vendorName;
    private final long retryAfterMs;

    public RateLimitExceededException(String vendorName, long retryAfterMs) {
        super(String.format("Rate limit exceeded for %s. Retry after %d ms", vendorName, retryAfterMs));
        this.vendorName = vendorName;
        this.retryAfterMs = retryAfterMs;
    }

    public String getVendorName() {
        return vendorName;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
