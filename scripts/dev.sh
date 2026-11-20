#!/usr/bin/env bash
# Run host services in one terminal with prefixed logs. Docker infra is managed separately.
# Ctrl+C or closing the terminal stops every service; the first service to exit stops the rest.
set -uo pipefail
cd "$(dirname "$0")/.."

read -ra apps <<< "${APPS:-core ai admin}"
for app in "${apps[@]}"; do
  case $app in core|ai|ai-local|admin|mobile) ;; *) echo "Unknown app: $app (allowed: core ai ai-local admin mobile)" >&2; exit 2 ;; esac
done

export PYTHONUNBUFFERED=1 FORCE_COLOR=1
set -m  # Each service gets its own process group, so terminal signals reach only this script.
pids=()
stop() {
  trap '' INT TERM HUP
  local groups
  groups=$(IFS=,; echo "${pids[*]}")
  for pid in "${pids[@]}"; do kill -TERM -- "-$pid" 2>/dev/null; done
  # Wait for whole process groups (uv/uvicorn, Vite, core_dev.py which stops its JVM), not just jobs.
  for _ in {1..200}; do
    pgrep -g "$groups" >/dev/null || return 0
    sleep 0.2
  done
  echo "Services did not stop within 40s; killing them." >&2
  for pid in "${pids[@]}"; do kill -KILL -- "-$pid" 2>/dev/null; done
}
trap 'stop; exit 130' INT TERM HUP

colors=(36 35 33 32 34)
for i in "${!apps[@]}"; do
  app=${apps[$i]}
  prefix=$'\e'"[${colors[i % 5]}m[$app]"$'\e[0m '
  # make leads the job so an exit is noticed even if orphans keep its output open; the prefixer
  # ignores INT/HUP so shutdown logs still flow, and exits once the service group closes the pipe.
  make --no-print-directory "$app" < /dev/null > >(trap '' INT HUP; exec sed -u "s/^/$prefix/") 2>&1 &
  pids+=("$!")
done

wait -n "${pids[@]}"
status=$?
echo "A service exited ($status); stopping the others..." >&2
stop
exit "$status"
