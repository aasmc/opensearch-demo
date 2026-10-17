package ru.demo.logsender.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;
import org.springframework.web.client.ResourceAccessException;
import ru.demo.logsender.TestProperties;
import ru.demo.logsender.generator.LogBatchFactory;
import ru.demo.logsender.generator.LogEvent;
import ru.demo.logsender.generator.RandomMessageGenerator;
import ru.demo.logsender.opensearch.BulkResult;
import ru.demo.logsender.opensearch.OpenSearchBulkClient;

class LogSendingSchedulerTest {

    private final Random random = new Random(3);
    private final LogBatchFactory batchFactory = new LogBatchFactory(
            new RandomMessageGenerator(random), TestProperties.properties(), Clock.systemUTC(), random);
    private final OpenSearchBulkClient bulkClient = mock(OpenSearchBulkClient.class);
    private final LogSendingScheduler scheduler =
            new LogSendingScheduler(batchFactory, bulkClient, TestProperties.properties());

    @Test
    @SuppressWarnings("unchecked")
    void everyTenthBatchIsImportantAndEveryThirdImportantBatchHasWarnOrError() {
        when(bulkClient.send(anyList())).thenReturn(new BulkResult(10, List.of()));

        IntStream.range(0, 90).forEach(i -> scheduler.sendBatch());

        ArgumentCaptor<List<LogEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkClient, times(90)).send(captor.capture());
        List<List<LogEvent>> batches = captor.getAllValues();
        for (int i = 0; i < batches.size(); i++) {
            int batchNumber = i + 1;
            List<LogEvent> batch = batches.get(i);
            long phraseCount = batch.stream()
                    .filter(event -> event.eventMessage().contains(LogBatchFactory.IMPORTANT_PHRASE))
                    .count();
            long problemCount = batch.stream()
                    .filter(event -> event.logLevel() == Level.WARN || event.logLevel() == Level.ERROR)
                    .count();

            assertThat(phraseCount).as("phrases in batch #%d", batchNumber).isEqualTo(batchNumber % 10 == 0 ? 1 : 0);
            if (batchNumber % 30 == 0) {
                assertThat(problemCount).as("WARN/ERROR in batch #%d", batchNumber).isEqualTo(1);
            } else if (batchNumber % 10 == 0) {
                assertThat(problemCount).as("WARN/ERROR in batch #%d", batchNumber).isZero();
            }
        }
    }

    @Test
    void sendingFailureDoesNotPropagate() {
        when(bulkClient.send(anyList())).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatNoException().isThrownBy(scheduler::sendBatch);
    }
}
