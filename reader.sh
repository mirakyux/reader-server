#!/usr/bin/env bash
# Build and start the two services from this checkout. Does not modify Docker installation.
set -euo pipefail
cd "$(dirname "$0")"
exec docker compose -f docker-compose.yml up -d --build "$@"
