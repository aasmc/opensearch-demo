# Запрос монитора `important-message-monitor`

Разбор запроса из [opensearch/init/monitor.json](opensearch/init/monitor.json), по которому монитор решает, отправлять ли
письмо: что делает каждая часть, почему она нужна и какую нагрузку запрос даёт на OpenSearch.

Всё описание относится к версиям стенда: OpenSearch **3.8.0** и плагин Alerting **3.8.0.0**. Каждое утверждение
подкреплено одним из трёх источников:

- **[док]** — документация OpenSearch 3.8 (docs.opensearch.org/3.8);
- **[код]** — исходный код по тегам релиза: OpenSearch `3.8.0`, alerting `3.8.0.0`, common-utils `3.8.0.0`;
- **[проверено]** — эксперимент на контейнере `opensearchproject/opensearch:3.8.0` с маппингом из
  [index-template.json](opensearch/init/index-template.json); как повторить — в разделе
  [10](#10-как-проверить-самостоятельно).

Там, где документация и код расходятся, это сказано явно.

## Содержание

1. [Запрос целиком](#1-запрос-целиком)
2. [Как монитор выполняет запрос](#2-как-монитор-выполняет-запрос)
3. [`size: 0`](#3-size-0)
4. [`derived` — вычисляемые поля](#4-derived--вычисляемые-поля)
5. [`query` — какие документы попадают в выборку](#5-query--какие-документы-попадают-в-выборку)
6. [`aggregations` — группировка по процессу](#6-aggregations--группировка-по-процессу)
7. [Условие триггера](#7-условие-триггера)
8. [Нагрузка на OpenSearch: не положит ли запрос систему](#8-нагрузка-на-opensearch-не-положит-ли-запрос-систему)
9. [Вариант для Platform V Search](#9-вариант-для-platform-v-search)
10. [Как проверить самостоятельно](#10-как-проверить-самостоятельно)
11. [Источники](#11-источники)

## 1. Запрос целиком

Поисковый запрос монитора (`inputs[0].search.query`):

```json
{
  "size": 0,
  "derived": {
    "has_important_phrase": {
      "type": "long",
      "script": {
        "source": "emit(doc['event_message.keyword'].size() > 0 && doc['event_message.keyword'].value.toLowerCase().contains('this is a very important message') ? 1 : 0)"
      }
    },
    "is_warn_or_error": {
      "type": "long",
      "script": {
        "source": "emit(doc['log_level'].size() > 0 && (doc['log_level'].value == 'WARN' || doc['log_level'].value == 'ERROR') ? 1 : 0)"
      }
    }
  },
  "query": {
    "bool": {
      "filter": [
        {
          "range": {
            "event_timestamp": {
              "from": "{{period_end}}||-2m",
              "to": "{{period_end}}",
              "include_lower": true,
              "include_upper": true,
              "format": "epoch_millis"
            }
          }
        },
        {
          "bool": {
            "should": [
              { "match_phrase": { "event_message": "This is a very important message" } },
              { "terms": { "log_level": ["WARN", "ERROR"] } }
            ],
            "minimum_should_match": 1
          }
        }
      ]
    }
  },
  "aggregations": {
    "composite_agg": {
      "composite": {
        "size": 1000,
        "sources": [ { "process_id": { "terms": { "field": "process_id" } } } ]
      },
      "aggregations": {
        "important": { "max": { "field": "has_important_phrase" } },
        "problems":  { "max": { "field": "is_warn_or_error" } }
      }
    }
  }
}
```

Условие триггера `important-message-with-problems` (`triggers[0].bucket_level_trigger.condition`):

```json
{
  "buckets_path": { "important": "important", "problems": "problems" },
  "parent_bucket_path": "composite_agg",
  "script": { "source": "params.important > 0 && params.problems > 0", "lang": "painless" }
}
```

В двух словах: запрос берёт записи за последние 2 минуты, у которых есть фраза или уровень `WARN`/`ERROR`, и
группирует их по `process_id`. Для каждого процесса он считает два флага: была ли фраза и был ли `WARN`/`ERROR`.
Триггер срабатывает на процессы, у которых подняты оба флага.

## 2. Как монитор выполняет запрос

Тип монитора — `bucket_level_monitor` (в интерфейсе «Per bucket monitor»). Он оценивает условие триггера
отдельно для каждого бакета агрегации [[док][d-monitors-types]]. Один запуск, раз в минуту
(`schedule.period.interval: 1`), проходит так:

1. **Подстановка времени.** Плагин превращает запрос в mustache-шаблон и подставляет `period_start` и
   `period_end` как epoch-миллисекунды [[код][c-input-render]]. В документации эти переменные описаны как
   границы интервала запуска монитора [[док][d-bucket-period]].
2. **Внедрение триггера в запрос.** К запросу добавляется pipeline-агрегация `bucket_selector_ext` с условием
   триггера [[код][c-rewrite-selector]]. Поэтому условие вычисляется внутри того же поиска, на этапе reduce, по
   одному разу на бакет [[код][c-selector-reduce]]. Отдельного запроса для триггера нет.
3. **Пагинация.** Если composite-агрегация вернула `after_key`, плагин повторяет запрос со следующей страницей,
   пока страницы не кончатся [[код][c-runner-loop]], [[код][c-rewrite-after]].
4. **Классификация алертов.** Каждый сработавший бакет (`process_id`) становится алертом `NEW`, если его ещё
   нет среди активных. Если он уже активен, алерт помечается `DEDUPED`. Активный алерт, бакет которого пропал из
   выборки, становится `COMPLETED` [[код][c-runner-loop]]. Переменные `ctx.newAlerts`, `ctx.dedupedAlerts` и
   `ctx.completedAlerts` описаны в [[док][d-monitor-vars]].
5. **Примеры документов.** Если есть новые алерты и шаблон письма их выводит, выполняется **ещё один** поиск —
   тот же запрос с добавленными `top_hits` [[код][c-runner-sample]]. Подробнее — в разделе
   [6.3](#63-примеры-документов-для-письма).
6. **Письма.** Действие настроено как `per_alert` с `actionable_alerts: ["NEW"]`, поэтому письмо уходит
   только по новым алертам, одно на процесс (пример конфигурации — в [[док][d-api]]). Пока процесс остаётся в
   2-минутном окне, его алерт `DEDUPED` и повторного письма нет.

## 3. `size: 0`

**Что делает.** Запрос не возвращает сами документы, только результат агрегаций.

**Почему.** Монитору нужны только бакеты: триггер смотрит на `buckets_path`, а не на `hits`
[[док][d-trigger-extraction]]. Без `size: 0` в ответ попали бы до 10 документов, которые никто не читает.
Документы для письма плагин достаёт отдельно, через `top_hits` (раздел [6.3](#63-примеры-документов-для-письма)).
Этот приём используют и примеры в документации [[док][d-bucket-create]].

## 4. `derived` — вычисляемые поля

### 4.1. Что это

Derived fields — поля, которые вычисляются скриптом во время запроса и не хранятся в индексе. Их можно
объявить прямо в теле поиска, в объекте `derived` [[док][d-derived]]. Значение возвращается через `emit()`, тип
`long` поддерживается [[док][d-derived-params]]. Начиная с OpenSearch 2.17 derived-поля можно использовать в
большинстве агрегаций; не поддерживаются гео-агрегации, `significant_terms`, `significant_text` и
`scripted_metric` [[док][d-derived-aggs]]. `max` в списке исключений нет.

| Поле | Значение |
|---|---|
| `has_important_phrase` | `1`, если `event_message` содержит фразу (без учёта регистра), иначе `0` |
| `is_warn_or_error` | `1`, если `log_level` равен `WARN` или `ERROR`, иначе `0` |

### 4.2. Почему derived-поля, а не `filter`-агрегации

Естественнее было бы посчитать «есть ли фраза» через sub-агрегацию `filter`, но так письмо приходит без
примеров документов. Это ограничение плагина Alerting, а не OpenSearch:

- Для сбора примеров плагин разбирает **каждую** sub-агрегацию composite-агрегации и ожидает внутри
  плоский объект «параметр → строка», например `{"max": {"field": "..."}}`. Значения он приводит к
  `Map<String, String>` [[код][c-sample-aggs]].
- У `filter`-агрегации внутри вложенный запрос, а не строка, поэтому приведение падает с
  `ClassCastException`. Плагин ловит исключение, пишет в лог `Error retrieving sample documents` и отправляет
  письмо с пустым `sample_documents` [[код][c-runner-sample]]. **[проверено]** при разработке стенда.

Derived-поле даёт обычное числовое значение на документ, поэтому подходит обычная метрика
`{"max": {"field": "has_important_phrase"}}`, и сбор примеров работает. Вторая причина: derived-поле, объявленное
в запросе, не требует менять маппинг индекса [[док][d-derived]].

### 4.3. Почему скрипты написаны именно так

- **`doc['...']`, а не `params._source`.** Скрипты читают doc values. Документация прямо рекомендует doc values
  вместо `_source` ради скорости [[док][d-derived-perf]]. Оба поля в маппинге — `keyword` с включёнными doc
  values (по умолчанию).
- **Проверка `.size() > 0`.** Если у документа нет значения в doc values, обращение к `.value` в Painless
  завершилось бы ошибкой. Проверка возвращает `0` вместо исключения. У `event_message.keyword` значения нет,
  например, у сообщений длиннее `ignore_above: 1024` символов: такие строки в `keyword` не индексируются
  [[док][d-keyword-params]].
- **`toLowerCase().contains(...)`.** `match_phrase` в фильтре регистронезависим, потому что текст проходит
  анализатор поля [[док][d-match-phrase]]. Скрипт приводит строку к нижнему регистру, чтобы вести себя так же.
- **`emit(... ? 1 : 0)` типа `long`.** Тип значения в `emit` должен совпадать с объявленным `type`, иначе
  запрос падает [[док][d-derived-params]]. Числа 0/1 нужны, чтобы `max` давал ответ на вопрос «есть ли хотя бы
  одна такая запись».

### 4.4. Чего derived-поля стоят

- Они не индексируются и считаются при каждом запросе, поэтому работают медленнее обычных полей. Чтобы
  смягчить это, документация рекомендует сужать выборку фильтрами по индексированным полям
  [[док][d-derived-perf]]. Именно для этого в запросе стоит фильтр из раздела 5.2.
- **Код OpenSearch:** агрегация по derived-полю получает aggregation script, то есть скрипт выполняется для
  каждого документа, попавшего в агрегацию [[код][c-vsc-derived]].
- **Требуют `search.allow_expensive_queries: true`** [[док][d-derived-prereq]]. Если выключить настройку, любой
  запрос с `derived` отклоняется [[код][c-derived-expensive]]. **[проверено]** Поиск возвращает
  `[derived field] queries cannot be executed when 'search.allow_expensive_queries' is set to false.`, а монитор
  завершается с ошибкой `all shards failed`, и алерты не создаются. По умолчанию настройка включена.
- **Можно выключить совсем** настройками `search.derived_field.enabled` и `index.query.derived_field.enabled`
  [[док][d-derived-prereq]]. Монитор тогда тоже перестанет работать.
- **Скрипты компилируются один раз и кэшируются** [[док][d-scripts-cache]]. **[проверено]** После десятков
  запусков счётчик компиляций в контексте `derived_field` равен 2, по одной на каждый скрипт
  (`GET _nodes/stats/script_cache`).

## 5. `query` — какие документы попадают в выборку

### 5.1. `bool.filter`

**Что делает.** Оба условия стоят в `filter`, то есть работают как логическое «И» в контексте фильтра. Документ
либо подходит, либо нет, релевантность (`_score`) не считается [[док][d-filter-context]], [[док][d-bool]].

**Почему.** Оценка релевантности монитору не нужна. Кроме того, фильтры OpenSearch кэширует
[[док][d-filter-context]].

### 5.2. `range` по `event_timestamp`

```json
"range": { "event_timestamp": {
  "from": "{{period_end}}||-2m", "to": "{{period_end}}",
  "include_lower": true, "include_upper": true, "format": "epoch_millis" } }
```

**Что делает.** Оставляет записи с `event_timestamp` в интервале `[period_end − 2 минуты, period_end]`.

- `{{period_end}}` — mustache-переменная Alerting: время окончания текущего запуска в epoch-миллисекундах
  [[код][c-input-render]], [[док][d-bucket-period]].
- `||-2m` — date math: от опорной даты после `||` отнимается 2 минуты [[док][d-range-relative]].
- `format: epoch_millis` — подставленное значение является числом миллисекунд, а не датой в формате маппинга.
  Параметр `format` задаёт формат дат в запросе [[док][d-range-params]].
- `from`/`to`/`include_lower`/`include_upper` — устаревшая, но рабочая запись `gte`/`lte`. В справочнике
  `range` этих параметров нет, однако OpenSearch их разбирает и сам сериализует `range` именно в этой форме
  [[код][c-range-from]]. Поэтому в таком виде запрос генерирует и визуальный редактор Alerting, и пример в
  документации мониторов [[док][d-bucket-period]].

**Почему окно 2 минуты при запуске раз в минуту.** Окна соседних запусков перекрываются на минуту:

- Запись, которая стала доступна для поиска с задержкой (refresh индекса — 1 с, плюс доставка из
  `log-sender`), всё равно попадёт хотя бы в одно окно.
- Перекрытие не даёт дублей писем: в следующем запуске тот же `process_id` уже `DEDUPED`, а письма уходят только
  по `NEW` (раздел 2, шаг 6). **[проверено]** при приёмке стенда: на каждую «важную пачку с проблемой» пришло
  ровно одно письмо.

**Ограничение.** Окно строится по `event_timestamp`, который проставляет `log-sender`, а не по времени
индексации. Если запись станет доступна для поиска позже чем через 2 минуты после своего `event_timestamp`,
например из-за расхождения часов или задержки доставки, ни один запуск её не увидит.

### 5.3. `bool.should`: `match_phrase` ИЛИ `terms`

```json
"bool": { "should": [
  { "match_phrase": { "event_message": "This is a very important message" } },
  { "terms": { "log_level": ["WARN", "ERROR"] } } ],
  "minimum_should_match": 1 }
```

**Что делает.** Оставляет только записи, в которых есть фраза **или** уровень `WARN`/`ERROR`.

- `match_phrase` ищет слова фразы подряд и в заданном порядке по анализируемому `text`-полю
  [[док][d-match-phrase]]. Поэтому `event_message` в маппинге имеет тип `text`.
- `terms` ищет точное совпадение с одним из значений, с учётом регистра [[док][d-terms]]. Поэтому `log_level` —
  `keyword`, и генератор пишет уровни в верхнем регистре.
- `minimum_should_match: 1` требует совпадения хотя бы одного условия. Во вложенном `bool` без `must`/`filter`
  это и так значение по умолчанию [[док][d-bool]]; здесь оно задано явно для читаемости.

**Почему этот фильтр нужен, хотя флаги всё равно считаются derived-полями:**

1. **Нагрузка.** Обычные записи `TRACE`/`DEBUG`/`INFO` без фразы ни на что не влияют, но без фильтра для каждой
   из них выполнялись бы оба скрипта. Фильтр по индексированным полям сужает выборку до вычисления
   derived-полей, как и рекомендует документация [[док][d-derived-perf]]. **[проверено]** На объёме демо за
   2 минуты (1200 записей) под фильтр попадает 231 запись.
2. **Примеры в письме.** Примеры документов берутся из того же запроса (раздел 6.3), поэтому в письмо попадают
   только запись с фразой и записи `WARN`/`ERROR`, без шума.

**Почему условие «И» не написано прямо здесь.** Фраза и `WARN`/`ERROR` находятся в **разных** документах
одного процесса. Запрос проверяет каждый документ по отдельности и связать их не может. Связь «один и тот же
`process_id`» появляется только на уровне агрегации (раздел 6).

## 6. `aggregations` — группировка по процессу

### 6.1. `composite_agg`

```json
"composite_agg": { "composite": { "size": 1000,
  "sources": [ { "process_id": { "terms": { "field": "process_id" } } } ] } }
```

**Что делает.** Создаёт бакет на каждое значение `process_id`. Бакеты упорядочены по ключу и отдаются
страницами по `size` штук, следующая страница запрашивается через `after_key` [[док][d-composite]].

**Почему `composite`, а не `terms`.**
- Условие триггера требует, чтобы `parent_bucket_path` указывал на multi-bucket агрегацию
  [[док][d-trigger-extraction]].
- Плагин умеет переходить по страницам только у `composite`: `after_key` читается только у
  `CompositeAggregation` [[код][c-rewrite-after]]. В итоге проверяются **все** процессы окна, а не первые N.

**Почему имя именно `composite_agg`.** Примеры документов плагин достаёт из ответа по жёстко заданному ключу
`aggregations.composite_agg` [[код][c-sample-docs]]. При другом имени поиск отработает, но
`sample_documents` в письме будут пустыми. Это же имя используется в примере из документации
[[док][d-trigger-extraction]].

**Почему `size: 1000`.**
- `size` ограничивает число бакетов на странице; по умолчанию 10 [[док][d-composite]].
- `size` не может превышать `search.max_buckets`, иначе запрос падает с `TooManyBucketsException`
  [[код][c-composite-limit]]. По умолчанию `search.max_buckets` = 65535 [[док][d-search-settings]].
- Память под очередь бакетов агрегация учитывает в request circuit breaker [[код][c-composite-limit]]. Этот
  breaker ограничивает память на построение структур запроса, например агрегаций, и по умолчанию равен 60% heap
  [[док][d-breaker]].
- 1000 — компромисс. Окно демо целиком помещается в одну страницу (**[проверено]**: 104–113 бакетов на 2
  минуты демо-нагрузки), а память на страницу остаётся небольшой.

### 6.2. `important` и `problems` — `max`

```json
"important": { "max": { "field": "has_important_phrase" } },
"problems":  { "max": { "field": "is_warn_or_error" } }
```

**Что делает.** `max` возвращает максимальное значение поля в бакете [[док][d-max]]. Поля принимают значения
0/1, поэтому `max = 1` означает, что в процессе есть хотя бы одна такая запись. `max` считает в `double`, так что
в ответе будет `1.0`/`0.0` [[док][d-max]].

**Почему `max`, а не `sum` или `filter`.**
- `filter` ломает сбор примеров (раздел 4.2).
- `sum` дал бы количество, но условию нужен лишь факт наличия.
- Плагину подходит любая плоская метрика по полю [[код][c-sample-aggs]], а `max` прямо выражает «есть хотя бы
  одна».

### 6.3. Примеры документов для письма

Это не часть `monitor.json`: плагин сам добавляет их во втором поиске, и от них зависят письмо и нагрузка.

**Когда выполняется второй поиск.** Только если в запуске есть новые алерты **и** шаблон письма содержит
`{{ctx.newAlerts}}`, `{{ctx}}` или `sample_documents` [[код][c-prints-sample]], [[код][c-runner-sample]]. Наш
шаблон содержит `{{#ctx.newAlerts}}`. Доступ к примерам через `ctx.newAlerts.0.sample_documents` описан в
[[док][d-sample-docs]].

**Что добавляет плагин.** В каждый бакет — `top_hits` по 5 документов дважды, `top_hits` и `low_hits`.
Сортируются они по полям sub-агрегаций (у нас — `has_important_phrase`, `is_warn_or_error`): по убыванию и по
возрастанию [[код][c-sample-aggs]]. Затем дубли убираются [[код][c-sample-docs]]. Из `_source` забираются только
поля, которые шаблон выводит как `{{_source.<поле>}}` внутри блока `sample_documents`
[[код][c-parse-tags]], [[код][c-rewrite-fetch]]. У нас это `log_level`, `service_name`, `event_timestamp` и
`event_message`.

**Расхождение документации и поведения.**
- Документация говорит, что сортировка по derived-полям не поддерживается [[док][d-derived-limits]].
- **[проверено]** Ошибки нет, но и сортировки нет. Derived-тип отдаёт для сортировки field data своего
  базового типа [[код][c-derived-fielddata]], а в индексе такого поля физически нет. У всех документов
  значение сортировки равно `-9223372036854775808`, и порядок остаётся порядком документов в индексе.

**Следствие.** В письмо попадают первые и последние 5 документов бакета в порядке индекса.
- **В демо это ни на что не влияет.** У процесса, по которому приходит письмо, под фильтр попадают ровно 2
  записи: фраза и один `WARN`/`ERROR`. **[проверено]**
- **При другом профиле логов** это важно. Если у процесса больше 10 записей `WARN`/`ERROR`, запись с фразой
  может не попасть в письмо. **[проверено]** На процессе с 12 записями, где фраза седьмая, письмо показало
  записи 0–4 без фразы.
- **Алерт при этом срабатывает всегда.** Условие триггера считается по `max` всех записей процесса, а не по
  примерам (раздел 7, процесс D).
- **Все записи процесса доступны по ссылке.** В письме под `process_id` есть ссылка на Discover с запросом
  `process_id:"<id>"` и интервалом ±10 минут от `ctx.periodEnd`. Интервал задан через date math
  (`<время>||-10m`), поэтому он привязан ко времени алерта, а не ко времени открытия письма. **[проверено]**
  Ссылка из письма открывает в Dashboards 3.8.0 все записи процесса, включая запись с фразой. Исправить
  сортировку примеров без изменения схемы индекса нельзя; почему от изменения схемы отказались — в разделе
  [8.5](#85-оценка-для-прода-и-почему-не-ingest-pipeline).

**Только первая страница.** Второй поиск запрашивает только первую страницу composite-агрегации: при сборе
примеров `after` не подставляется [[код][c-rewrite-fetch]]. Если процессов в окне больше 1000, алерты со
следующих страниц останутся без примеров, а в лог попадёт `Failed to retrieve sample documents`
[[код][c-alert-context]]. **[проверено]** при нагрузочном прогоне (раздел 8.2).

## 7. Условие триггера

```json
"buckets_path": { "important": "important", "problems": "problems" },
"parent_bucket_path": "composite_agg",
"script": { "source": "params.important > 0 && params.problems > 0", "lang": "painless" }
```

- **`parent_bucket_path: "composite_agg"`** — путь к multi-bucket агрегации, бакеты которой проверяются
  [[док][d-trigger-extraction]].
- **`buckets_path`** связывает имена переменных скрипта с метриками бакета [[док][d-trigger-extraction]]. Здесь
  `params.important` — значение `max` из sub-агрегации `important`, `params.problems` — из `problems`.
- **`script`** — Painless-выражение, вычисляемое для каждого бакета. `true` значит «бакет сработал»
  [[док][d-trigger-extraction]], [[код][c-selector-reduce]]. Скрипт кэшируется как любой другой
  [[док][d-scripts-cache]]. **[проверено]** В контексте `aggregation_selector` за все запуски одна компиляция.
- **Пропуски значений.** Для отсутствующих значений по умолчанию действует политика `skip`
  [[код][c-gap-policy]]. Но у нас значение есть в каждом бакете: derived-поле вычисляется для каждого документа.

Итог: бакет (процесс) срабатывает только когда в нём есть **и** запись с фразой, **и** запись `WARN`/`ERROR`.
**[проверено]** на четырёх процессах:

| Процесс | Записи | Сработал |
|---|---|---|
| A | фраза + `WARN` | да |
| B | только `WARN`/`ERROR` | нет |
| C | только фраза | нет |
| D | фраза + 11 × `WARN` | да |

## 8. Нагрузка на OpenSearch: не положит ли запрос систему

### 8.1. Что происходит за один запуск

1. **Чтение текущих активных алертов монитора** из системного индекса Alerting, до 500 штук
   [[код][c-alerts-load]].
2. **Основной поиск** по `app-logs` — по разу на каждую страницу composite-агрегации [[код][c-runner-loop]].
   Поиск идёт с `preference=_primary_first` [[код][c-input-render]].
3. **Поиск примеров документов** (раздел 6.3) — только если есть новые алерты.
4. **Запись алертов** в системный индекс и отправка писем.

Findings для этого монитора не создаются: `data_sources.findings_enabled` по умолчанию `false`
[[код][c-findings-default]], а дополнительный поиск findings выполняется только при `true`
[[код][c-runner-findings]].

### 8.2. Замеры

Профиль и прогоны на контейнере `opensearch:3.8.0` (1 узел, heap 512 МБ, 1 шард). Прогоны через
`_execute?dryrun=true` выполняют все шаги, кроме записи алертов и отправки писем. **[проверено]**

| Сценарий | Записей в 2-минутном окне | Прошло фильтр | Бакетов | Сработало | Время |
|---|---|---|---|---|---|
| Нагрузка демо (10 записей/с) | 1 200 | 231 | 113 | 4 | поиск `took` 8 мс; dry-run целиком 30–40 мс |
| Стресс (≈1 700 записей/с) | 201 200 | 42 020 | > 1 000 (несколько страниц) | 1 729 | одна страница `took` 15–130 мс; dry-run целиком 1,6–1,8 с |

**Circuit breakers.** Ни один не сработал ни в одном прогоне (`tripped: 0` в `GET _nodes/stats/breaker`).

**Компиляции скриптов.**
- `derived_field` — 2 за всё время, `aggregation_selector` — 1.
- `template` (mustache): около 3 новых компиляций за запуск на нагрузке демо и около 9 на стрессе.
- `compilation_limit_triggered: 0`.

### 8.3. Что защищает кластер

| Механизм | Значение по умолчанию | Что ограничивает | Источник |
|---|---|---|---|
| `size` composite-агрегации | 1000 (наше значение) | бакетов и памяти на одну страницу | [[док][d-composite]], [[код][c-composite-limit]] |
| `search.max_buckets` | 65535 | `size` composite-агрегации не может его превышать | [[док][d-search-settings]], [[код][c-composite-limit]] |
| Request circuit breaker | 60% heap | память на агрегации запроса | [[док][d-breaker]] |
| Кэш скриптов | 100 скриптов на контекст | повторной компиляции одинаковых скриптов нет | [[док][d-script-settings]] |
| Лимит компиляций | 75 за 5 минут на контекст | частоту компиляции **новых** скриптов; при превышении — `circuit_breaking_exception` | [[док][d-script-settings]], [[код][c-script-rate]] |
| `plugins.alerting.max_actionable_alert_count` | 50 | число отдельных писем за запуск (см. ниже) | [[код][c-max-actionable]], [[код][c-per-exec]] |

**Чего нет по умолчанию — таймаута поиска.**
- `search.cancel_after_time_interval` и `search.default_search_timeout` по умолчанию равны `-1`, то есть
  таймаута нет [[док][d-search-settings]].
- Alerting берёт отмену поиска из `search.cancel_after_time_interval`, причём не меньше 5 минут
  [[код][c-cancel]]. При `-1` отмены нет вовсе.
- Документация описывает `plugins.alerting.input_timeout` (30 с) как время, за которое монитор должен выполнить
  поиск [[док][d-alerting-settings]]. **[код]** В плагине 3.8.0.0 настройка только регистрируется
  [[код][c-input-timeout]], а при выполнении поиска монитора нигде не используется: поиск по исходникам находит
  лишь регистрацию.
- Итог: сам по себе медленный запрос монитора не прервётся.

### 8.4. Вывод

**Для стенда — нет, запрос систему не положит.**
- Раз в минуту выполняется один поиск примерно на 1 200 записей окна. Скрипты работают только на ~230
  отфильтрованных записях, ответ укладывается в одну страницу, весь запуск занимает десятки миллисекунд.
- Затраты зависят от числа записей в **2-минутном окне**, а не от размера индекса. Индекс копится без ротации,
  но фильтр по `event_timestamp` отсекает старые записи до вычисления скриптов.

**На больших объёмах риски такие** (в порядке важности). Они наступают, когда в окне много записей
`WARN`/`ERROR` или с фразой, как в стресс-прогоне; при ожидаемом профиле прода — нет (8.5):

1. **Скрипты на каждую отфильтрованную запись.** Стоимость derived-полей растёт линейно с числом записей
   `WARN`/`ERROR` и записей с фразой в окне [[док][d-derived-perf]]. Таймаута по умолчанию нет (8.3).
   Для продакшена стоит задать `search.cancel_after_time_interval`, чтобы зависший запрос монитора не
   держал ресурсы бесконечно.
2. **Одно большое письмо вместо отдельных.** Если за запуск у триггера больше 50 алертов (`NEW` + `DEDUPED` +
   `COMPLETED`), плагин вместо `per_alert` отправляет **одно** сообщение со всеми алертами сразу
   [[код][c-per-exec]], [[код][c-runner-sample]]. **[проверено]** При 1 729 алертах сформировалось одно
   письмо размером 158 КБ со всеми процессами.
3. **Нет примеров документов за пределами первых 1000 процессов** (раздел 6.3).
4. **Не больше 500 активных алертов за раз.** Плагин читает не более 500 активных алертов монитора
   [[код][c-alerts-load]]. Если активных больше, алерты сверх 500 не находятся среди текущих: возможны повторные
   `NEW` и повторные письма. Это следует из кода и на стенде не воспроизводилось.
5. **Монитор зависит от `search.allow_expensive_queries`** (раздел 4.4). Если администратор выключит её ради
   защиты кластера, монитор перестанет работать, хотя сам OpenSearch продолжит работать.
6. **Компиляции mustache-шаблонов.** На стрессе их около 9 за запуск, то есть около 45 за 5 минут при лимите 75.
   Почему число растёт с объёмом, плагин не документирует. При дальнейшем росте возможен
   `circuit_breaking_exception` в контексте `template`; это видно по `compilation_limit_triggered` в
   `GET _nodes/stats/script_cache` [[док][d-scripts-cache]].

### 8.5. Оценка для прода и почему не ingest pipeline

**Ожидаемый профиль прода.** Логов больше 1000 в секунду. `WARN` около 1000 в час, `ERROR` около 100 в час.
Записей с фразой — десятки в сутки.

**Оценка.** Цифры ниже посчитаны из профиля, на такой нагрузке монитор отдельно не прогонялся.

| | За 2-минутное окно |
|---|---|
| Все логи | 120 000 и больше |
| `WARN` + `ERROR` | около 37 |
| Записи с фразой | почти всегда 0, изредка 1 |
| Прошло фильтр (5.3) | около 40 |
| Бакетов (процессов) | не больше ~40, одна страница |
| Новых алертов | единицы за запуск в худшем случае, десятки в сутки |

Что из этого следует:

- **Скрипты почти не работают.** Derived-поля вычисляются только для записей, прошедших фильтр по
  индексированным полям [[док][d-derived-perf]]. Это около 40 записей за запуск, и число не зависит от общего
  потока логов. Остальные записи окна отсекаются без скриптов. Для сравнения: на нагрузке демо фильтр проходила
  231 запись, и поиск занимал 8 мс (8.2).
- **Ограничения плагина не наступают.**
  - Бакеты помещаются в одну страницу composite-агрегации, поэтому примеры документов есть у всех писем (6.3).
  - Порог в 50 алертов за запуск недостижим, и каждый процесс получает отдельное письмо: `per_alert`
    работает без перехода на сводку (8.3). Менять `plugins.alerting.max_actionable_alert_count` не нужно.
  - Лимит в 500 активных алертов тоже недостижим (8.4, п. 4).
- **Что растёт со временем.** Фильтр `terms` по `log_level` и `match_phrase` работают по индексу целиком, а
  не только по окну. Их стоимость растёт с объёмом хранимых логов, а не с потоком. Это самые дешёвые
  операции поиска, но индекс без ротации всё равно стоит ограничивать, например ежедневной ротацией через
  ISM. Это тоже оценка, а не замер.

**Рассмотренная альтернатива: флаги при записи.** Флаги `is_important` и `is_problem` можно проставлять при
индексации через ingest pipeline ([[док][d-ingest]], [[док][d-script-proc]]). Тогда это были бы настоящие поля,
сортировка примеров работала бы, и запись с фразой всегда попадала бы в письмо. Монитору не понадобились бы
скрипты и `search.allow_expensive_queries`. Вариант проверили на `opensearch:3.8.0` и **отказались** от него.
**[проверено]**

- **Скрипт выполнялся бы на каждой записи.** При 1000+ записях в секунду это 3,6 млн выполнений в час. Цель —
  сэкономить около 40 вычислений в минуту на стороне монитора.
- **Запись замедлилась.** 200 000 документов по 10 000 в bulk-запросе записывались на 20–35% дольше: 2,8–3,0 с
  против 2,1–2,5 с (1 узел, heap 512 МБ). Сама документация советует ingest pipelines для простой предобработки
  и небольших объёмов, а для больших — внешний Data Prepper [[док][d-ingest]].
- **Ошибка pipeline отклоняет документ** [[док][d-pipeline-failures]]. Первый вариант скрипта падал на
  `event_message` числом или объектом и на `log_level` массивом. Такие логи не записались бы вовсе.
- **`index.default_pipeline` легко обойти.** Он не выполняется, если в запросе указан другой pipeline или
  `?pipeline=_none`. Надёжен только `index.final_pipeline` [[док][d-index-settings]].
- **Удаление pipeline останавливает запись.** Пока индекс ссылается на несуществующий pipeline, все записи
  отклоняются [[док][d-index-settings]].

При профиле прода монитор и так дешёвый, а ingest pipeline добавил бы нагрузку на каждую запись и новые точки
отказа. Если флаги когда-нибудь понадобятся, дешевле проставлять их в отправителе логов: на OpenSearch это
нагрузки не даёт.

### 8.6. Расхождения фильтра и derived-поля

Фильтр (`match_phrase` по `text`) и флаг (`contains` по `keyword`) проверяют фразу по-разному. Возможны случаи,
когда запись прошла фильтр, но `has_important_phrase = 0`:

- **Длинные сообщения.** Сообщение длиннее 1024 символов не попадает в `event_message.keyword`
  [[док][d-keyword-params]].
- **Разные разделители.** `match_phrase` сравнивает токены анализатора [[док][d-match-phrase]], поэтому
  считает совпадением фразу с другими разделителями между словами (несколько пробелов, перенос строки).
  `contains` — нет.

Такие расхождения дают только **пропуск** алерта, но не ложное срабатывание. Генератор демо всегда пишет
фразу ровно и коротко, так что на стенде их не бывает.

## 9. Вариант для Platform V Search

В плагине Alerting для Platform V Search (PVS) есть два ограничения, из-за которых `monitor.json` туда не
переносится как есть:

- нельзя создать per bucket monitor, у которого в `message` используется тег `bucket_keys`;
- содержимое `_source` нельзя отправить по каналам Email и Webhook. Триггер по `_source` настраивать можно.

Наш шаблон письма нарушает оба: `process_id` берётся из `{{bucket_keys}}` (в тексте и в ссылке), а поля
документов — из `{{_source.<поле>}}` внутри `sample_documents`.

Для PVS есть отдельный файл [opensearch/init/monitor-pvs.json](opensearch/init/monitor-pvs.json). `init.sh` его
не применяет: он только для переноса. Отличия от `monitor.json`:

- **Имя** — `important-message-monitor-pvs`, чтобы при создании рядом не конфликтовать с основным монитором.
- **Запрос и триггер не изменились.** Ограничения касаются только текста уведомления.
- **В письме нет `process_id` и полей документов.** Для каждого процесса выводятся только `_index/_id` примеров
  из `sample_documents` (раздел [6.3](#63-примеры-документов-для-письма)).
- **Ссылка на Discover ведёт к примерам по `_id`.** Запрос на языке Lucene `_id:("<id1>" "<id2>" ...)`: в
  Lucene термы через пробел объединяются по ИЛИ, поэтому разделитель после последнего id не мешает. Кавычки
  нужны, потому что автоматический `_id` может начинаться с `-`, а это оператор NOT. В Discover видны
  `process_id`, уровень и текст записей. Чтобы увидеть все записи процесса, щёлкните по `process_id` и добавьте
  фильтр.

**[проверено]** На стенде (OpenSearch 3.8.0): dry-run монитора выдаёт в письме два `_id` (запись с фразой и
`WARN`), а ссылка из письма открывает в Dashboards 3.8.0 ровно эти две записи с их `process_id`.

**Чего этот вариант не гарантирует.**
- **Неполные примеры.** В ссылку попадают только примеры, то есть до 10 записей процесса, а не все. Если у
  процесса больше 10 записей `WARN`/`ERROR`, записи с фразой среди них может не быть (раздел 6.3). Алерт при
  этом всё равно срабатывает.
- **Поведение самого PVS не проверено.** Неизвестно, отдаёт ли PVS `_id`/`_index` из `sample_documents` в
  уведомлении и отклоняет ли он монитор при создании или вырезает данные при отправке. Это нужно проверить
  на стенде PVS.
- **Хост в ссылке.** Адрес `http://localhost:5601` нужно заменить на адрес Dashboards в PVS.

## 10. Как проверить самостоятельно

На поднятом стенде (`docker compose up -d`):

```bash
# 1. Прогон монитора без отправки писем: результат запроса, сработавшие бакеты, текст письма
curl -s -X POST 'localhost:9200/_plugins/_alerting/monitors/_execute?dryrun=true' \
  -H 'Content-Type: application/json' -d @opensearch/init/monitor.json
# то же для варианта под Platform V Search (раздел 9)
curl -s -X POST 'localhost:9200/_plugins/_alerting/monitors/_execute?dryrun=true' \
  -H 'Content-Type: application/json' -d @opensearch/init/monitor-pvs.json

# 2. Профиль самого поиска: подставьте вместо {{period_end}} текущее время в миллисекундах
NOW=$(($(date +%s) * 1000))
jq --arg now "$NOW" '.inputs[0].search.query | walk(if type == "string" then gsub("{{period_end}}"; $now) else . end) | .profile = true' \
  opensearch/init/monitor.json > /tmp/monitor-query.json
curl -s 'localhost:9200/app-logs/_search' -H 'Content-Type: application/json' -d @/tmp/monitor-query.json

# 3. Кэш скриптов и circuit breakers
curl -s 'localhost:9200/_nodes/stats/script_cache,breaker?pretty'
```

Вывод `profile` описан в [[док][d-profile]], статистика скриптов — в [[док][d-scripts-cache]].

## 11. Источники

**Документация OpenSearch 3.8**

- [Derived field type][d-derived]: [Current limitations][d-derived-limits], [Prerequisites][d-derived-prereq],
  [Parameters][d-derived-params], [Aggregations][d-derived-aggs], [Performance][d-derived-perf]
- [Composite aggregation][d-composite], [Maximum aggregation][d-max]
- [Boolean query][d-bool], [Query and filter context][d-filter-context], [Match phrase query][d-match-phrase],
  [Terms query][d-terms], [Range query: relative dates][d-range-relative], [Range query: parameters][d-range-params]
- [Keyword field type: parameters][d-keyword-params]
- [Alerting monitors: типы][d-monitors-types], [Monitor variables][d-monitor-vars],
  [Per query and per bucket monitors][d-bucket-create], [period_start/period_end][d-bucket-period],
  [Alerting triggers: extraction query editor][d-trigger-extraction], [sample_documents][d-sample-docs],
  [Alerting API][d-api], [Alerting settings][d-alerting-settings]
- [Search settings][d-search-settings], [Circuit breaker settings][d-breaker],
  [Script and resource settings][d-script-settings], [Compilation limits and caching][d-scripts-cache],
  [Profile API][d-profile]
- [Ingest pipelines][d-ingest], [Script processor][d-script-proc],
  [Pipeline failures][d-pipeline-failures], [Index settings][d-index-settings]

**Исходный код**

- alerting `3.8.0.0`: `AggregationQueryRewriter.kt`, `BucketLevelMonitorRunner.kt`, `InputService.kt`,
  `AlertingUtils.kt`, `AlertService.kt`, `AlertingSettings.kt`
- common-utils `3.8.0.0`: `BucketSelectorExtAggregator.kt`, `BucketSelectorExtAggregationBuilder.kt`,
  `DataSources.kt`
- OpenSearch `3.8.0`: `CompositeAggregator.java`, `ValuesSourceConfig.java`, `DerivedFieldType.java`,
  `DerivedFieldResolverFactory.java`, `RangeQueryBuilder.java`, `ScriptContext.java`

Ссылки на конкретные строки — в тексте выше.

[d-derived]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/
[d-derived-limits]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/#current-limitations
[d-derived-prereq]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/#prerequisites
[d-derived-params]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/#parameters
[d-derived-aggs]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/#aggregations
[d-derived-perf]: https://docs.opensearch.org/3.8/mappings/supported-field-types/derived/#performance
[d-composite]: https://docs.opensearch.org/3.8/aggregations/bucket/composite/#parameters
[d-max]: https://docs.opensearch.org/3.8/aggregations/metric/maximum/
[d-bool]: https://docs.opensearch.org/3.8/query-dsl/compound/bool/
[d-filter-context]: https://docs.opensearch.org/3.8/query-dsl/query-filter-context/#filter-context
[d-match-phrase]: https://docs.opensearch.org/3.8/query-dsl/full-text/match-phrase/
[d-terms]: https://docs.opensearch.org/3.8/query-dsl/term/terms/
[d-range-relative]: https://docs.opensearch.org/3.8/query-dsl/term/range/#relative-dates
[d-range-params]: https://docs.opensearch.org/3.8/query-dsl/term/range/#parameters
[d-keyword-params]: https://docs.opensearch.org/3.8/mappings/supported-field-types/keyword/#parameters
[d-monitors-types]: https://docs.opensearch.org/3.8/observing-your-data/alerting/monitors/
[d-monitor-vars]: https://docs.opensearch.org/3.8/observing-your-data/alerting/monitors/#monitor-variables
[d-bucket-create]: https://docs.opensearch.org/3.8/observing-your-data/alerting/per-query-bucket-monitors/#creating-a-per-query-or-per-bucket-monitor
[d-bucket-period]: https://docs.opensearch.org/3.8/observing-your-data/alerting/per-query-bucket-monitors/#creating-a-per-query-or-per-bucket-monitor
[d-trigger-extraction]: https://docs.opensearch.org/3.8/observing-your-data/alerting/triggers/#extraction-query-editor
[d-sample-docs]: https://docs.opensearch.org/3.8/observing-your-data/alerting/triggers/#the-associated_queries-and-sample_documents-variables
[d-api]: https://docs.opensearch.org/3.8/observing-your-data/alerting/api/
[d-alerting-settings]: https://docs.opensearch.org/3.8/observing-your-data/alerting/settings/#alerting-settings
[d-search-settings]: https://docs.opensearch.org/3.8/install-and-configure/configuring-opensearch/search-settings/
[d-breaker]: https://docs.opensearch.org/3.8/install-and-configure/configuring-opensearch/circuit-breaker/#request-circuit-breaker-settings
[d-script-settings]: https://docs.opensearch.org/3.8/install-and-configure/configuring-opensearch/script-and-resource-settings/#script-context-settings
[d-scripts-cache]: https://docs.opensearch.org/3.8/scripting/using-scripts/#compilation-limits-and-caching
[d-profile]: https://docs.opensearch.org/3.8/api-reference/search-apis/profile/
[d-ingest]: https://docs.opensearch.org/3.8/ingest-pipelines/
[d-script-proc]: https://docs.opensearch.org/3.8/ingest-pipelines/processors/script/
[d-pipeline-failures]: https://docs.opensearch.org/3.8/ingest-pipelines/pipeline-failures/
[d-index-settings]: https://docs.opensearch.org/3.8/install-and-configure/configuring-opensearch/index-settings/

[c-rewrite-selector]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AggregationQueryRewriter.kt#L47-L52
[c-rewrite-after]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AggregationQueryRewriter.kt#L108-L151
[c-rewrite-fetch]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AggregationQueryRewriter.kt#L73-L94
[c-sample-aggs]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AggregationQueryRewriter.kt#L153-L171
[c-runner-loop]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/BucketLevelMonitorRunner.kt#L126-L257
[c-runner-findings]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/BucketLevelMonitorRunner.kt#L189-L203
[c-runner-sample]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/BucketLevelMonitorRunner.kt#L272-L296
[c-per-exec]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AlertingUtils.kt#L152-L183
[c-sample-docs]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/BucketLevelMonitorRunner.kt#L613-L667
[c-alert-context]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/BucketLevelMonitorRunner.kt#L592-L611
[c-input-render]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/InputService.kt#L328-L399
[c-prints-sample]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AlertingUtils.kt#L286-L311
[c-parse-tags]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AlertingUtils.kt#L241-L278
[c-cancel]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/util/AlertingUtils.kt#L207-L215
[c-max-actionable]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/settings/AlertingSettings.kt#L24
[c-input-timeout]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/settings/AlertingSettings.kt#L108-L112
[c-alerts-load]: https://github.com/opensearch-project/alerting/blob/3.8.0.0/alerting/src/main/kotlin/org/opensearch/alerting/AlertService.kt#L140-L150
[c-selector-reduce]: https://github.com/opensearch-project/common-utils/blob/3.8.0.0/src/main/kotlin/org/opensearch/commons/alerting/aggregation/bucketselectorext/BucketSelectorExtAggregator.kt#L60-L116
[c-gap-policy]: https://github.com/opensearch-project/common-utils/blob/3.8.0.0/src/main/kotlin/org/opensearch/commons/alerting/aggregation/bucketselectorext/BucketSelectorExtAggregationBuilder.kt#L27
[c-findings-default]: https://github.com/opensearch-project/common-utils/blob/3.8.0.0/src/main/kotlin/org/opensearch/commons/alerting/model/DataSources.kt#L45
[c-composite-limit]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/search/aggregations/bucket/composite/CompositeAggregator.java#L139-L181
[c-vsc-derived]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/search/aggregations/support/ValuesSourceConfig.java#L188-L191
[c-derived-expensive]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/index/mapper/DerivedFieldResolverFactory.java#L43-L47
[c-derived-fielddata]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/index/mapper/DerivedFieldType.java#L154-L157
[c-range-from]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/index/query/RangeQueryBuilder.java#L340-L346
[c-script-rate]: https://github.com/opensearch-project/OpenSearch/blob/3.8.0/server/src/main/java/org/opensearch/script/ScriptContext.java#L146-L149
