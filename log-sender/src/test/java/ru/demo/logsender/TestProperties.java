package ru.demo.logsender;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import ru.demo.logsender.config.LogSenderProperties;

public final class TestProperties {

    private TestProperties() {
    }

    public static LogSenderProperties properties() {
        return properties(URI.create("http://localhost:9200"), "app-logs");
    }

    public static LogSenderProperties properties(URI opensearchUrl, String index) {
        return new LogSenderProperties(opensearchUrl, index, 10, 10, 3,
                List.of("order-service", "payment-service"), Duration.ofSeconds(1), Duration.ofSeconds(5));
    }
}
