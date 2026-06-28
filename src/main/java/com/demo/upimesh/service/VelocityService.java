package com.demo.upimesh.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-sender rate limiting (Velocity check).
 * Prevents offline double-spending attacks where a malicious sender broadcasts
 * multiple conflicting packets across different offline nodes.
 */
@Service
public class VelocityService {

    private static final Logger log = LoggerFactory.getLogger(VelocityService.class);
    private static final String REDIS_KEY_PREFIX = "upimesh:velocity:";

    @Value("${upi.mesh.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${upi.mesh.velocity.max-packets:5}")
    private int maxPackets;

    @Value("${upi.mesh.velocity.window-seconds:300}")
    private long windowSeconds;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // In-memory fallback for dev mode
    private final Map<String, SenderWindow> localCounts = new ConcurrentHashMap<>();

    private static class SenderWindow {
        AtomicInteger count = new AtomicInteger(0);
        Instant firstSeen = Instant.now();
    }

    /**
     * Check if sender exceeded velocity limit.
     * @return true if velocity is allowed, false if limit exceeded.
     */
    public boolean allow(String senderVpa) {
        if (redisEnabled && redisTemplate != null) {
            try {
                String key = REDIS_KEY_PREFIX + senderVpa;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
                }
                boolean allowed = count != null && count <= maxPackets;
                if (!allowed) {
                    log.warn("Velocity limit exceeded for sender {} (count: {})", senderVpa, count);
                }
                return allowed;
            } catch (Exception e) {
                log.error("Redis unreachable during velocity check for sender {}: {}", senderVpa, e.getMessage());
                throw new RedisUnavailableException("Redis is unavailable for velocity checking", e);
            }
        }
        return allowInMemory(senderVpa);
    }

    private boolean allowInMemory(String senderVpa) {
        Instant now = Instant.now();
        SenderWindow win = localCounts.compute(senderVpa, (k, existing) -> {
            if (existing == null || existing.firstSeen.isBefore(now.minusSeconds(windowSeconds))) {
                SenderWindow nw = new SenderWindow();
                nw.count.set(1);
                return nw;
            }
            existing.count.incrementAndGet();
            return existing;
        });
        boolean allowed = win.count.get() <= maxPackets;
        if (!allowed) {
            log.warn("Velocity limit exceeded for sender {} in dev mode (count: {})", senderVpa, win.count.get());
        }
        return allowed;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        if (!redisEnabled) {
            Instant cutoff = Instant.now().minusSeconds(windowSeconds);
            localCounts.entrySet().removeIf(e -> e.getValue().firstSeen.isBefore(cutoff));
        }
    }

    public void clear() {
        localCounts.clear();
        if (redisEnabled && redisTemplate != null) {
            try {
                var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                log.warn("Failed to clear Redis velocity keys: {}", e.getMessage());
            }
        }
    }
}
