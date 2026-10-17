#!/usr/bin/env bash
# Ручная отправка пачек логов в OpenSearch через Bulk API.
#
#   scripts/send-logs.sh                  # одна пачка из 10 сообщений с одним process_id
#   scripts/send-logs.sh --important      # одно сообщение пачки содержит важную фразу
#   scripts/send-logs.sh --count 5        # пять пачек подряд
#
# Переменные окружения: OPENSEARCH_URL (по умолчанию http://localhost:9200), INDEX (app-logs),
# SERVICE_NAME (manual-script).
set -euo pipefail

OPENSEARCH_URL=${OPENSEARCH_URL:-http://localhost:9200}
INDEX=${INDEX:-app-logs}
SERVICE_NAME=${SERVICE_NAME:-manual-script}
BATCH_SIZE=10
IMPORTANT_PHRASE="This is a very important message"

important=false
count=1

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --important) important=true; shift ;;
    --count) count=${2:?--count requires a value}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

subjects=("User session" "Payment request" "Order" "Cache entry" "Background job" "Notification")
verbs=("was processed" "was created" "was updated" "was validated" "was queued" "was retried")
details=("without errors" "after a short delay" "by the worker pool" "on the primary node" "with default settings")

random_sentence() {
  echo "${subjects[RANDOM % ${#subjects[@]}]} ${verbs[RANDOM % ${#verbs[@]}]} ${details[RANDOM % ${#details[@]}]} in $((RANDOM % 1000 + 1)) ms"
}

timestamp() {
  # ISO-8601 UTC с миллисекундами; date на macOS не умеет %N, поэтому берём время из python3, если он есть.
  if command -v python3 > /dev/null; then
    python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))'
  else
    date -u +%Y-%m-%dT%H:%M:%SZ
  fi
}

send_batch() {
  local process_id important_index body message
  process_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  important_index=-1
  if [[ "$important" == true ]]; then
    important_index=$((RANDOM % BATCH_SIZE))
  fi

  body=""
  for ((i = 0; i < BATCH_SIZE; i++)); do
    if [[ $i -eq $important_index ]]; then
      message="$(random_sentence). ${IMPORTANT_PHRASE}: $(random_sentence)."
    else
      message=$(random_sentence)
    fi
    body+=$'{"index":{}}\n'
    body+="{\"process_id\":\"${process_id}\",\"event_timestamp\":\"$(timestamp)\",\"service_name\":\"${SERVICE_NAME}\",\"event_message\":\"${message}\"}"$'\n'
  done

  response=$(curl -sS -X POST "${OPENSEARCH_URL}/${INDEX}/_bulk" \
    -H 'Content-Type: application/x-ndjson' --data-binary "$body")
  if [[ "$response" != *'"errors":false'* ]]; then
    echo "Bulk request failed: $response" >&2
    exit 1
  fi
  echo "Sent ${BATCH_SIZE} messages, process_id=${process_id}$([[ "$important" == true ]] && echo ' [IMPORTANT]')"
}

for ((n = 0; n < count; n++)); do
  send_batch
done
