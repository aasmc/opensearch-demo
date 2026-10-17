package ru.demo.logsender.generator;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.slf4j.event.Level;

/**
 * Документ лога в OpenSearch.
 */
public record LogEvent(
        @JsonProperty("process_id") String processId,
        @JsonProperty("event_timestamp") Instant eventTimestamp,
        @JsonProperty("service_name") String serviceName,
        @JsonProperty("log_level") Level logLevel,
        @JsonProperty("event_message") String eventMessage) {
}
