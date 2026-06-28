package com.demo.upimesh;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.RedisUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "upi.mesh.redis.enabled=true"
})
class RedisFailureModeTest {

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private BridgeIngestionService bridgeIngestionService;

    @Autowired
    private DemoService demoService;

    @Test
    void redisUnavailabilityFailsClosed() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenThrow(new RuntimeException("Redis connection timeout"));

        assertThrows(RedisUnavailableException.class, () -> idempotencyService.claim("test-hash"));

        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "1234", 5);

        BridgeIngestionService.IngestResult result = bridgeIngestionService.ingest(packet, "bridge-node-1", 1);
        assertEquals("SERVICE_UNAVAILABLE", result.outcome());
        assertEquals("redis_unavailable", result.reason());
    }
}
