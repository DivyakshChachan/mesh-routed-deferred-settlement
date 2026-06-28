package com.demo.upimesh.service;

/**
 * Thrown when Redis is unreachable during distributed idempotency deduplication.
 * Causing the ingestion pipeline to fail closed (503 Service Unavailable) rather than
 * failing open and risking duplicate settlement.
 */
public class RedisUnavailableException extends RuntimeException {
    public RedisUnavailableException(String message) {
        super(message);
    }
    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
