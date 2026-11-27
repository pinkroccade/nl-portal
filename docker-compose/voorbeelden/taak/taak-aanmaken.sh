#!/usr/bin/env bash
# Schiet een externe taak (Taak V2) in Open Object voor de demo-burger, zodat die hem na inloggen ziet in NL Portal.
# De taak-link wijst naar de mock van het Sociaal Portaal (compose.demo.yaml).
#
# Gebruik (nadat `mise run dev:setup` de .env heeft gemaakt en de demo draait):
#   bash docker-compose/voorbeelden/taak/taak-aanmaken.sh [titel] [bsn] [zaaknummer]
set -euo pipefail

cd "$(dirname "$0")/../.."

# OBJECTS_API_TOKEN uit .env; een waarde in de omgeving wint.
if [ -z "${OBJECTS_API_TOKEN:-}" ] && [ -f .env ]; then
  OBJECTS_API_TOKEN="$(grep -E '^OBJECTS_API_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"\r')"
fi
: "${OBJECTS_API_TOKEN:?OBJECTS_API_TOKEN ontbreekt: draai eerst mise run dev:setup}"

TITEL="${1:-Kopie huurcontract aanleveren voor adresonderzoek}"
BSN="${2:-999993847}" # BSN van de Keycloak-gebruiker 'burger'
OPEN_OBJECT="http://localhost:${OPEN_OBJECT_PORT:-8010}"
PORTAAL_MOCK="http://localhost:${SOCIAAL_PORTAAL_MOCK_PORT:-8095}"
# Het objecttype "Taak V2", zoals de backend het kent (imports/open-object/fixtures/objecttypes.json).
OBJECTTYPE="http://nl-portal-open-object:8010/api/v2/objecttypes/b4a617af-6969-45e8-9afd-d51e170716d5"

nieuw_uuid() {
  if [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  else powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'
  fi
}
# Koppeling met registratie "product" (geen zaak): zo opent de portal bij een klik direct de taak-URL (de mock van het Sociaal Portaal).
KOPPELING_ID="$(nieuw_uuid)"
ZAAKNUMMER="${3:-ZAAK-2026-0000042}" # alleen ter weergave op de mockpagina
TAAK_ID="$(nieuw_uuid)"
TITEL_URL="$(printf '%s' "$TITEL" | sed 's/ /%20/g')"
VANDAAG="$(date +%F)"

BODY=$(
  cat <<JSON
{
  "type": "$OBJECTTYPE",
  "record": {
    "typeVersion": 1,
    "startAt": "$VANDAAG",
    "data": {
      "soort": "url",
      "titel": "$TITEL",
      "status": "open",
      "eigenaar": "Gemeente De Bosrand - Sociaal Domein",
      "identificatie": { "type": "bsn", "value": "$BSN" },
      "url": { "uri": "$PORTAAL_MOCK/?titel=$TITEL_URL&zaak=$ZAAKNUMMER" },
      "koppeling": { "registratie": "product", "value": "$KOPPELING_ID" },
      "verwerker_taak_id": "$TAAK_ID"
    }
  }
}
JSON
)

curl --fail-with-body -sS -X POST "$OPEN_OBJECT/api/v2/objects" \
  -H "Authorization: Token $OBJECTS_API_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Content-Crs: EPSG:4326" \
  --data "$BODY"
echo
echo "Taak aangemaakt voor BSN $BSN. Log in op http://localhost:3000 als burger/burger."
