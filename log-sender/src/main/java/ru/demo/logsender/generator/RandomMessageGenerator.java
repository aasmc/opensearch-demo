package ru.demo.logsender.generator;

import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Генерирует случайные английские фразы для сообщений лога.
 * Словари намеренно не содержат слов из {@link LogBatchFactory#IMPORTANT_PHRASE}.
 */
@Component
public class RandomMessageGenerator {

    private static final List<String> SUBJECTS = List.of(
            "User session", "Payment request", "Order", "Cache entry", "Background job",
            "Inventory check", "Notification", "Report export", "Auth token", "Shipping quote");

    private static final List<String> VERBS = List.of(
            "was processed", "was created", "was updated", "was validated", "was queued",
            "was retried", "was completed", "was received", "was scheduled", "was acknowledged");

    private static final List<String> DETAILS = List.of(
            "without errors", "after a short delay", "by the worker pool", "for the current tenant",
            "using the fallback route", "within the expected time", "on the primary node",
            "with default settings", "for the nightly batch", "after cache refresh");

    private final RandomGenerator random;

    public RandomMessageGenerator(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @return случайное предложение без завершающей точки, например
     *         {@code "Order was validated on the primary node in 42 ms"}
     */
    public String sentence() {
        return "%s %s %s in %d ms".formatted(
                pick(SUBJECTS), pick(VERBS), pick(DETAILS), random.nextInt(1, 1000));
    }

    private String pick(List<String> words) {
        return words.get(random.nextInt(words.size()));
    }
}
