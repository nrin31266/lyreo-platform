package com.lyreo.platform.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Single-node API rate limiter for the MVP.
 *
 * <p>Bucket4j owns token-bucket semantics while Caffeine bounds/evicts the
 * per-client bucket cache. This intentionally avoids Redis in the initial
 * architecture. When Lyreo runs multiple API instances and needs a globally
 * coordinated user quota, replace this adapter behind the same boundary with
 * a PostgreSQL/distributed implementation through an explicit architecture
 * decision.</p>
 */
public final class LocalRateLimitService {
    private static final long DEFAULT_MAX_KEYS = 100_000;

    private final Cache<String, Bucket> buckets;
    private final long tokens;
    private final Duration window;

    public LocalRateLimitService(long tokens, Duration window) {
        this(tokens, window, DEFAULT_MAX_KEYS);
    }

    public LocalRateLimitService(long tokens, Duration window, long maxKeys) {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maxKeys <= 0) throw new IllegalArgumentException("maxKeys must be > 0");

        this.tokens = tokens;
        this.window = window;
        this.buckets = Caffeine.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterAccess(window.multipliedBy(2))
            .build();
    }

    public boolean tryConsume(String key) {
        return tryConsumeProbe(key).allowed();
    }

    public RateLimitResult tryConsumeProbe(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("rate-limit key is required");
        ConsumptionProbe probe = buckets.get(key, ignored -> newBucket()).tryConsumeAndReturnRemaining(1);
        return new RateLimitResult(
            probe.isConsumed(),
            probe.getRemainingTokens(),
            probe.isConsumed() ? 0 : probe.getNanosToWaitForRefill()
        );
    }

    private Bucket newBucket() {
        var refill = Refill.intervally(tokens, window);
        var bandwidth = Bandwidth.classic(tokens, refill);
        return Bucket.builder().addLimit(bandwidth).build();
    }

    public record RateLimitResult(
        boolean allowed,
        long remainingTokens,
        long nanosToWaitForRefill
    ) {
        public long secondsToWaitForRefill() {
            if (nanosToWaitForRefill <= 0) return 0;
            long seconds = TimeUnit.NANOSECONDS.toSeconds(nanosToWaitForRefill);
            return Math.max(1, seconds);
        }
    }
}
