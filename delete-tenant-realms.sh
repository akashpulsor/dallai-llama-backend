#!/bin/bash
# delete-tenant-realms.sh
# Deletes all Keycloak realms except master and dalai-llama

set -e

KC_URL="${KC_URL:-https://auth.dalaillama.in}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:?Set KC_ADMIN_PASS env var}"

echo "Authenticating to ${KC_URL}..."
TOKEN=$(curl -sf -X POST "${KC_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=${KC_ADMIN_USER}" \
  -d "password=${KC_ADMIN_PASS}" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r .access_token)

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "ERROR: Failed to get admin token"
  exit 1
fi

echo "Fetching realm list..."
REALMS=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
  "${KC_URL}/admin/realms" | jq -r '.[].realm')

echo "Found realms:"
echo "$REALMS" | sed 's/^/  - /'
echo ""

DELETED=0
SKIPPED=0
FAILED=0

for realm in $REALMS; do
  case "$realm" in
    master|dalai-llama)
      echo "SKIP   $realm (protected)"
      SKIPPED=$((SKIPPED+1))
      ;;
    *)
      echo -n "DELETE $realm ... "
      HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KC_URL}/admin/realms/${realm}")
      if [ "$HTTP_CODE" = "204" ]; then
        echo "OK"
        DELETED=$((DELETED+1))
      else
        echo "FAILED (HTTP $HTTP_CODE)"
        FAILED=$((FAILED+1))
      fi
      ;;
  esac
done

echo ""
echo "Summary: deleted=${DELETED} skipped=${SKIPPED} failed=${FAILED}"
