#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="compose.portable.yaml"
ENV_FILE=".env.portable"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-120}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v podman >/dev/null 2>&1 || fail "podman is required"
command -v curl >/dev/null 2>&1 || fail "curl is required for readiness checks"
[ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
[ -f "$ENV_FILE" ] || fail "missing $ENV_FILE; copy .env.portable.example and set local values"

for key in BANK_DB_USER BANK_DB_PASSWORD BANK_INTERNAL_SECRET PODMAN_NETWORK GATEWAY_PORT; do
  grep -Eq "^${key}=[^[:space:]]+$" "$ENV_FILE" ||
    fail "$ENV_FILE must define $key without printing credentials"
done

GATEWAY_PORT="$(sed -n 's/^GATEWAY_PORT=//p' "$ENV_FILE" | head -n 1)"
[[ "$GATEWAY_PORT" =~ ^[0-9]+$ ]] || fail "GATEWAY_PORT must be numeric"
GATEWAY_HOST="127.0.0.1"
NETWORK_NAME="$(sed -n 's/^PODMAN_NETWORK=//p' "$ENV_FILE" | head -n 1)"
[ -n "$NETWORK_NAME" ] || fail "PODMAN_NETWORK must not be empty"

podman network exists "$NETWORK_NAME" 2>/dev/null ||
  podman network create "$NETWORK_NAME" >/dev/null

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config >/dev/null 2>&1 ||
  fail "Compose configuration validation failed"

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up --build -d >/dev/null 2>&1 ||
  fail "Compose stack failed to start; inspect container status and logs separately"

deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
health_url="http://${GATEWAY_HOST}:${GATEWAY_PORT}/actuator/health"
while (( SECONDS < deadline )); do
  if curl --fail --silent --show-error --max-time 3 "$health_url" |
    grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    printf 'Demo bank stack is ready at http://%s:%s\n' "$GATEWAY_HOST" "$GATEWAY_PORT"
    exit 0
  fi
  sleep 2
done

fail "gateway did not become ready within ${READY_TIMEOUT_SECONDS}s"
