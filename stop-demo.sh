#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="compose.portable.yaml"
ENV_FILE=".env.portable"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v podman >/dev/null 2>&1 || fail "podman is required"
[ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
[ -f "$ENV_FILE" ] || fail "missing $ENV_FILE"

for key in BANK_DB_USER BANK_DB_PASSWORD BANK_INTERNAL_SECRET PODMAN_NETWORK; do
  grep -Eq "^${key}=[^[:space:]]+$" "$ENV_FILE" ||
    fail "$ENV_FILE must define $key without printing credentials"
done

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config >/dev/null 2>&1 ||
  fail "Compose configuration validation failed"

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop >/dev/null 2>&1 ||
  fail "Compose stack failed to stop"
printf 'Demo bank stack stopped; containers and volumes were retained.\n'
