package com.pmkt.gateway.base;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Duration;

/**
 * Simple per-gateway instance rate limiter using token bucket.
 * For distributed deployments, replace with Redis-backed Bucket4j.
 */
public class GatewayRateLimiter {

    private final Bucket bucket;

    public GatewayRateLimiter(int permitsPerSecond) {
        Bandwidth limit = Bandwidth.classic(
            permitsPerSecond,
            Refill.intervally(permitsPerSecond, Duration.ofSeconds(1))
        );
        this.bucket = Bucket.builder().addLimit(limit).build();
    }

    public void acquire() {
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new RateLimitExceededException(
                "unknown",
                probe.getNanosToWaitForRefill()
            );
        }
    }

    public long availableTokens() {
        return bucket.getAvailableTokens();
    }
}
