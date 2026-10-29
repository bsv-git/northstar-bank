#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  cp .env.example .env
fi

if ! podman ps --format '{{.Names}}' | grep -qx 'de_warehouse'; then
  podman start de_warehouse
fi

for db in bank_identity bank_ledger bank_payment bank_notification; do
  if ! podman exec de_warehouse psql -U de_admin -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
    echo "Creating database ${db}"
    podman exec de_warehouse psql -U de_admin -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"${db}\" OWNER de_admin;"
  fi
done

podman compose --env-file .env -f compose.yaml up -d --build

echo "Demo bank stack is running"
