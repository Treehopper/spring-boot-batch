#!/usr/bin/env bash
# Exercises the external-scheduler (UC4) API against a running app. Requires curl and jq.
#   ./smoke-test.sh [base-url]
set -euo pipefail

BASE=${1:-http://localhost:8080}
AUTH=(-u uc4:uc4-secret)
JOB=importCustomersJob

api() { curl -sS "${AUTH[@]}" -H 'Content-Type: application/json' "$@"; }

start() {
  api -X POST -d "$1" "$BASE/api/jobs/$JOB/start" | jq -r .jobExecutionId
}

await() {
  local id=$1 status
  for _ in $(seq 1 60); do
    status=$(api "$BASE/api/executions/$id" | jq -r .status)
    [[ $status == STARTING || $status == STARTED || $status == STOPPING ]] || { echo "$status"; return; }
    sleep 1
  done
  echo TIMEOUT
}

echo "== Without credentials the API is rejected"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$BASE/api/jobs/$JOB"

echo "== Job, owner and triggers"
api "$BASE/api/jobs/$JOB" | jq -c .

echo "== 1. Successful run"
id=$(start '{"itemCount":"10","itemDelayMs":"50"}')
echo "execution $id -> $(await "$id")"
api "$BASE/api/executions/$id" | jq -c '{jobExecutionId, status, durationMs, parameters, steps}'

echo "== 2. Failing run (sends a notification mail)"
id=$(start '{"failAtItem":"3","itemDelayMs":"10"}')
echo "execution $id -> $(await "$id")"
api "$BASE/api/executions/$id" | jq -c '{status, exitCode, exitDescription: (.exitDescription | split("\n")[0])}'

echo "== 3. Long run, stopped gracefully via Spring Batch (sends a notification mail)"
id=$(start '{"itemCount":"100","itemDelayMs":"300"}')
sleep 2
api -X POST "$BASE/api/executions/$id/stop" | jq -c '{status}'
echo "execution $id -> $(await "$id")"
api "$BASE/api/executions/$id" | jq -c '{status, steps}'
echo "stopping again: $(api -o /dev/null -w 'HTTP %{http_code}' -X POST "$BASE/api/executions/$id/stop")"

echo "== 4. Hand scheduling over to UC4 (pauses the cron trigger) and back"
api -X PUT -d '{"owner":"UC4"}' "$BASE/api/jobs/$JOB/owner" | jq -c '{owner, triggers: [.triggers[] | {key, state}]}'
id=$(start '{"itemCount":"3","itemDelayMs":"10"}')
echo "API start still works while UC4 owns the job: execution $id -> $(await "$id")"
api -X PUT -d '{"owner":"INTERNAL"}' "$BASE/api/jobs/$JOB/owner" | jq -c '{owner, triggers: [.triggers[] | {key, state}]}'

echo "== Quartz Manager sees the Quartz job and its triggers"
curl -sS "$BASE/quartz-manager/jobs" | jq -c '[.[] | {job: .jobKeyDTO, cls: .jobClassName, triggers: .triggerKeys}]'
