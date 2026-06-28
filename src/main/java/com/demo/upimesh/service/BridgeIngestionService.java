package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache (Redis / map).
 *   3. Decrypt the ciphertext with the server's private key.
 *   4. Check freshness — reject if signedAt is too old (replay protection).
 *   5. Check velocity — prevent offline double-spending rate spikes.
 *   6. Hand off to SettlementService for actual debit/credit.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private IdempotencyService idempotency;
    @Autowired private VelocityService velocity;
    @Autowired private SettlementService settlement;
    @Autowired(required = false) private MeterRegistry meterRegistry;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String packetHash = "?";
        try {
            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ---- Idempotency gate ----
            boolean claimed;
            try {
                claimed = idempotency.claim(packetHash);
            } catch (RedisUnavailableException rue) {
                log.error("Failing ingestion closed for packet {} due to Redis unavailability", packetHash);
                return recordMetric(IngestResult.serviceUnavailable(packetHash, "redis_unavailable"));
            }

            if (!claimed) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return recordMetric(IngestResult.duplicate(packetHash));
            }

            // ---- Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, 12) + "...", e.getMessage());
                return recordMetric(IngestResult.invalid(packetHash, "decryption_failed"));
            }

            // ---- Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...", ageSeconds);
                return recordMetric(IngestResult.invalid(packetHash, "stale_packet"));
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                return recordMetric(IngestResult.invalid(packetHash, "future_dated"));
            }

            // ---- Velocity check (double-spend spike protection) ----
            try {
                if (!velocity.allow(instruction.getSenderVpa())) {
                    log.warn("Sender {} exceeded offline velocity thresholds on packet {}",
                            instruction.getSenderVpa(), packetHash.substring(0, 12) + "...");
                    return recordMetric(IngestResult.invalid(packetHash, "VELOCITY_EXCEEDED"));
                }
            } catch (RedisUnavailableException rue) {
                log.error("Failing ingestion closed for packet {} due to Redis unavailability in velocity check", packetHash);
                return recordMetric(IngestResult.serviceUnavailable(packetHash, "redis_unavailable"));
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return recordMetric(IngestResult.settled(packetHash, tx));

        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return recordMetric(IngestResult.invalid(packetHash, "internal_error: " + e.getMessage()));
        }
    }

    private IngestResult recordMetric(IngestResult result) {
        if (meterRegistry != null) {
            meterRegistry.counter("bridge.ingest", "outcome", result.outcome()).increment();
        }
        return result;
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
        public static IngestResult serviceUnavailable(String hash, String reason) {
            return new IngestResult("SERVICE_UNAVAILABLE", hash, reason, null);
        }
    }
}
