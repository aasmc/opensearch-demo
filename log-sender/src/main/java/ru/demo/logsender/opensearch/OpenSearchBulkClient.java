package ru.demo.logsender.opensearch;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.demo.logsender.config.LogSenderProperties;
import ru.demo.logsender.generator.LogEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отправляет документы в OpenSearch через Bulk API.
 */
@Component
public class OpenSearchBulkClient {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final String INDEX_ACTION = "{\"index\":{}}";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String index;

    public OpenSearchBulkClient(RestClient.Builder builder, JsonMapper jsonMapper, LogSenderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.opensearchUrl())
                .requestFactory(requestFactory)
                .build();
        this.jsonMapper = jsonMapper;
        this.index = properties.index();
    }

    public BulkResult send(List<LogEvent> events) {
        JsonNode response = restClient.post()
                .uri("/{index}/_bulk", index)
                .contentType(NDJSON)
                .body(toNdjson(events))
                .retrieve()
                .body(JsonNode.class);
        return new BulkResult(events.size(), extractFailures(response));
    }

    private String toNdjson(List<LogEvent> events) {
        StringBuilder body = new StringBuilder();
        for (LogEvent event : events) {
            body.append(INDEX_ACTION).append('\n')
                    .append(jsonMapper.writeValueAsString(event)).append('\n');
        }
        return body.toString();
    }

    private static List<String> extractFailures(JsonNode response) {
        if (response == null) {
            return List.of("empty bulk response");
        }
        if (!response.path("errors").asBoolean(false)) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode()) {
                failures.add(error.path("type").asString() + ": " + error.path("reason").asString());
            }
        }
        return failures;
    }
}
