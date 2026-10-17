package ru.demo.logsender.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.demo.logsender.TestProperties;
import ru.demo.logsender.generator.LogBatchFactory;
import ru.demo.logsender.generator.LogEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проверяет отправку пачки в настоящий OpenSearch с шаблоном индекса из {@code opensearch/init}.
 */
@Testcontainers
@SpringBootTest(properties = {"log-sender.scheduling.enabled=false", "log-sender.index=app-logs-it"})
class OpenSearchBulkClientIntegrationTest {

    private static final Path INDEX_TEMPLATE = Path.of("../opensearch/init/index-template.json");

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.8.0")
            .withEnv("discovery.type", "single-node")
            .withEnv("plugins.security.disabled", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow")
                    .forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static RestClient opensearch;

    @Autowired
    private LogBatchFactory batchFactory;

    @Autowired
    private OpenSearchBulkClient bulkClient;

    @DynamicPropertySource
    static void opensearchProperties(DynamicPropertyRegistry registry) {
        registry.add("log-sender.opensearch-url", OpenSearchBulkClientIntegrationTest::opensearchUrl);
    }

    @BeforeAll
    static void applyIndexTemplate() throws Exception {
        opensearch = RestClient.create(opensearchUrl());
        opensearch.put()
                .uri("/_index_template/app-logs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Files.readString(INDEX_TEMPLATE))
                .retrieve()
                .toBodilessEntity();
    }

    @Test
    void indexesBatchWithStrictMappingAndFindsImportantPhrase() {
        List<LogEvent> batch = batchFactory.createBatch(true);

        BulkResult result = bulkClient.send(batch);
        refresh();

        assertThat(result.hasFailures()).as("failures: %s", result.failures()).isFalse();
        assertThat(count("{\"query\":{\"term\":{\"process_id\":\"" + batch.getFirst().processId() + "\"}}}"))
                .isEqualTo(10);
        assertThat(count("{\"query\":{\"match_phrase\":{\"event_message\":\"" + LogBatchFactory.IMPORTANT_PHRASE + "\"}}}"))
                .isEqualTo(1);
    }

    @Test
    void reportsPerDocumentFailures() {
        // Индекс вне шаблона app-logs*: без полей event_* при dynamic=strict документ будет отклонён
        LogEvent invalid = new LogEvent("process-1", null, "order-service", "Order was created");
        opensearch.put()
                .uri("/strict-test-index")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"process_id\":{\"type\":\"keyword\"}}}}")
                .retrieve()
                .toBodilessEntity();

        OpenSearchBulkClient client = new OpenSearchBulkClient(RestClient.builder(), JsonMapper.builder().build(),
                TestProperties.properties(URI.create(opensearchUrl()), "strict-test-index"));

        BulkResult result = client.send(List.of(invalid));

        assertThat(result.failures()).singleElement().asString().startsWith("strict_dynamic_mapping_exception");
    }

    private static String opensearchUrl() {
        return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    }

    private static void refresh() {
        opensearch.post().uri("/app-logs-it/_refresh").retrieve().toBodilessEntity();
    }

    private static long count(String query) {
        JsonNode response = opensearch.post()
                .uri("/app-logs-it/_count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(query)
                .retrieve()
                .body(JsonNode.class);
        return response.path("count").asLong();
    }
}
