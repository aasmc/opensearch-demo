package ru.demo.logsender.scheduling;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.demo.logsender.config.LogSenderProperties;
import ru.demo.logsender.generator.BatchType;
import ru.demo.logsender.generator.LogBatchFactory;
import ru.demo.logsender.generator.LogEvent;
import ru.demo.logsender.opensearch.BulkResult;
import ru.demo.logsender.opensearch.OpenSearchBulkClient;

/**
 * Раз в {@code log-sender.fixed-rate-ms} отправляет одну пачку логов.
 * Каждая {@code important-every-nth-batch}-я пачка содержит важную фразу, а каждая
 * {@code problem-every-nth-important-batch}-я из таких пачек — ещё и сообщение уровня WARN или ERROR.
 */
@Component
public class LogSendingScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogSendingScheduler.class);

    private final LogBatchFactory batchFactory;
    private final OpenSearchBulkClient bulkClient;
    private final LogSenderProperties properties;
    private final AtomicLong batchCounter = new AtomicLong();
    private final AtomicLong importantBatchCounter = new AtomicLong();

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
        BatchType type = batchType(batchNumber);
        List<LogEvent> batch = batchFactory.createBatch(type);
        String processId = batch.getFirst().processId();
        try {
            BulkResult result = bulkClient.send(batch);
            if (result.hasFailures()) {
                log.warn("Batch #{} process_id={} indexed with {} failure(s) of {}: {}",
                        batchNumber, processId, result.failures().size(), result.total(), result.failures());
            } else if (type != BatchType.REGULAR) {
                log.info("Batch #{} process_id={} sent ({} docs) [{}]", batchNumber, processId, result.total(), type);
            } else {
                log.info("Batch #{} process_id={} sent ({} docs)", batchNumber, processId, result.total());
            }
        } catch (RuntimeException e) {
            log.error("Batch #{} process_id={} failed: {}", batchNumber, processId, e.getMessage());
        }
    }

    private BatchType batchType(long batchNumber) {
        if (batchNumber % properties.importantEveryNthBatch() != 0) {
            return BatchType.REGULAR;
        }
        return importantBatchCounter.incrementAndGet() % properties.problemEveryNthImportantBatch() == 0
                ? BatchType.IMPORTANT_WITH_PROBLEM
                : BatchType.IMPORTANT;
    }
}
