#!/usr/bin/env bash
# Use case: e-commerce sale -> partial refund -> idempotent retry.
# Requires: curl, jq. Usage: docker compose up -d && ./examples/ecommerce_order_lifecycle.sh
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"

CASH_ID=$(curl -s -X POST "$BASE_URL/api/v1/accounts" -H 'Content-Type: application/json' -d '{"name":"Cash","accountType":"ASSET","currency":"USD"}' | jq -r '.id')
REVENUE_ID=$(curl -s -X POST "$BASE_URL/api/v1/accounts" -H 'Content-Type: application/json' -d '{"name":"Sales Revenue","accountType":"REVENUE","currency":"USD"}' | jq -r '.id')
RETURNS_ID=$(curl -s -X POST "$BASE_URL/api/v1/accounts" -H 'Content-Type: application/json' -d '{"name":"Sales Returns","accountType":"EXPENSE","currency":"USD"}' | jq -r '.id')

SALE_KEY="order-8842-sale"
SALE_BODY="{\"idempotencyKey\":\"$SALE_KEY\",\"description\":\"Order #8842\",\"entries\":[{\"accountId\":\"$CASH_ID\",\"direction\":\"DEBIT\",\"amountMinor\":12000},{\"accountId\":\"$REVENUE_ID\",\"direction\":\"CREDIT\",\"amountMinor\":12000}]}"
SALE_TX=$(curl -s -X POST "$BASE_URL/api/v1/transactions" -H 'Content-Type: application/json' -d "$SALE_BODY")
echo "Sale posted:"; echo "$SALE_TX" | jq '{id, status}'

REFUND_BODY="{\"idempotencyKey\":\"order-8842-refund-1\",\"description\":\"Partial refund\",\"entries\":[{\"accountId\":\"$RETURNS_ID\",\"direction\":\"DEBIT\",\"amountMinor\":3000},{\"accountId\":\"$CASH_ID\",\"direction\":\"CREDIT\",\"amountMinor\":3000}]}"
curl -s -X POST "$BASE_URL/api/v1/transactions" -H 'Content-Type: application/json' -d "$REFUND_BODY" | jq '{id, status}'

echo "Cash balance (expect 9000):"
curl -s "$BASE_URL/api/v1/accounts/$CASH_ID" | jq '.balanceMinor'

echo "Retrying the sale with the same key -- should return the SAME tx id, no double-post:"
RETRY_TX=$(curl -s -X POST "$BASE_URL/api/v1/transactions" -H 'Content-Type: application/json' -d "$SALE_BODY")
echo "$RETRY_TX" | jq '.id'
curl -s "$BASE_URL/api/v1/accounts/$CASH_ID" | jq '.balanceMinor'
