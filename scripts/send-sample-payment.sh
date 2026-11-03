#!/usr/bin/env bash
# Sends a sample "payment-settled" message to the ledger's Kafka topic, as the payment system
# would. Requires the compose stack to be up (`./run.sh` or `docker compose up -d mysql kafka`).
#
# Usage:
#   ./scripts/send-sample-payment.sh
#   ./scripts/send-sample-payment.sh merchant-42 125.50 EUR
#   ./scripts/send-sample-payment.sh merchant-7 15000 JPY
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ACCOUNT_ID="${1:-merchant-42}"
AMOUNT="${2:-125.50}"
CURRENCY="${3:-EUR}"
PAYMENT_ID="${4:-payment-$(date +%s)-$RANDOM}"
EVENT_ID="event-$(date +%s)-$RANDOM"
TOPIC="payment.settled"

PAYLOAD=$(cat <<JSON
{"eventId":"${EVENT_ID}","paymentId":"${PAYMENT_ID}","accountId":"${ACCOUNT_ID}","amount":${AMOUNT},"currency":"${CURRENCY}"}
JSON
)

echo "==> Opening account ${ACCOUNT_ID} (${CURRENCY}) if it doesn't exist yet"
curl -s -o /dev/null -w "   -> %{http_code}\n" -X POST "http://localhost:8080/v1/accounts" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: open-${ACCOUNT_ID}" \
  -d "{\"id\":\"${ACCOUNT_ID}\",\"currency\":\"${CURRENCY}\"}" || true

echo "==> Publishing payment-settled message to topic '${TOPIC}':"
echo "    ${PAYLOAD}"
docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "${TOPIC}" <<< "${PAYLOAD}"

echo "==> Sent. Check the account's balance:"
echo "    curl http://localhost:8080/v1/accounts/${ACCOUNT_ID}"

