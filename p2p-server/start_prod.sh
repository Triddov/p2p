#!/bin/bash

set -euo pipefail


docker compose pull
docker compose up -d 
docker image prune -f

sleep 5
if docker compose ps --format '{{.Name}} {{.State}}' | grep -vw 'running'; then
  echo "=== Services are not running: ==="
  docker compose logs --tail=50
  exit 1
fi
