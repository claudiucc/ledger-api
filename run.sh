#!/usr/bin/env bash
# Builds the ledger-api image, starts it with everything it needs (MySQL, Kafka),
# and runs the test suite. Requires only Docker, Git and a JDK on the host.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

DC="docker compose"

echo "==> [1/5] Building the application image"
$DC build app

echo "==> [2/5] Starting infrastructure (MySQL, Kafka) and waiting for it to be healthy"
$DC up -d --wait mysql kafka

echo "==> [3/5] Running the test suite (unit + integration, via Testcontainers)"
./gradlew --no-daemon clean test

echo "==> [4/5] Starting the application"
$DC up -d --wait app

echo "==> [5/5] Stack is up:"
echo "    API:        http://localhost:8080"
echo "    Actuator:   http://localhost:8080/actuator/health"
echo "    MySQL:      localhost:3306 (root / passw0rd, db ledger_api)"
echo "    Kafka:      localhost:9094 (from the host), kafka:9092 (inside the compose network)"
echo
echo "Optional dev tools (Kafka UI on :8081, Adminer on :8082):"
echo "    docker compose --profile tools up -d"

