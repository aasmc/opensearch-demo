package ru.demo.logsender.generator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Документ лога в OpenSearch.
 */
public record LogEvent(
        @JsonProperty("process_id") String processId,
        @JsonProperty("event_timestamp") Instant eventTimestamp,
        @JsonProperty("service_name") String serviceName,
        @JsonProperty("event_message") String eventMessage) {
}
