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
    void everyTenthBatchContainsExactlyOneImportantMessage() {
        when(bulkClient.send(anyList())).thenReturn(new BulkResult(10, List.of()));

        IntStream.range(0, 30).forEach(i -> scheduler.sendBatch());

        ArgumentCaptor<List<LogEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkClient, times(30)).send(captor.capture());
        List<Long> importantCounts = captor.getAllValues().stream()
                .map(batch -> batch.stream()
                        .filter(event -> event.eventMessage().contains(LogBatchFactory.IMPORTANT_PHRASE))
                        .count())
                .toList();
        for (int i = 0; i < importantCounts.size(); i++) {
            long expected = (i + 1) % 10 == 0 ? 1 : 0;
            assertThat(importantCounts.get(i)).as("batch #%d", i + 1).isEqualTo(expected);
        }
    }

    @Test
    void sendingFailureDoesNotPropagate() {
        when(bulkClient.send(anyList())).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatNoException().isThrownBy(scheduler::sendBatch);
    }
}
