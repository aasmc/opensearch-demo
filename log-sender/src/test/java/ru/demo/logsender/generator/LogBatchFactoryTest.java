package ru.demo.logsender.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.demo.logsender.generator.LogBatchFactory.IMPORTANT_PHRASE;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.demo.logsender.TestProperties;

class LogBatchFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:15:30.123456Z");

    private LogBatchFactory factory(long seed) {
        Random random = new Random(seed);
        return new LogBatchFactory(new RandomMessageGenerator(random), TestProperties.properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), random);
    }

    @Test
    void batchContainsTenEventsOfOneProcessAndService() {
        List<LogEvent> batch = factory(1).createBatch(false);

        assertThat(batch).hasSize(10);
        assertThat(batch).extracting(LogEvent::processId).containsOnly(batch.getFirst().processId());
        assertThat(UUID.fromString(batch.getFirst().processId()).toString()).isEqualTo(batch.getFirst().processId());
        assertThat(batch).extracting(LogEvent::serviceName).containsOnly(batch.getFirst().serviceName());
        assertThat(batch).extracting(LogEvent::eventMessage).allSatisfy(message -> assertThat(message).isNotBlank());
    }

    @Test
    void timestampsAreTruncatedToMillisAndAscending() {
        List<LogEvent> batch = factory(1).createBatch(false);

        assertThat(batch.getFirst().eventTimestamp()).isEqualTo(Instant.parse("2026-10-17T10:15:30.123Z"));
        assertThat(batch).extracting(LogEvent::eventTimestamp).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void differentBatchesHaveDifferentProcessIds() {
        LogBatchFactory factory = factory(1);

        assertThat(factory.createBatch(false).getFirst().processId())
                .isNotEqualTo(factory.createBatch(false).getFirst().processId());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void regularBatchHasNoImportantPhrase(long seed) {
        assertThat(factory(seed).createBatch(false))
                .extracting(LogEvent::eventMessage)
                .noneMatch(message -> message.contains(IMPORTANT_PHRASE));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void importantBatchHasExactlyOneMessageWithPhraseSurroundedByOtherText(long seed) {
        List<String> withPhrase = factory(seed).createBatch(true).stream()
                .map(LogEvent::eventMessage)
                .filter(message -> message.contains(IMPORTANT_PHRASE))
                .toList();

        assertThat(withPhrase).singleElement().satisfies(message -> {
            assertThat(message).isNotEqualTo(IMPORTANT_PHRASE);
            assertThat(message.indexOf(IMPORTANT_PHRASE)).isPositive();
            assertThat(message).doesNotEndWith(IMPORTANT_PHRASE);
        });
    }
}
