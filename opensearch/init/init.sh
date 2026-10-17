#!/bin/sh
# Идемпотентная настройка OpenSearch и OpenSearch Dashboards для демо.
# Запускается контейнером opensearch-init; можно запускать и с хоста:
#   OPENSEARCH_URL=http://localhost:9200 DASHBOARDS_URL=http://localhost:5601 sh opensearch/init/init.sh
set -eu

OPENSEARCH_URL=${OPENSEARCH_URL:-http://localhost:9200}
DASHBOARDS_URL=${DASHBOARDS_URL:-http://localhost:5601}
INIT_DIR=$(cd "$(dirname "$0")" && pwd)
DASHBOARDS_DIR=$(dirname "$INIT_DIR")/dashboards

INDEX=app-logs
SMTP_ACCOUNT_ID=mailhog-smtp
EMAIL_CHANNEL_ID=important-message-email
MONITOR_NAME=important-message-monitor

log() {
  echo "[init] $*"
}

# request METHOD URL [curl args...]
# Печатает тело ответа; при HTTP-коде вне 2xx пишет ошибку в stderr и возвращает 1.
request() {
  method=$1
  url=$2
  shift 2
  body_file=$(mktemp)
  code=$(curl -sS -o "$body_file" -w '%{http_code}' -X "$method" "$url" "$@") || code=000
  body=$(cat "$body_file")
  rm -f "$body_file"
  case "$code" in
    2??) printf '%s' "$body" ;;
    *) echo "[init] $method $url -> HTTP $code: $body" >&2; return 1 ;;
  esac
}

# exists URL — 0, если ресурс отвечает 200.
exists() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "200" ]
}

wait_for() {
  name=$1
  url=$2
  attempt=0
  until curl -fs "$url" > /dev/null; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
      log "$name is not available at $url"
      exit 1
    fi
    sleep 2
  done
  log "$name is available"
}

json() {
  request "$@" -H 'Content-Type: application/json'
}

# upsert_notification CONFIG_ID FILE — создаёт или обновляет конфиг плагина Notifications.
upsert_notification() {
  config_id=$1
  config=$(cat "$2")
  if exists "$OPENSEARCH_URL/_plugins/_notifications/configs/$config_id"; then
    json PUT "$OPENSEARCH_URL/_plugins/_notifications/configs/$config_id" -d "{\"config\": $config}" > /dev/null
    log "notification config '$config_id' updated"
  else
    json POST "$OPENSEARCH_URL/_plugins/_notifications/configs" -d "{\"config_id\": \"$config_id\", \"config\": $config}" > /dev/null
    log "notification config '$config_id' created"
  fi
}

find_monitor_id() {
  # Индекс конфигурации Alerting создаётся при первом мониторе, поэтому ошибку поиска считаем «не найден».
  json POST "$OPENSEARCH_URL/_plugins/_alerting/monitors/_search" \
    -d "{\"query\": {\"match_phrase\": {\"monitor.name\": \"$MONITOR_NAME\"}}}" 2> /dev/null \
    | grep -o '"_id":"[^"]*"' | head -n 1 | cut -d '"' -f 4 || true
}

wait_for "OpenSearch" "$OPENSEARCH_URL/_cluster/health?wait_for_status=yellow&timeout=5s"
wait_for "OpenSearch Dashboards" "$DASHBOARDS_URL/api/status"

# 1. Шаблон и индекс логов
json PUT "$OPENSEARCH_URL/_index_template/app-logs" -d @"$INIT_DIR/index-template.json" > /dev/null
log "index template 'app-logs' applied"
if exists "$OPENSEARCH_URL/$INDEX"; then
  log "index '$INDEX' already exists"
else
  request PUT "$OPENSEARCH_URL/$INDEX" > /dev/null
  log "index '$INDEX' created"
fi

# 2. Notifications: SMTP-отправитель (MailHog) и email-канал
upsert_notification "$SMTP_ACCOUNT_ID" "$INIT_DIR/smtp-account.json"
upsert_notification "$EMAIL_CHANNEL_ID" "$INIT_DIR/email-channel.json"

# 3. Alerting: монитор на важную фразу
monitor_id=$(find_monitor_id)
if [ -n "$monitor_id" ]; then
  json PUT "$OPENSEARCH_URL/_plugins/_alerting/monitors/$monitor_id" -d @"$INIT_DIR/monitor.json" > /dev/null
  log "monitor '$MONITOR_NAME' updated (id=$monitor_id)"
else
  monitor_id=$(json POST "$OPENSEARCH_URL/_plugins/_alerting/monitors" -d @"$INIT_DIR/monitor.json" \
    | grep -o '"_id":"[^"]*"' | head -n 1 | cut -d '"' -f 4)
  log "monitor '$MONITOR_NAME' created (id=$monitor_id)"
fi

# 4. Dashboards: index pattern, поиски, визуализации, дашборд
import_result=$(request POST "$DASHBOARDS_URL/api/saved_objects/_import?overwrite=true" \
  -H 'osd-xsrf: true' -F "file=@$DASHBOARDS_DIR/saved-objects.ndjson")
case "$import_result" in
  *'"success":true'*) log "saved objects imported" ;;
  *) log "saved objects import failed: $import_result"; exit 1 ;;
esac
json POST "$DASHBOARDS_URL/api/opensearch-dashboards/settings" -H 'osd-xsrf: true' \
  -d "{\"changes\": {\"defaultIndex\": \"$INDEX\"}}" > /dev/null
log "default index pattern set to '$INDEX'"

log "done"
