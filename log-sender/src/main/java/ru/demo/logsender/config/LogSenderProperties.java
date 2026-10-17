package ru.demo.logsender.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки отправки логов в OpenSearch.
 *
 * @param opensearchUrl          базовый URL OpenSearch
 * @param index                  индекс, в который пишутся логи
 * @param batchSize              количество сообщений в одной пачке (все с одним process_id)
 * @param importantEveryNthBatch каждая N-я пачка содержит одно сообщение с важной фразой
 * @param serviceNames           имена сервисов, от лица которых генерируются логи
 * @param connectTimeout         таймаут подключения к OpenSearch
 * @param readTimeout            таймаут ожидания ответа OpenSearch
 */
@Validated
@ConfigurationProperties("log-sender")
public record LogSenderProperties(
        @NotNull URI opensearchUrl,
        @NotBlank String index,
        @Positive int batchSize,
        @Positive int importantEveryNthBatch,
        @NotEmpty List<@NotBlank String> serviceNames,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
