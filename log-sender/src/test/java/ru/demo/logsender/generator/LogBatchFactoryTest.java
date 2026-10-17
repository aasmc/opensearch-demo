package ru.demo.logsender.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.demo.logsender.generator.LogBatchFactory.IMPORTANT_PHRASE;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;
import ru.demo.logsender.TestProperties;

class LogBatchFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:15:30.123456Z");
    private static final List<Level> PROBLEM_LEVELS = List.of(Level.WARN, Level.ERROR);

    private LogBatchFactory factory(long seed) {
        Random random = new Random(seed);
        return new LogBatchFactory(new RandomMessageGenerator(random), TestProperties.properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), random);
    }

    @Test
    void batchContainsTenEventsOfOneProcessAndService() {
        List<LogEvent> batch = factory(1).createBatch(BatchType.REGULAR);

        assertThat(batch).hasSize(10);
        assertThat(batch).extracting(LogEvent::processId).containsOnly(batch.getFirst().processId());
        assertThat(UUID.fromString(batch.getFirst().processId()).toString()).isEqualTo(batch.getFirst().processId());
        assertThat(batch).extracting(LogEvent::serviceName).containsOnly(batch.getFirst().serviceName());
        assertThat(batch).extracting(LogEvent::eventMessage).allSatisfy(message -> assertThat(message).isNotBlank());
        assertThat(batch).extracting(LogEvent::logLevel).doesNotContainNull();
    }

    @Test
    void timestampsAreTruncatedToMillisAndAscending() {
        List<LogEvent> batch = factory(1).createBatch(BatchType.REGULAR);

        assertThat(batch.getFirst().eventTimestamp()).isEqualTo(Instant.parse("2026-10-17T10:15:30.123Z"));
        assertThat(batch).extracting(LogEvent::eventTimestamp).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void differentBatchesHaveDifferentProcessIds() {
        LogBatchFactory factory = factory(1);

        assertThat(factory.createBatch(BatchType.REGULAR).getFirst().processId())
                .isNotEqualTo(factory.createBatch(BatchType.REGULAR).getFirst().processId());
    }

    @Test
    void regularBatchesUseAllSlf4jLevels() {
        LogBatchFactory factory = factory(1);

        List<Level> levels = IntStream.range(0, 100)
                .mapToObj(i -> factory.createBatch(BatchType.REGULAR))
                .flatMap(List::stream)
                .map(LogEvent::logLevel)
                .toList();

        assertThat(levels).contains(Level.values());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void regularBatchHasNoImportantPhrase(long seed) {
        assertThat(factory(seed).createBatch(BatchType.REGULAR))
                .extracting(LogEvent::eventMessage)
                .noneMatch(message -> message.contains(IMPORTANT_PHRASE));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void importantBatchHasExactlyOneInfoMessageWithPhraseSurroundedByOtherText(long seed) {
        List<LogEvent> withPhrase = phraseEvents(factory(seed).createBatch(BatchType.IMPORTANT));

        assertThat(withPhrase).singleElement().satisfies(event -> {
            assertThat(event.logLevel()).isEqualTo(Level.INFO);
            assertThat(event.eventMessage()).isNotEqualTo(IMPORTANT_PHRASE);
            assertThat(event.eventMessage().indexOf(IMPORTANT_PHRASE)).isPositive();
            assertThat(event.eventMessage()).doesNotEndWith(IMPORTANT_PHRASE);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void importantBatchHasNoWarnOrError(long seed) {
        assertThat(factory(seed).createBatch(BatchType.IMPORTANT))
                .extracting(LogEvent::logLevel)
                .doesNotContainAnyElementsOf(PROBLEM_LEVELS);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100})
    void importantBatchWithProblemHasInfoPhraseAndExactlyOneOtherWarnOrError(long seed) {
        List<LogEvent> batch = factory(seed).createBatch(BatchType.IMPORTANT_WITH_PROBLEM);

        assertThat(phraseEvents(batch)).singleElement()
                .extracting(LogEvent::logLevel).isEqualTo(Level.INFO);
        assertThat(batch).filteredOn(event -> PROBLEM_LEVELS.contains(event.logLevel()))
                .singleElement()
                .satisfies(event -> assertThat(event.eventMessage()).doesNotContain(IMPORTANT_PHRASE));
    }

    private static List<LogEvent> phraseEvents(List<LogEvent> batch) {
        return batch.stream().filter(event -> event.eventMessage().contains(IMPORTANT_PHRASE)).toList();
    }
}
