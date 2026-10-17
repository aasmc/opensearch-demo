package ru.demo.logsender.opensearch;

import java.util.List;

/**
 * Итог bulk-запроса.
 *
 * @param total    количество отправленных документов
 * @param failures причины ошибок по документам, которые не удалось проиндексировать
 */
public record BulkResult(int total, List<String> failures) {

    public BulkResult {
        failures = List.copyOf(failures);
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
