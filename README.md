# OpenSearch demo: логи, дашборд и алертинг

Демо-стенд: Spring Boot приложение каждую секунду пишет пачку логов в OpenSearch, OpenSearch Dashboards
показывает их на дашборде, а монитор Alerting отправляет письмо в MailHog, когда в логах появляется фраза
**"This is a very important message"** и в логах того же процесса (`process_id`) есть запись уровня `WARN`
или `ERROR`.

Задание: [specs/opensearch-demo.md](specs/opensearch-demo.md), план: [specs/opensearch-demo-plan.md](specs/opensearch-demo-plan.md).

## Состав

| Сервис | Версия | Адрес на хосте | Назначение |
|---|---|---|---|
| `opensearch` | `opensearchproject/opensearch:3.8.0` | http://localhost:9200 | хранение и поиск логов, Alerting, Notifications |
| `opensearch-dashboards` | `opensearchproject/opensearch-dashboards:3.8.0` | http://localhost:5601 | дашборд и UI алертинга |
| `mailhog` | `mailhog/mailhog:v1.0.1` | http://localhost:8025 (UI), `localhost:1025` (SMTP) | тестовый почтовый сервер |
| `opensearch-init` | `curlimages/curl:8.16.0` | — | одноразовая настройка стенда ([opensearch/init/init.sh](opensearch/init/init.sh)) |
| `log-sender` | Spring Boot 4.1.1, Java 25 | — | генератор логов ([log-sender](log-sender)) |

Версии образов заданы в [.env](.env).

## Запуск

Требования: Docker с Docker Compose v2, не меньше 4 ГБ памяти для Docker. На Linux дополнительно нужно
`sudo sysctl -w vm.max_map_count=262144`.

```bash
docker compose up -d --build     # поднять стенд (первая сборка log-sender занимает пару минут)
docker compose logs -f log-sender
docker compose down -v           # остановить и удалить данные
```

Ручных шагов после запуска нет. `opensearch-init` создаёт шаблон и индекс `app-logs`, SMTP-отправитель и
email-канал, монитор и объекты дашборда, после чего стартует `log-sender`. Скрипт идемпотентен, его можно
перезапустить: `docker compose run --rm opensearch-init`. На стенде, поднятом до появления `log_level`, он
добавит поле в маппинг существующего индекса и пересоздаст монитор нового типа, удалять данные не нужно.

## Что происходит

**Логи.** Каждые 1000 мс `log-sender` отправляет через Bulk API пачку из 10 сообщений с одним `process_id`.
У каждого сообщения есть уровень логирования SLF4J (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).

| Пачка | Как часто | Содержимое |
|---|---|---|
| обычная | все остальные | уровни случайные, включая `WARN` и `ERROR` |
| важная | каждая 10-я | ровно одно сообщение на `INFO` с важной фразой внутри другого текста, например `Order was queued ... in 42 ms. This is a very important message: Cache entry was updated ... in 7 ms.`; остальные сообщения только `TRACE`/`DEBUG`/`INFO` |
| важная с проблемой | каждая 3-я важная (каждая 30-я пачка) | как важная, но ещё ровно одно другое сообщение имеет уровень `WARN` или `ERROR`. Сообщение с фразой всегда на `INFO` |

Письмо приходит только по пачкам третьего вида.

Поля документа (маппинг в [opensearch/init/index-template.json](opensearch/init/index-template.json)):

| Поле | Тип | Описание |
|---|---|---|
| `process_id` | `keyword` | UUID процесса, общий для всех сообщений пачки |
| `event_timestamp` | `date` | время записи (UTC, миллисекунды) |
| `service_name` | `keyword` | имя сервиса |
| `log_level` | `keyword` | уровень логирования SLF4J: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `event_message` | `text` (+ `.keyword`) | текст сообщения; `text` нужен для поиска фразы через `match_phrase` |

**Дашборд.** OpenSearch Dashboards → Dashboards → **Logs monitoring**
(http://localhost:5601/app/dashboards#/view/logs-monitoring). Период по умолчанию — 15 минут,
автообновление — 5 секунд. На дашборде:

- счётчик важных сообщений и их распределение во времени;
- объём логов во времени и по сервисам;
- важные сообщения по сервисам;
- таблица важных сообщений с `process_id`.

Чтобы увидеть все 10 сообщений процесса, щёлкните по `process_id` и добавьте фильтр. Логи также видны в
Discover (index pattern `app-logs*`, сохранённые поиски «All logs» и «Important messages»).

**Алерт.** Монитор `important-message-monitor` типа *per bucket monitor*
([opensearch/init/monitor.json](opensearch/init/monitor.json)) запускается раз в минуту и смотрит документы за
последние 2 минуты. Он группирует их по `process_id` и срабатывает на процесс, в логах которого есть и фраза
`This is a very important message`, и хотя бы одна запись уровня `WARN` или `ERROR`. Per document monitor тут
не подходит: он проверяет каждый документ отдельно и не может связать документы одного процесса.

На каждый такой процесс приходит одно письмо на `oncall@opensearch-demo.local` через канал Notifications
`important-message-email`: пока процесс виден в окне, алерт остаётся активным и повторно не отправляется. В
письме указаны `process_id` и найденные документы процесса — сообщение с фразой и записи `WARN`/`ERROR` — с
полями `log_level`, `service_name`, `event_timestamp`, `event_message` и id документа.

Подробный разбор запроса монитора — что делает каждая его часть, почему она нужна и какую нагрузку даёт на
OpenSearch, со ссылками на документацию и исходный код — в [MONITOR_QUERY.md](MONITOR_QUERY.md). Ручная
настройка монитора через UI — в [DASHBOARDS_CONFIGURATION.md](DASHBOARDS_CONFIGURATION.md#6-монитор-alerting).

Письма смотрите в MailHog: http://localhost:8025. Монитор и алерты — в OpenSearch Dashboards → Alerting.

> **Задержка алерта.** Минимальный интервал запуска монитора в OpenSearch Alerting — 1 минута, поэтому
> письмо приходит в течение ~1 минуты после записи фразы в индекс. Быстрее штатными средствами нельзя;
> такая задержка согласована.

## Ручная отправка логов

```bash
scripts/send-logs.sh                         # одна пачка из 10 сообщений со случайными уровнями
scripts/send-logs.sh --important             # пачка с важной фразой, но без WARN/ERROR — письма не будет
scripts/send-logs.sh --important --count 3   # третья пачка ещё и с WARN/ERROR — через ~1 мин придёт письмо
scripts/send-logs.sh --count 5               # пять пачек
OPENSEARCH_URL=http://host:9200 SERVICE_NAME=my-service scripts/send-logs.sh
```

Скрипт следует тому же правилу, что и приложение: в каждой третьей важной пачке за запуск одно сообщение без
фразы получает уровень `WARN` или `ERROR`.

## Приложение log-sender

Настройки в [application.yaml](log-sender/src/main/resources/application.yaml) (префикс `log-sender`):
`opensearch-url` (переменная `OPENSEARCH_URL`), `index`, `batch-size`, `important-every-nth-batch`,
`problem-every-nth-important-batch`, `fixed-rate-ms`, `service-names`, таймауты.

```bash
cd log-sender
./mvnw test                          # unit- и интеграционные тесты (нужен Docker: Testcontainers с OpenSearch 3.8.0)
OPENSEARCH_URL=http://localhost:9200 ./mvnw spring-boot:run   # запуск вне Docker
```

## Изменение дашборда

Объекты дашборда хранятся в [opensearch/dashboards/saved-objects.ndjson](opensearch/dashboards/saved-objects.ndjson)
и импортируются при каждом запуске `opensearch-init` с перезаписью. Чтобы сохранить изменения, сделанные в UI,
экспортируйте их:

```bash
curl -s -X POST localhost:5601/api/saved_objects/_export -H 'osd-xsrf: true' -H 'Content-Type: application/json' \
  -d '{"type":["index-pattern","search","visualization","dashboard"],"excludeExportDetails":true}' \
  > opensearch/dashboards/saved-objects.ndjson
```

## Ограничения демо

- Security plugin OpenSearch отключён (`plugins.security.disabled=true`): нет TLS и аутентификации. Для
  production так делать нельзя.
- Образ MailHog собран только под `linux/amd64`, на Apple Silicon он запускается через эмуляцию.
- Один узел, без реплик, без ротации индекса (ISM).
