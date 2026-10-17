# Ручная настройка дашборда и алертинга в OpenSearch Dashboards

Инструкция повторяет вручную, через UI OpenSearch Dashboards 3.8.0, то, что в этом проекте автоматически делает
`opensearch-init` ([opensearch/init/init.sh](opensearch/init/init.sh)). В итоге получится:

1. index pattern `app-logs*`;
2. сохранённые поиски «All logs» и «Important messages»;
3. пять визуализаций;
4. дашборд **Logs monitoring** с автообновлением раз в 5 секунд;
5. SMTP-отправитель и email-канал в плагине Notifications (почта уходит в MailHog);
6. монитор Alerting, который присылает письмо, когда в `event_message` появляется фраза
   **"This is a very important message"**.

Названия пунктов меню и кнопок даны так, как они выглядят в интерфейсе (на английском).

---

## Содержание

- [0. Подготовка](#0-подготовка)
- [1. Index pattern](#1-index-pattern)
- [2. Сохранённые поиски в Discover](#2-сохранённые-поиски-в-discover)
- [3. Визуализации](#3-визуализации)
- [4. Дашборд](#4-дашборд)
- [5. Канал уведомлений (Notifications)](#5-канал-уведомлений-notifications)
- [6. Монитор (Alerting)](#6-монитор-alerting)
- [7. Проверка всей цепочки](#7-проверка-всей-цепочки)
- [8. Частые ошибки](#8-частые-ошибки)
- [9. Сохранение результата в репозиторий](#9-сохранение-результата-в-репозиторий)

---

## 0. Подготовка

### 0.1. Поднять стенд без автоматической настройки

Чтобы настраивать всё с нуля, автоматическая настройка не должна успеть создать объекты. Поднимите только
инфраструктуру:

```bash
docker compose down -v                                        # удалить прежние данные (если были)
docker compose up -d opensearch opensearch-dashboards mailhog
```

Подождите 1–2 минуты, пока OpenSearch Dashboards станет доступен: http://localhost:5601.

### 0.2. Создать индекс с правильным маппингом

Индекс и его маппинг — это не настройка UI, но без них поиск фразы работать не будет. Поле `event_message`
должно иметь тип `text`, тогда фраза находится внутри длинного сообщения.

1. Откройте меню (☰ слева вверху) → **Management** → **Dev Tools**.
2. Вставьте в консоль и выполните (кнопка ▶ справа от запроса или `Ctrl/Cmd + Enter`):

   ```
   PUT _index_template/app-logs
   {
     "index_patterns": ["app-logs*"],
     "priority": 100,
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

3. Затем создайте сам индекс. Монитору (раздел 6) нужен уже существующий индекс:

   ```
   PUT app-logs
   ```

### 0.3. Запустить поток логов

```bash
docker compose up -d --no-deps log-sender     # генератор: 10 сообщений в секунду
# или вручную:
scripts/send-logs.sh --count 3
scripts/send-logs.sh --important
```

> Если запустить `docker compose up -d` целиком, контейнер `opensearch-init` создаст все объекты
> автоматически. Для ручной настройки используйте команды выше, с `--no-deps`.

### 0.4. Всплывающее окно при первом входе

При первом открытии Dashboards может появиться окно **"New! Enhanced Discover experience available"**.
Нажмите **Dismiss**.

---

## 1. Index pattern

Index pattern говорит Dashboards, из каких индексов брать данные и какое поле считать временем.

1. Меню ☰ → **Management** → **Dashboards Management**.
2. Слева выберите **Index patterns** → кнопка **Create index pattern**.
3. **Step 1 of 2: Define an index pattern**
   - **Index pattern name**: `app-logs*`
   - Ниже должно появиться *"Your index pattern can match your 1 source"* и индекс `app-logs`. Если
     совпадений нет, значит индекс не создан (см. шаг 0.2).
   - Нажмите **Next step**.
4. **Step 2 of 2: Configure settings**
   - **Time field**: выберите `event_timestamp`.
   - Нажмите **Create index pattern**.
5. Откроется страница index pattern со списком полей. Проверьте типы:
   - `event_timestamp` — date;
   - `event_message` — string (text), `event_message.keyword` — string (keyword);
   - `process_id`, `service_name` — string (keyword).
6. *(Необязательно)* Сделайте pattern основным: нажмите ☆ (**Set as default index**) справа вверху.

---

## 2. Сохранённые поиски в Discover

### 2.1. «All logs»

1. Меню ☰ → **OpenSearch Dashboards** → **Discover**.
2. Слева вверху выберите index pattern **app-logs\***.
3. В выборе времени справа вверху поставьте **Last 15 minutes**. Если документов нет, проверьте,
   что отправка логов запущена (шаг 0.3).
4. Добавьте колонки. В списке полей слева наведите курсор на поле и нажмите кнопку добавления (иконка **+**).
   Добавьте по очереди:
   1. `service_name`
   2. `process_id`
   3. `event_message`
5. Убедитесь, что сортировка идёт по `event_timestamp` от новых к старым (она стоит по умолчанию).
6. В верхнем меню нажмите **Save** → **Title**: `All logs` → **Save**.

### 2.2. «Important messages»

1. Оставаясь в Discover, введите в строке поиска (язык **DQL**, переключатель справа от строки) запрос:

   ```
   event_message:"This is a very important message"
   ```

   Кавычки обязательны: это поиск фразы, слова должны идти подряд. Нажмите **Refresh** или Enter.
2. Убедитесь, что найдены только сообщения с фразой (примерно одно в 10 секунд, если работает `log-sender`).
3. **Save** → включите переключатель **Save as new search**, иначе вы перезапишете «All logs» →
   **Title**: `Important messages` → **Save**.

> Почему `event_message`, а не `event_message.keyword`: keyword-поле совпадает только с полным значением,
> а фраза — лишь часть сообщения. Поиск по `text` к тому же не зависит от регистра и пунктуации.

---

## 3. Визуализации

### 3.0. Как устроен редактор визуализации

После выбора типа и источника откроется редактор. Слева график, справа панель настроек:

```
┌──────────────────────────────────────┬───────────────────────────────┐
│                                      │ app-logs*            (источник)│
│                                      │ [Data] [Metrics & axes] [Panel │
│                                      │         settings]   ← вкладки  │
│             область графика          │ ┌ Metrics ─────────────────┐   │
│                                      │ │ > Y-axis Count           │   │
│                                      │ │        (+) Add           │   │
│                                      │ └──────────────────────────┘   │
│                                      │ ┌ Buckets ─────────────────┐   │
│                                      │ │        (+) Add           │   │
│                                      │ └──────────────────────────┘   │
│                                      │          ✕ Discard  [Update]   │
└──────────────────────────────────────┴───────────────────────────────┘
```

- **Metrics** — что считаем: значение по оси Y, размер сектора или число в ячейке. Нам везде нужно количество
  документов (**Count**), а оно уже стоит по умолчанию.
- **Buckets** — как группируем документы: по времени (**Date Histogram**) или по значению поля (**Terms**).
  По умолчанию бакетов нет, их добавляют кнопкой **Add** в блоке Buckets.
- Каждая строка метрики или бакета — сворачиваемый блок. Клик по заголовку (например, **> Y-axis Count**)
  раскрывает его настройки.
- Изменения применяются только после кнопки **Update** справа внизу. Пока изменений нет, она серая.
  **Discard** отменяет неприменённые изменения.
- Вкладки зависят от типа: у Vertical Bar и Line — **Data / Metrics & axes / Panel settings**, у Metric,
  Pie и Data Table — **Data / Options**.
- Сохранение: кнопка **Save** справа вверху над графиком → окно **Save visualization** → поле **Title**
  (и необязательное **Description**) → **Save**.

Поля **Aggregation**, **Sub aggregation** и **Field** — выпадающие списки с поиском: кликните по полю,
начните вводить название и выберите вариант из списка.

### 3.0.1. Как начать каждую визуализацию

1. Меню ☰ → **OpenSearch Dashboards** → **Visualize**.
2. Нажмите **Create visualization**. Если визуализации уже есть, кнопка находится справа над списком.
3. В окне **New Visualization** кликните по нужному типу.
4. Откроется окно **New &lt;Тип&gt; / Choose a source** со списком источников:
   - `app-logs*` — index pattern, то есть все логи;
   - `All logs`, `Important messages` — сохранённые поиски из раздела 2.

   Кликните по нужному источнику. Если выбран сохранённый поиск, визуализация считает только найденные им
   документы.
5. В выборе времени над графиком должно стоять **Last 15 minutes**. Если график пустой, расширьте период.

---

### 3.1. «Important messages (count)» — Metric

Большое число: сколько важных сообщений за выбранный период.

1. **Create visualization** → **Metric** → источник **Important messages**.
2. Вкладка **Data** → блок **Metrics**: кликните по строке **> Metric Count**, чтобы раскрыть её.
   - **Aggregation**: оставьте **Count**.
   - **Custom label**: введите `Important messages`. Эта подпись будет под числом.
3. Блок **Buckets** не трогайте: нужно одно общее число без группировки.
4. Нажмите **Update**. В области графика появится число.
5. *(Необязательно)* Вкладка **Options** → **Style** → **Metric font size in points**: размер шрифта
   (по умолчанию 60).
6. **Save** → **Title**: `Important messages (count)` → **Save**.

### 3.2. «Important messages over time» — Vertical Bar

Столбики: сколько важных сообщений было в каждом интервале времени.

1. **Create visualization** → **Vertical Bar** → источник **Important messages**.
2. Блок **Metrics**: строка **Y-axis Count** уже есть, менять не нужно.
3. Блок **Buckets** → **Add** → в меню **Add bucket** выберите **X-axis**.
4. В появившемся блоке **X-axis**:
   - **Aggregation**: выберите **Date Histogram**.
   - **Field**: подставится `event_timestamp` (единственное поле даты). Если нет, выберите его.
   - **Minimum interval**: оставьте **Auto**. Dashboards сам подберёт шаг под период: для 15 минут это
     30 секунд. Можно выбрать или ввести своё значение, например `10s` или `1m`.
   - **Drop partial buckets**: выключено.
5. Нажмите **Update**. По оси X — время, по оси Y — число важных сообщений.
6. **Save** → **Title**: `Important messages over time` → **Save**.

### 3.3. «Log volume by service» — Line

Линии: объём логов во времени, отдельная линия для каждого сервиса.

1. **Create visualization** → **Line** → источник **app-logs\*** (все логи, не сохранённый поиск).
2. Блок **Metrics**: **Y-axis Count** оставьте как есть.
3. Первый бакет — ось времени:
   1. **Buckets** → **Add** → **X-axis**.
   2. **Aggregation**: **Date Histogram**.
   3. **Field**: `event_timestamp` (подставится сам).
   4. **Minimum interval**: **Auto**.
4. Второй бакет — разбивка на линии по сервисам:
   1. Снова **Buckets** → **Add** → **Split series**.
   2. **Sub aggregation**: **Terms**.
   3. **Field**: `service_name`.
   4. **Order by**: **Metric: Count**.
   5. **Order**: **Descending**.
   6. **Size**: замените `5` на `10`. Это максимальное число линий; сервисов 5, плюс `manual-script`
      от ручного скрипта.
   7. **Group other values in separate bucket** и **Show missing values** оставьте выключенными.
5. Нажмите **Update**. Появятся цветные линии с легендой по сервисам.
6. *(Необязательно)* Вкладка **Metrics & axes** → **Line mode**: **Smoothed** для сглаженных линий.
7. **Save** → **Title**: `Log volume by service` → **Save**.

> Порядок бакетов важен: сначала **X-axis**, затем **Split series**. Если порядок другой, перетащите блок
> за значок **═** в его заголовке.

### 3.4. «Logs by service» — Pie

Кольцевая диаграмма: доля логов каждого сервиса.

1. **Create visualization** → **Pie** → источник **app-logs\***.
2. Блок **Metrics**: **Slice size Count** оставьте.
3. **Buckets** → **Add** → **Split slices**.
   - **Aggregation**: **Terms**.
   - **Field**: `service_name`.
   - **Order by**: **Metric: Count**, **Order**: **Descending**.
   - **Size**: `10`.
4. Нажмите **Update**.
5. Вкладка **Options**:
   - **Pie settings** → **Donut**: включено (кольцо); если выключить, будет обычный круг.
   - **Legend position**: **Right**.
   - **Show tooltip**: включено.
   - **Labels settings** → **Show labels**: по желанию (подписи на секторах).
   - Нажмите **Update**, если что-то меняли.
6. **Save** → **Title**: `Logs by service` → **Save**.

### 3.5. «Important messages by service» — Data Table

Таблица: сколько важных сообщений пришло от каждого сервиса, с итоговой строкой.

1. **Create visualization** → **Data Table** → источник **Important messages**.
2. Блок **Metrics**: раскройте **> Metric Count** → **Custom label**: `Important messages`. Это заголовок
   колонки с числами.
3. **Buckets** → **Add** → **Split rows**.
   - **Aggregation**: **Terms**.
   - **Field**: `service_name`.
   - **Order by**: **Metric: Count**, **Order**: **Descending**.
   - **Size**: `10`.
4. Нажмите **Update**. Появится таблица: колонка `service_name` и колонка `Important messages`.
5. Вкладка **Options**:
   - **Max rows per page**: `10`.
   - **Show total**: включить.
   - **Total function**: **Sum**. Внизу таблицы появится сумма.
   - Нажмите **Update**.
6. **Save** → **Title**: `Important messages by service` → **Save**.

### 3.6. Итог

В **Visualize** должно быть пять визуализаций:

| Title | Тип | Источник | Metrics | Buckets |
|---|---|---|---|---|
| Important messages (count) | Metric | Important messages | Count, label «Important messages» | — |
| Important messages over time | Vertical Bar | Important messages | Count | X-axis: Date Histogram `event_timestamp` |
| Log volume by service | Line | app-logs\* | Count | X-axis: Date Histogram `event_timestamp`; Split series: Terms `service_name`, size 10 |
| Logs by service | Pie | app-logs\* | Count | Split slices: Terms `service_name`, size 10 |
| Important messages by service | Data Table | Important messages | Count, label «Important messages» | Split rows: Terms `service_name`, size 10; Options: Show total = Sum |


---

## 4. Дашборд

### 4.1. Создание и панели

1. Меню ☰ → **OpenSearch Dashboards** → **Dashboards**.
2. Нажмите **Create** → **Dashboard** (на пустом списке — **Create new dashboard**).
3. В верхнем меню нажмите **Add**. Откроется панель **Add panels** со всеми сохранёнными объектами.
4. Кликните по очереди (каждый клик добавляет панель на дашборд):
   1. `Important messages (count)`
   2. `Important messages over time`
   3. `Log volume by service`
   4. `Logs by service`
   5. `Important messages by service`
   6. `Important messages`. Это сохранённый поиск; его можно найти фильтром **Types → Saved search**.
      На дашборде он будет таблицей важных сообщений с `process_id`.
5. Закройте панель **Add panels** (✕).

### 4.2. Раскладка

Панели перетаскиваются за заголовок, размер меняется за правый нижний угол. Рекомендуемая раскладка:

```
┌────────────────────┬──────────────────────────────────────────────┐
│ Important messages │ Important messages over time                 │
│ (count)            │                                              │
├────────────────────┴───────────────────────┬──────────────────────┤
│ Log volume by service                      │ Logs by service      │
├──────────────────────┬─────────────────────┴──────────────────────┤
│ Important messages   │ Important messages (таблица документов)     │
│ by service           │                                            │
└──────────────────────┴────────────────────────────────────────────┘
```

### 4.3. Период и автообновление каждые 5 секунд

1. В выборе времени справа вверху нажмите иконку календаря → **Commonly used** → **Last 15 minutes**.
2. Снова откройте это меню. Внизу, в блоке **Refresh every**, введите `5`, выберите единицу **seconds** и
   нажмите **Start**.
3. Кнопка **Refresh** начнёт обновлять данные каждые 5 секунд.

### 4.4. Сохранение

1. В верхнем меню нажмите **Save**.
2. **Title**: `Logs monitoring`.
3. **Description**: например, `Мониторинг логов app-logs и появления важной фразы`.
4. Включите **Store time with dashboard**. Это обязательно: без этого переключателя дашборд не запомнит
   период «Last 15 minutes» и автообновление раз в 5 секунд.
5. Нажмите **Save**.

### 4.5. Проверка

- Откройте дашборд заново из списка **Dashboards**. Период должен быть *Last 15 minutes*, автообновление
  должно работать.
- Щёлкните по значению `process_id` в таблице важных сообщений и нажмите **Filter for value** (иконка
  лупы с плюсом). Все панели отфильтруются по процессу. Чтобы увидеть все 10 сообщений процесса, откройте
  его в Discover с тем же фильтром и с поиском **All logs**.

---

## 5. Канал уведомлений (Notifications)

Алертинг отправляет письма через плагин **Notifications**: сначала создаётся SMTP-отправитель, затем
email-канал, который его использует.

### 5.1. SMTP-отправитель (MailHog)

1. Меню ☰ → **Management** → **Notifications**.
2. Слева выберите **Email senders** → в блоке **SMTP senders** нажмите **Create SMTP sender**.
3. Заполните форму **Configure sender**:

   | Поле | Значение | Комментарий |
   |---|---|---|
   | **Sender name** | `mailhog-smtp` | только строчные `a-z`, цифры, `-` и `_`, от 2 до 50 символов |
   | **Email address** | `alerts@opensearch-demo.local` | адрес отправителя (From) |
   | **Host** | `mailhog` | **не `localhost`**: письмо отправляет OpenSearch из своего контейнера, а MailHog доступен там по имени сервиса docker-compose |
   | **Port** | `1025` | SMTP-порт MailHog |
   | **Encryption method** | **None** | MailHog не поддерживает TLS; по умолчанию выбрано SSL/TLS, его нужно сменить |

4. Нажмите **Create**.

### 5.2. Email-канал

1. Слева выберите **Channels** → **Create channel**.
2. **Name and description**:
   - **Name**: `Important message email`
   - **Description - optional**: `Алерты о важных сообщениях в логах`
3. **Configurations**:
   - **Channel type**: **Email**. Тип нельзя поменять после создания канала.
   - **Sender type**: **SMTP sender**.
   - **SMTP sender**: выберите `mailhog-smtp`.
   - **Default recipients**: введите `oncall@opensearch-demo.local` и нажмите **Enter**, чтобы адрес стал
     «чипом». Адрес может быть любым: MailHog принимает всю почту.
4. Нажмите **Send test message**.
5. Откройте MailHog (http://localhost:8025). Там должно быть тестовое письмо. Если его нет, см. раздел 8.
6. Нажмите **Create**. Канал появится в списке со статусом **Active**.

> Пункт **Alerting → Destinations** устарел. Используйте только каналы Notifications.

---

## 6. Монитор (Alerting)

Используем тип **Per document monitor**. Он проверяет каждый новый документ ровно один раз и создаёт
отдельный алерт на каждый найденный документ. Поэтому в письмо можно вывести поля конкретного сообщения,
а дублей и пропусков на границах интервала не бывает.

### 6.1. Monitor details

1. Меню ☰ → **OpenSearch Plugins** → **Alerting**.
2. Вкладка **Monitors** → **Create monitor**.
3. Заполните:
   - **Monitor name**: `important-message-monitor`
   - **Monitor type**: **Per document monitor**
   - **Monitor defining method**: **Visual editor**
   - **Schedule**:
     - **Frequency**: **By interval**
     - **Run every**: `1` **Minute(s)**. Это минимально возможный интервал, меньше минуты Alerting не позволяет.

### 6.2. Select data

- **Index**: начните вводить `app-logs` и выберите индекс из списка. Можно указать и шаблон `app-logs*`.

### 6.3. Query

| Поле | Значение |
|---|---|
| **Query name** | `important-phrase` |
| **Field** | `event_message`. Выберите поле из группы **text**, не `event_message.keyword` |
| Оператор | **is** |
| Значение | `This is a very important message` (без кавычек) |
| **Tags - optional** | **+ Add tag** → `important` (необязательно) |

Для text-поля оператор **is** сохраняется как запрос фразы `event_message:"This is a very important message"`.
Такой запрос находит фразу в любом месте сообщения. Как убедиться, что запрос сохранился именно так,
описано в шаге 6.7.

Можно раскрыть **Preview query and performance** и проверить, что запрос выполняется без ошибок.

### 6.4. Trigger

1. Нажмите **Add trigger**.
2. Заполните:
   - **Trigger name**: `important-message-found`
   - **Severity level**: **1 (Highest)**
   - **Trigger conditions** → **Specify queries or tags**: выберите `important-phrase` из группы **Queries**
     (или тег `important`).

### 6.5. Action (письмо)

Блок **Actions (1)** с действием **Notification** уже добавлен вместе с триггером.

1. **Action name**: `send-email`
2. **Channels**: выберите **[Channel] Important message email**
3. **Message subject**: `[OpenSearch] Important message detected`
4. **Message**: удалите текст по умолчанию и вставьте шаблон:

   ```
   Monitor {{ctx.monitor.name}} found an important message in the logs.

   Trigger: {{ctx.trigger.name}} (severity {{ctx.trigger.severity}})
   Detected at: {{ctx.periodEnd}}
   {{#ctx.alerts}}{{#sample_documents}}
   ----------------------------------------
   process_id:      {{_source.process_id}}
   service_name:    {{_source.service_name}}
   event_timestamp: {{_source.event_timestamp}}
   event_message:   {{_source.event_message}}
   index/doc id:    {{_index}}/{{_id}}
   {{/sample_documents}}{{/ctx.alerts}}
   ```

   Что здесь используется:
   - `ctx.alerts` — алерты, созданные в этом запуске монитора (есть только у per document monitor);
   - `sample_documents` — найденные документы, `_source` содержит все их поля;
   - `{{#...}} ... {{/...}}` — цикл Mustache по списку.

5. **Action configuration** → **Perform action**: выберите **Per alert**. Тогда на каждый найденный документ
   придёт отдельное письмо.
   - Если под **Per alert** появится выбор **Actionable alerts**, оставьте его пустым или выберите **New**.
   - Вариант **Per execution** (стоит по умолчанию) присылает одно письмо за запуск со списком всех
     найденных документов. Шаблон выше работает и в этом режиме.
6. *(Необязательно)* Нажмите **Send test message** и проверьте, что письмо пришло в MailHog. В тестовом
   письме блок с документами будет пустым: это нормально, реальных алертов ещё нет.

### 6.6. Создание

Прокрутите вниз и нажмите **Create**. Откроется страница монитора. Состояние должно быть **Enabled**,
тип **Per document**.

### 6.7. Проверка сохранённого монитора (рекомендуется)

1. Скопируйте ID монитора из адресной строки (`.../monitors/<ID>`).
2. В **Dev Tools** выполните:

   ```
   GET _plugins/_alerting/monitors/<ID>
   ```

3. Проверьте в ответе:
   - `monitor_type`: `"doc_level_monitor"`;
   - `inputs[0].doc_level_input.queries[0].query` содержит `event_message:"This is a very important message"`;
   - `schedule.period`: `interval: 1`, `unit: "MINUTES"`;
   - у действия `action_execution_policy.action_execution_scope.per_alert`.

Для сравнения: эталонное определение того же монитора, которое автоматическая настройка создаёт через API,
лежит в [opensearch/init/monitor.json](opensearch/init/monitor.json).

---

## 7. Проверка всей цепочки

1. Отправьте важное сообщение (или подождите, пока его отправит `log-sender`, это происходит раз в 10 секунд):

   ```bash
   scripts/send-logs.sh --important
   ```

   Скрипт выведет `process_id` пачки.
2. **Дашборд** (Dashboards → Logs monitoring): в течение ~5 секунд счётчик Important messages
   увеличится, а сообщение появится в таблице.
3. **Монитор**: Alerting → Monitors → `important-message-monitor`. После ближайшего запуска (≤ 1 минуты)
   в истории появятся алерты.
4. **Почта**: откройте MailHog (http://localhost:8025). Придёт письмо *"[OpenSearch] Important message detected"*
   с тем же `process_id`, что вывел скрипт.

Ожидаемая задержка от записи сообщения до письма — до ~1 минуты (интервал запуска монитора).

---

## 8. Частые ошибки

| Симптом | Причина и решение |
|---|---|
| При создании index pattern нет совпадений | Индекс не создан или в нём нет документов. Выполните шаг 0.2 и отправьте логи |
| В index pattern нет поля времени `event_timestamp` | Первые документы попали в индекс до создания шаблона, и маппинг создался динамически. Удалите индекс (`DELETE app-logs`), создайте его заново по шагу 0.2, затем пересоздайте index pattern |
| Запрос в Discover находит 0 документов | Не забыты ли кавычки вокруг фразы? Используется ли `event_message`, а не `.keyword`? Подходит ли период времени? |
| Визуализации пишут *Could not locate that index-pattern-field* | Index pattern создан без полей или до появления документов. Dashboards Management → Index patterns → `app-logs*` → кнопка **Refresh field list** (↻) |
| **Send test message** в канале падает с ошибкой подключения | В **Host** указан `localhost` вместо `mailhog`, или **Encryption method** не **None** |
| Sender name не принимается | Допустимы только строчные латинские буквы, цифры, `-` и `_` |
| Монитор не создаётся: индекс не найден | Per document monitor требует существующий индекс. Создайте `app-logs` (шаг 0.2) |
| Монитор работает, но писем нет | Проверьте канал (**Send test message**). Проверьте, что триггер ссылается на нужный query. На странице монитора посмотрите **Errors** и состояние алертов. Для ручного прогона без отправки писем: `POST _plugins/_alerting/monitors/<ID>/_execute?dryrun=true` в Dev Tools |
| В письме пустые поля документа | Шаблон должен обходить `{{#ctx.alerts}}{{#sample_documents}}...{{/sample_documents}}{{/ctx.alerts}}`. Тестовое письмо из формы всегда без документов |
| Дашборд не обновляется сам | Дашборд сохранён без **Store time with dashboard**. Включите автообновление (4.3) и пересохраните с этим переключателем (4.4) |
| Письмо приходит не сразу | Это ожидаемо: монитор запускается раз в минуту, чаще OpenSearch Alerting не позволяет |

---

## 9. Сохранение результата в репозиторий

Объекты, созданные в UI, хранятся в OpenSearch и пропадут после `docker compose down -v`. Чтобы
автоматическая настройка восстанавливала ваш вариант дашборда, экспортируйте его.

**Дашборд и визуализации:**

- Через UI: Dashboards Management → **Saved objects** → отметьте объекты (index pattern, оба поиска, все
  визуализации, дашборд) → **Export** → выключите **Include related objects** или оставьте включённым →
  **Export**. Полученный файл сохраните как `opensearch/dashboards/saved-objects.ndjson`.
- Или командой:

  ```bash
  curl -s -X POST localhost:5601/api/saved_objects/_export -H 'osd-xsrf: true' -H 'Content-Type: application/json' \
    -d '{"type":["index-pattern","search","visualization","dashboard"],"excludeExportDetails":true}' \
    > opensearch/dashboards/saved-objects.ndjson
  ```

**Монитор и каналы** в saved objects не входят. Их определения лежат в
[opensearch/init/monitor.json](opensearch/init/monitor.json),
[smtp-account.json](opensearch/init/smtp-account.json) и [email-channel.json](opensearch/init/email-channel.json).
Если вы поменяли монитор в UI, перенесите изменения в `monitor.json`. Текущее определение возьмите командой
`GET _plugins/_alerting/monitors/<ID>`, поле `monitor`, без служебных полей `last_update_time`,
`enabled_time` и `user`.

> Учтите: UI присваивает каналу случайный ID, а автоматическая настройка использует фиксированный
> `important-message-email`. В `monitor.json` поле `destination_id` должно указывать на ID канала.
