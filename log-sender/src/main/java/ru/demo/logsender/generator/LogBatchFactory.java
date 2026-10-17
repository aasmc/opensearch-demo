package ru.demo.logsender.generator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;
import ru.demo.logsender.config.LogSenderProperties;

/**
 * Собирает пачку логов одного процесса: все сообщения пачки имеют общий process_id и service_name.
 */
@Component
public class LogBatchFactory {

    public static final String IMPORTANT_PHRASE = "This is a very important message";

    private final RandomMessageGenerator messageGenerator;
    private final LogSenderProperties properties;
    private final Clock clock;
    private final RandomGenerator random;

    public LogBatchFactory(RandomMessageGenerator messageGenerator,
                           LogSenderProperties properties,
                           Clock clock,
                           RandomGenerator random) {
        this.messageGenerator = messageGenerator;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    /**
     * @param important если {@code true}, ровно одно случайное сообщение пачки содержит
     *                  {@link #IMPORTANT_PHRASE} в окружении другого текста
     */
    public List<LogEvent> createBatch(boolean important) {
        int batchSize = properties.batchSize();
        String processId = UUID.randomUUID().toString();
        String serviceName = properties.serviceNames().get(random.nextInt(properties.serviceNames().size()));
        int importantIndex = important ? random.nextInt(batchSize) : -1;
        Instant start = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        List<LogEvent> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            String message = i == importantIndex ? importantMessage() : messageGenerator.sentence();
            batch.add(new LogEvent(processId, start.plusMillis(i), serviceName, message));
        }
        return batch;
    }

    private String importantMessage() {
        return "%s. %s: %s.".formatted(messageGenerator.sentence(), IMPORTANT_PHRASE, messageGenerator.sentence());
    }
}
