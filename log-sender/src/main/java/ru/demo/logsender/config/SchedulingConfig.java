package ru.demo.logsender.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает планировщик отправки логов. В тестах отключается через {@code log-sender.scheduling.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "log-sender.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig {
}
