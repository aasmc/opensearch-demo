package ru.demo.logsender.generator;

import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.INFO;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;
import ru.demo.logsender.config.LogSenderProperties;

/**
 * Собирает пачку логов одного процесса: все сообщения пачки имеют общий process_id и service_name.
 */
@Component
public class LogBatchFactory {

    public static final String IMPORTANT_PHRASE = "This is a very important message";

    /** Уровни с весами (повторами) для обычных пачек. */
    private static final List<Level> REGULAR_LEVELS =
            List.of(TRACE, DEBUG, DEBUG, INFO, INFO, INFO, INFO, INFO, WARN, ERROR);
    /** Уровни для важных пачек, которые не должны вызывать алерт: без WARN и ERROR. */
    private static final List<Level> QUIET_LEVELS = List.of(TRACE, DEBUG, DEBUG, INFO, INFO, INFO, INFO, INFO);
    private static final List<Level> PROBLEM_LEVELS = List.of(WARN, ERROR);

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
     * В важной пачке ровно одно случайное сообщение уровня INFO содержит {@link #IMPORTANT_PHRASE} в окружении
     * другого текста. В {@link BatchType#IMPORTANT_WITH_PROBLEM} ровно одно другое сообщение имеет уровень WARN
     * или ERROR.
     */
    public List<LogEvent> createBatch(BatchType type) {
        int batchSize = properties.batchSize();
        String processId = UUID.randomUUID().toString();
        String serviceName = pick(properties.serviceNames());
        int importantIndex = type == BatchType.REGULAR ? -1 : random.nextInt(batchSize);
        int problemIndex = type == BatchType.IMPORTANT_WITH_PROBLEM
                ? (importantIndex + 1 + random.nextInt(batchSize - 1)) % batchSize
                : -1;
        List<Level> levels = type == BatchType.REGULAR ? REGULAR_LEVELS : QUIET_LEVELS;
        Instant start = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        List<LogEvent> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            Level level;
            String message;
            if (i == importantIndex) {
                level = INFO;
                message = importantMessage();
            } else {
                level = i == problemIndex ? pick(PROBLEM_LEVELS) : pick(levels);
                message = messageGenerator.sentence();
            }
            batch.add(new LogEvent(processId, start.plusMillis(i), serviceName, level, message));
        }
        return batch;
    }

    private String importantMessage() {
        return "%s. %s: %s.".formatted(messageGenerator.sentence(), IMPORTANT_PHRASE, messageGenerator.sentence());
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
