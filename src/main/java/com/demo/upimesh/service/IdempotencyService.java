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

/**
 * Idempotency cache — prevents duplicate settlement of the same packet.
 *
 * Two modes:
 *   - Redis mode (production): uses SET NX EX for distributed atomic dedup.
 *     Multiple backend instances share a single Redis and are safe from
 *     concurrent duplicate storms.
 *   - In-memory mode (dev): uses ConcurrentHashMap.putIfAbsent, which is
 *     atomic within a single JVM — identical semantics, just not distributed.
 *
 * The contract:
 *   - claim(hash) returns true on first call, false on every call after that
 *     (within the TTL window)
 *   - the operation is atomic — even if 100 threads call claim(hash) at the
 *     same instant, exactly one returns true
 *   - Fail-closed: if Redis is unavailable, throws RedisUnavailableException
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String REDIS_KEY_PREFIX = "upimesh:idempotency:";

    @Value("${upi.mesh.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // In-memory fallback (dev mode)
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * Try to claim a hash. Returns true if this caller is the first; false if
     * someone else already claimed it (i.e. the packet is a duplicate).
     */
    public boolean claim(String packetHash) {
        if (redisEnabled && redisTemplate != null) {
            return claimViaRedis(packetHash);
        }
        return claimInMemory(packetHash);
    }

    private boolean claimViaRedis(String packetHash) {
        try {
            String key = REDIS_KEY_PREFIX + packetHash;
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent(key, "claimed", Duration.ofSeconds(ttlSeconds));
            boolean claimed = Boolean.TRUE.equals(result);
            if (!claimed) {
                log.debug("Redis SETNX returned false for key {}", key);
            }
            return claimed;
        } catch (Exception e) {
            log.error("Redis unreachable during idempotency claim for key {}: {}. Failing closed to prevent duplicate settlement.", packetHash, e.getMessage());
            throw new RedisUnavailableException("Redis is unavailable for idempotency deduplication", e);
        }
    }

    private boolean claimInMemory(String packetHash) {
        Instant now = Instant.now();
        Instant prev = seen.putIfAbsent(packetHash, now);
        return prev == null;
    }

    public int size() {
        if (redisEnabled && redisTemplate != null) {
            try {
                var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                return keys != null ? keys.size() : 0;
            } catch (Exception e) {
                log.warn("Failed to get Redis key count: {}", e.getMessage());
                return -1;
            }
        }
        return seen.size();
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        if (!redisEnabled) {
            Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
            seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        }
    }

    public void clear() {
        seen.clear();
        if (redisEnabled && redisTemplate != null) {
            try {
                var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                log.warn("Failed to clear Redis idempotency keys: {}", e.getMessage());
            }
        }
    }
}
