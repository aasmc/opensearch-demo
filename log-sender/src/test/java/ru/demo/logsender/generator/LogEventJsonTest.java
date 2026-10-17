package ru.demo.logsender.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@JsonTest
class LogEventJsonTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void serializesFieldsInSnakeCaseWithIsoTimestamp() {
        LogEvent event = new LogEvent("0b0f7c7e-3e44-4d43-9b0b-6f3f1d1a2b3c",
                Instant.parse("2026-10-17T10:15:30.123Z"), "order-service", "Order was created");

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(event));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "process_id", "event_timestamp", "service_name", "event_message");
        assertThat(json.path("process_id").asString()).isEqualTo("0b0f7c7e-3e44-4d43-9b0b-6f3f1d1a2b3c");
        assertThat(json.path("event_timestamp").asString()).isEqualTo("2026-10-17T10:15:30.123Z");
        assertThat(json.path("service_name").asString()).isEqualTo("order-service");
        assertThat(json.path("event_message").asString()).isEqualTo("Order was created");
    }
}
