package ru.demo.logsender.scheduling;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.demo.logsender.config.LogSenderProperties;
import ru.demo.logsender.generator.LogBatchFactory;
import ru.demo.logsender.generator.LogEvent;
import ru.demo.logsender.opensearch.BulkResult;
import ru.demo.logsender.opensearch.OpenSearchBulkClient;

/**
 * Раз в {@code log-sender.fixed-rate-ms} отправляет одну пачку логов.
 * Каждая {@code important-every-nth-batch}-я пачка содержит важную фразу.
 */
@Component
public class LogSendingScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogSendingScheduler.class);

    private final LogBatchFactory batchFactory;
    private final OpenSearchBulkClient bulkClient;
    private final LogSenderProperties properties;
    private final AtomicLong batchCounter = new AtomicLong();

    public LogSendingScheduler(LogBatchFactory batchFactory,
                               OpenSearchBulkClient bulkClient,
                               LogSenderProperties properties) {
        this.batchFactory = batchFactory;
        this.bulkClient = bulkClient;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${log-sender.fixed-rate-ms:1000}")
    public void sendBatch() {
        long batchNumber = batchCounter.incrementAndGet();
        boolean important = batchNumber % properties.importantEveryNthBatch() == 0;
        List<LogEvent> batch = batchFactory.createBatch(important);
        String processId = batch.getFirst().processId();
        try {
            BulkResult result = bulkClient.send(batch);
            if (result.hasFailures()) {
                log.warn("Batch #{} process_id={} indexed with {} failure(s) of {}: {}",
                        batchNumber, processId, result.failures().size(), result.total(), result.failures());
            } else if (important) {
                log.info("Batch #{} process_id={} sent ({} docs) [IMPORTANT]", batchNumber, processId, result.total());
            } else {
                log.info("Batch #{} process_id={} sent ({} docs)", batchNumber, processId, result.total());
            }
        } catch (RuntimeException e) {
            log.error("Batch #{} process_id={} failed: {}", batchNumber, processId, e.getMessage());
        }
    }
}
