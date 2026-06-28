package com.demo.upimesh;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.RedisUnavailableException;
import com.demo.upimesh.service.VelocityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "upi.mesh.velocity.max-packets=3",
    "upi.mesh.security.enforce-api-key=true",
    "upi.mesh.security.api-keys=test-secret-key"
})
class SecurityAndVelocityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private VelocityService velocity;

    @BeforeEach
    void setup() {
        idempotency.clear();
        velocity.clear();
    }

    @Test
    void missingApiKeyReturns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/bridge/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packetId\":\"123\",\"ciphertext\":\"abc\",\"ttl\":5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validApiKeyAllowsRequest() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "1234", 5);

        mockMvc.perform(post("/api/bridge/ingest")
                .header("X-Bridge-API-Key", "test-secret-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packetId\":\"" + packet.getPacketId() + "\",\"ciphertext\":\"" + packet.getCiphertext() + "\",\"ttl\":5}"))
                .andExpect(status().isOk());
    }

    @Test
    void velocityThresholdExceededReturnsInvalid() throws Exception {
        // Max packets set to 3 via TestPropertySource
        for (int i = 1; i <= 3; i++) {
            MeshPacket packet = demoService.createPacket(
                    "alice@demo", "bob@demo", new BigDecimal("1.00"), "1234", 5);
            BridgeIngestionService.IngestResult r = bridge.ingest(packet, "bridge-1", 1);
            assertEquals("SETTLED", r.outcome());
        }

        // 4th packet should exceed velocity threshold
        MeshPacket excessPacket = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("1.00"), "1234", 5);
        BridgeIngestionService.IngestResult r = bridge.ingest(excessPacket, "bridge-1", 1);
        assertEquals("INVALID", r.outcome());
        assertEquals("VELOCITY_EXCEEDED", r.reason());
    }
}
