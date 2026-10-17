# План реализации: демо OpenSearch (логи, дашборд, алертинг)

Исходное задание: [opensearch-demo.md](opensearch-demo.md)

## 1. Итоговая архитектура

```
┌────────────────────┐  _bulk (каждые 1000 мс,   ┌──────────────────────┐
│ log-sender         │  10 док. / 1 process_id)  │ OpenSearch 3.8.0     │
│ Spring Boot 4.1.1  │ ─────────────────────────▶│ индекс app-logs      │
└────────────────────┘                           │ + Alerting           │
┌────────────────────┐  _bulk (вручную)          │ + Notifications      │
│ scripts/send-logs.sh│ ────────────────────────▶│                      │
└────────────────────┘                           └───┬──────────────┬───┘
                                                     │ query        │ SMTP :1025
                                       ┌─────────────▼──────┐  ┌────▼──────────┐
                                       │ OpenSearch         │  │ MailHog       │
                                       │ Dashboards 3.8.0   │  │ UI :8025      │
                                       │ UI :5601           │  └───────────────┘
                                       └────────────────────┘
        opensearch-init (одноразовый контейнер): шаблон индекса, saved objects,
        SMTP-канал, монитор — всё через REST API, идемпотентно.
```

Все компоненты поднимаются одной командой `docker compose up -d --build`.

### Версии (зафиксировать явно)

| Компонент | Образ / версия | Комментарий |
|---|---|---|
| OpenSearch | `opensearchproject/opensearch:3.8.0` | последний релиз на дату плана (05.08.2026); перед началом проверить Docker Hub |
| OpenSearch Dashboards | `opensearchproject/opensearch-dashboards:3.8.0` | версия строго совпадает с OpenSearch |
| MailHog | `mailhog/mailhog:v1.0.1` | образ только amd64 → на Apple Silicon `platform: linux/amd64` |
| Init-контейнер | `curlimages/curl:8.16.0` | для REST-вызовов настройки |
| Spring Boot | `4.1.1` | Java 25 (Temurin), сборка Maven (wrapper) |

Версии вынести в `.env` (`OPENSEARCH_VERSION=3.8.0` и т.д.) и использовать в `docker-compose.yml`.

## 2. Структура репозитория

```
opensearch-demo/
├── docker-compose.yml
├── .env                                 # версии образов, порты
├── README.md                            # как запустить и проверить
├── opensearch/
│   ├── init/
│   │   ├── init.sh                      # оркестрация настройки (ждёт health, вызывает API)
│   │   ├── index-template.json          # маппинг полей логов
│   │   ├── smtp-account.json            # Notifications: SMTP-отправитель (mailhog)
│   │   ├── email-channel.json           # Notifications: email-канал
│   │   └── monitor.json                 # Alerting: монитор на фразу
│   └── dashboards/
│       └── saved-objects.ndjson         # index pattern, поиски, визуализации, дашборд
├── scripts/
│   └── send-logs.sh                     # ручная отправка пачки логов через _bulk
└── log-sender/                          # Spring Boot приложение
    ├── pom.xml, mvnw
    ├── Dockerfile
    └── src/{main,test}/java/...
```

## 3. Этапы

### Этап 1. Стенд в docker-compose

Сервисы:

1. **opensearch**
   - `discovery.type=single-node`
   - `plugins.security.disabled=true` и `DISABLE_INSTALL_DEMO_CONFIG=true` — для демо без TLS и логина (иначе нужен `OPENSEARCH_INITIAL_ADMIN_PASSWORD` и https везде).
   - `OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g`, `ulimits.memlock`, `nofile`.
   - Порт `9200`, volume `opensearch-data`.
   - healthcheck: `curl -fs localhost:9200/_cluster/health?wait_for_status=yellow`.
   - Плагины Alerting и Notifications входят в стандартный образ — проверить `GET _cat/plugins`.
2. **opensearch-dashboards**
   - `OPENSEARCH_HOSTS=["http://opensearch:9200"]`, `DISABLE_SECURITY_DASHBOARDS_PLUGIN=true`.
   - Порт `5601`, `depends_on: opensearch (service_healthy)`, healthcheck на `/api/status`.
3. **mailhog**
   - Порты `1025` (SMTP), `8025` (UI).
4. **opensearch-init** (одноразовый, `restart: "no"`)
   - `depends_on`: opensearch и dashboards в состоянии healthy, mailhog started.
   - Монтирует `./opensearch` и выполняет `init.sh` (этап 2).
5. **log-sender**
   - `build: ./log-sender`, `depends_on: opensearch-init (service_completed_successfully)`, чтобы шаблон индекса существовал до первой записи.
   - `OPENSEARCH_URL=http://opensearch:9200`.

Критерии готовности: `docker compose up -d` поднимает всё, `curl localhost:9200` отвечает версией 3.8.0, UI Dashboards и MailHog открываются.

### Этап 2. Инициализация OpenSearch (init.sh, идемпотентно)

1. **Шаблон индекса** `PUT _index_template/app-logs` для `app-logs*`:
   ```json
   {
     "index_patterns": ["app-logs*"],
     "template": {
       "settings": { "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "1s" },
       "mappings": {
         "dynamic": "strict",
         "properties": {
           "process_id":      { "type": "keyword" },
           "event_timestamp": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
           "service_name":    { "type": "keyword" },
           "event_message":   { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 1024 } } }
         }
       }
     }
   }
   ```
   - `event_message` — `text`, чтобы искать фразу через `match_phrase` (фраза может быть частью сообщения).
   - `number_of_replicas: 0` — иначе single-node кластер будет yellow.
   - Индекс: один `app-logs` (для демо достаточно; ротация/ISM — вне рамок).
2. **SMTP-отправитель** `POST _plugins/_notifications/configs` с фиксированным `config_id: mailhog-smtp`:
   `config_type: smtp_account`, `host: mailhog`, `port: 1025`, `method: none`, `from_address: alerts@opensearch-demo.local`.
3. **Email-канал** с `config_id: important-message-email`: `config_type: email`, `email_account_id: mailhog-smtp`, `recipient_list: [{recipient: "oncall@opensearch-demo.local"}]`.
4. **Импорт saved objects**: `POST http://opensearch-dashboards:5601/api/saved_objects/_import?overwrite=true` с заголовком `osd-xsrf: true` и файлом `saved-objects.ndjson`.
5. **Монитор** (этап 4): поиск существующего по имени через `_plugins/_alerting/monitors/_search`; если есть — `PUT` (update), иначе `POST`.

Скрипт: `set -euo pipefail`, ожидание готовности с ретраями, проверка HTTP-кодов, для уже существующих notification-конфигов — `PUT` вместо `POST` (идемпотентность при повторном `up`).

### Этап 3. Приложение log-sender (Spring Boot 4.1.1)

**Стек:** Java 25, Maven, `spring-boot-starter-restclient`, `spring-boot-starter-jackson` (Jackson 3), `spring-boot-starter-validation`; `spring-boot-starter-web` не нужен. Отдельный OpenSearch-клиент не требуется: `_bulk` — простой HTTP-вызов с NDJSON, это меньше зависимостей и проще отладка.

**Компоненты:**

| Класс | Ответственность |
|---|---|
| `LogSenderApplication` | `@SpringBootApplication`, `@EnableScheduling`, `@ConfigurationPropertiesScan` |
| `LogSenderProperties` | `record` с `@ConfigurationProperties("log-sender")` и `@Validated`: `opensearchUrl`, `index`, `batchSize=10`, `importantEveryNthBatch=10`, `fixedRateMs=1000`, список `serviceNames` |
| `LogEvent` | `record LogEvent(String processId, Instant eventTimestamp, String serviceName, String eventMessage)`; JSON-имена в snake_case (`@JsonProperty` на компонентах record или `PropertyNamingStrategies.SNAKE_CASE`) |
| `RandomMessageGenerator` | случайные английские фразы из встроенных словарей (субъект/глагол/объект/обстоятельство), без внешних зависимостей; `Random` инжектируется для детерминированных тестов |
| `LogBatchFactory` | собирает пачку: один `UUID.randomUUID()` на пачку, 10 событий, возрастающие `event_timestamp` (+1 мс), один `service_name` на процесс; если пачка «важная» — в одно случайное сообщение вставляет фразу внутрь другого текста: `"<random> This is a very important message <random>"` |
| `OpenSearchBulkClient` | `RestClient`: `POST /{index}/_bulk`, `Content-Type: application/x-ndjson`, тело `{"index":{}}\n{doc}\n…`; разбор ответа — при `errors=true` логировать ошибки по item'ам; таймауты подключения/чтения < 1 с |
| `LogSendingScheduler` | `@Scheduled(fixedRateString = "${log-sender.fixed-rate-ms}")`; `AtomicLong` счётчик пачек; пачка №N важная, если `N % 10 == 0`; исключения ловятся и логируются, чтобы планировщик не останавливался |

**Семантика «фраза в одной из 10 пачек в одном сообщении из 10»:** каждая 10-я пачка содержит ровно одно сообщение с фразой, остальные 9 пачек — без неё. Значит фраза появляется ~раз в 10 секунд. Номер пачки и признак «важная» логировать на INFO (вместе с `process_id`) — удобно сверять с письмами.

**Нюансы:**
- `fixedRate`, а не `fixedDelay` — ровный интервал 1000 мс. Пул планировщика оставить однопоточным (по умолчанию), чтобы пачки не накладывались при медленном OpenSearch.
- `refresh` в `_bulk` не указывать — хватает `refresh_interval: 1s`.
- Graceful shutdown: `spring.lifecycle.timeout-per-shutdown-phase`.
- Dockerfile: multi-stage (Maven build → `eclipse-temurin:25-jre`), не root-пользователь.

**Тесты** (JUnit 5 + AssertJ, `spring-boot-starter-test`):
- Unit: `LogBatchFactory` — 10 сообщений, один валидный UUID, у не-важной пачки фразы нет, у важной — ровно одно сообщение содержит фразу и в нём есть дополнительный текст; `RandomMessageGenerator` никогда сам не генерирует фразу.
- Unit: планировщик — из 10 последовательных вызовов ровно одна пачка важная.
- Unit: сериализация `LogEvent` → поля `process_id`, `event_timestamp` (ISO-8601), `service_name`, `event_message`.
- Integration (Testcontainers 2, `GenericContainer` с образом `opensearchproject/opensearch:3.8.0` и шаблоном индекса из `opensearch/init`): отправка пачки → `_count` = 10, `match_phrase` находит важное сообщение.

### Этап 4. Алертинг

**Тип монитора — Per document monitor (document-level).** Он отслеживает новые документы по seq_no (каждый документ проверяется ровно один раз — нет дублей и пропусков на границах окна), использует percolate-запрос и создаёт отдельный алерт на каждый найденный документ.

```json
{
  "type": "monitor",
  "monitor_type": "doc_level_monitor",
  "name": "important-message-monitor",
  "enabled": true,
  "schedule": { "period": { "interval": 1, "unit": "MINUTES" } },
  "inputs": [{
    "doc_level_input": {
      "description": "Важная фраза в event_message",
      "indices": ["app-logs"],
      "queries": [{
        "id": "important-phrase",
        "name": "important-phrase",
        "query": "event_message:\"This is a very important message\"",
        "tags": ["important"]
      }]
    }
  }],
  "triggers": [{
    "document_level_trigger": {
      "name": "important-message-found",
      "severity": "1",
      "condition": { "script": { "source": "query[name=important-phrase]", "lang": "painless" } },
      "actions": [{
        "name": "send-email",
        "destination_id": "important-message-email",
        "action_execution_policy": { "action_execution_scope": { "per_alert": { "actionable_alerts": ["NEW"] } } },
        "subject_template": { "source": "[OpenSearch] Important message detected" },
        "message_template": { "source": "Monitor {{ctx.monitor.name}}...\n{{#ctx.alerts}}doc: {{related_doc_ids}}...{{/ctx.alerts}}" }
      }]
    }
  }]
}
```

- В тело письма вывести: время срабатывания, индекс, id документа и — если доступно в контексте 3.8 — содержимое документа (`process_id`, `service_name`, `event_message`). Перечень mustache-переменных doc-level монитора проверить по документации 3.8 при реализации.
- Throttling не включать — нужна каждая запись.

**Ограничение по скорости (важно):** минимальный интервал мониторов Alerting — **1 минута**, т.е. задержка «фраза в логе → письмо» = до ~60 с + refresh (1 с) + отправка. Это минимум, достижимый штатными средствами OpenSearch; в README это нужно зафиксировать явно.

*Запасной вариант* (если doc-level монитор не даст в письме полей документа): query-level монитор с тем же интервалом 1 мин, запросом `match_phrase` + `range event_timestamp gte {{period_end}}||-1m`, `size: 50`, триггер `ctx.results[0].hits.total.value > 0`, в письме — `{{#ctx.results.0.hits.hits}}{{_source.process_id}} {{_source.event_message}}{{/ctx.results.0.hits.hits}}`.

Задержка до 1 минуты **согласована как допустимая** — отдельный быстрый сервис не делаем.

Проверка: после запуска в течение ~1–2 минут в MailHog UI (`http://localhost:8025`) появляются письма; в Dashboards → Alerting виден монитор и алерты.

### Этап 5. Дашборд в OpenSearch Dashboards

Объекты создаются на поднятом стенде (через UI или Saved Objects API), затем экспортируются в `opensearch/dashboards/saved-objects.ndjson` и импортируются init-скриптом при каждом запуске. Index pattern экспортируется вместе со списком полей (`fields`), иначе визуализации не находят `event_timestamp`/`service_name`.

1. **Index pattern** `app-logs*`, time field `event_timestamp`, фиксированный id `app-logs`.
2. **Saved search «All logs»** — колонки `event_timestamp`, `service_name`, `process_id`, `event_message`.
3. **Saved search «Important messages»** — запрос DQL `event_message:"This is a very important message"`.
4. **Визуализации:**
   - Metric: количество важных сообщений за выбранный период.
   - Line/Bar (date histogram по `event_timestamp`): все логи vs важные сообщения (два filter-агрегата).
   - Data table: важные сообщения по `service_name` (terms).
   - Data table / pie: топ `service_name` по объёму логов.
5. **Dashboard «Logs monitoring»**: все визуализации + saved search «Important messages» (таблица с `process_id` для корреляции; клик по `process_id` → фильтр → видно все 10 сообщений процесса). `timeRestore: true`, период `now-15m`, автообновление `5s`.

Проверка: дашборд открывается сразу после `docker compose up`, без ручных действий, и обновляется в реальном времени.

### Этап 6. Скрипт ручной отправки логов

`scripts/send-logs.sh [--important] [--count N]`:
- генерирует одну пачку (10 сообщений, один `process_id` через `uuidgen`, `event_timestamp` = текущее время UTC) и шлёт через `curl -XPOST localhost:9200/app-logs/_bulk -H 'Content-Type: application/x-ndjson' --data-binary @-`;
- `--important` — одно сообщение пачки содержит фразу внутри другого текста;
- проверяет `"errors":false` в ответе.

Нужен для быстрой проверки алерта и дашборда без приложения и для демонстрации.

### Этап 7. Документация и приёмка

README:
- требования (Docker Desktop, ≥4 ГБ памяти; на Linux — `vm.max_map_count=262144`);
- запуск/остановка (`docker compose up -d --build`, `docker compose down -v`);
- адреса: OpenSearch `:9200`, Dashboards `:5601`, MailHog `:8025`;
- где смотреть дашборд, монитор, письма; ожидаемая задержка алерта;
- как пересоздать saved objects (экспорт из UI → перезапись ndjson).

## 4. Чек-лист приёмки

- [ ] `docker-compose.yml` содержит OpenSearch и Dashboards с явной версией `3.8.0` (не `latest`).
- [ ] `docker compose up -d --build` с чистого состояния поднимает всё без ручных шагов; `opensearch-init` завершается с кодом 0; повторный запуск init тоже успешен.
- [ ] В индексе `app-logs` документы с полями `process_id` (UUID), `event_timestamp`, `service_name`, `event_message`; маппинг соответствует шаблону.
- [ ] Каждую секунду приходит ровно 10 документов с одинаковым `process_id`.
- [ ] Фраза «This is a very important message» встречается ровно в 1 сообщении каждой 10-й пачки и окружена другим текстом.
- [ ] `scripts/send-logs.sh` и `--important` работают.
- [ ] Discover показывает логи; дашборд «Logs monitoring» доступен после старта и отображает важные сообщения.
- [ ] Монитор создан и включён; письма с деталями события приходят в MailHog UI в пределах ~1 мин после появления фразы.
- [ ] Unit- и интеграционные тесты log-sender проходят (`./mvnw test`).
- [ ] README описывает запуск, проверку и ограничение по задержке алерта.

## 5. Риски и решения

| Риск | Решение |
|---|---|
| Минимальный интервал монитора 1 мин против «максимально быстро» | doc-level монитор с интервалом 1 мин — минимум штатными средствами; задержка до 1 мин согласована; задокументировать в README |
| MailHog не поддерживает arm64 | `platform: linux/amd64` (эмуляция на Apple Silicon) или замена на совместимый Mailpit — только по согласованию, т.к. в задании указан MailHog |
| Ручной ndjson saved objects хрупкий | создавать объекты в UI и экспортировать; фиксированные id объектов |
| Security plugin в 3.x требует пароль/TLS | отключить для демо (`plugins.security.disabled=true`), отметить в README, что это не для prod |
| log-sender стартует раньше шаблона индекса → динамический маппинг | `depends_on: service_completed_successfully` от `opensearch-init`; `dynamic: strict` в шаблоне |
| Недоступность OpenSearch роняет планировщик | ловить исключения в `@Scheduled`, логировать, продолжать |

## 6. Принятые решения

1. Приложение log-sender пишется на **Java** (25).
2. Задержка алерта **до ~1 минуты допустима** (ограничение Alerting plugin); отдельный watcher-сервис не нужен.
3. Сборка — **Maven** вместо Gradle: Spring Initializr на момент реализации не генерировал Gradle-проекты (HTTP 500), а выбор сборщика требованием не был.
