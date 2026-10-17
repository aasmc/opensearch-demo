package ru.demo.logsender.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RandomMessageGeneratorTest {

    @Test
    void generatesNonBlankEnglishSentencesWithoutImportantPhrase() {
        RandomMessageGenerator generator = new RandomMessageGenerator(new Random(7));

        IntStream.range(0, 1_000).mapToObj(i -> generator.sentence()).forEach(sentence -> {
            assertThat(sentence).isNotBlank().matches("[A-Za-z0-9 ]+");
            assertThat(sentence).doesNotContainIgnoringCase("important");
        });
    }
}
