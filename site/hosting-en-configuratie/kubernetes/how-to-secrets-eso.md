# How-to: secrets uit Vault vullen via External Secrets Operator

*Diátaxis: how-to.* De chart maakt geen Secrets. Je laat External Secrets Operator (ESO) per component één Secret vullen uit Vault, en wijst dat Secret aan.

De `ExternalSecret` hoort in je base-chart of GitOps-repository, niet in deze chart. De keys staan vast en staan per component in de [referentie van de secret-keys](./referentie-secret-keys.md);
per key kun je de naam in je secret store overschrijven met `backend.secretKeys`. De frontend heeft geen secrets nodig.

## Voorbeeld met Vault (KV v2)

Een backend-Secret met alle keys die een tenant met de modules zaken, catalogi en objecten nodig heeft, en een Secret met de clientsleutel per tenant:

```yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: nl-portal-backend
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: vault
  target:
    name: nl-portal-backend
  data:
    - secretKey: REDIS_PASSWORD
      remoteRef: { key: nl-portal/redis, property: password }
    # Optioneel: overschrijft backend.redis.username en backend.redis.ssl.
    # - secretKey: REDIS_USERNAME
    #   remoteRef: { key: nl-portal/redis, property: username }
    # Alleen met backend.beheer.enabled: hash met prefix, bijvoorbeeld {bcrypt}$2a$...
    # - secretKey: NLPORTAL_BEHEER_WACHTWOORDHASH
    #   remoteRef: { key: nl-portal/beheer, property: wachtwoordhash }
    # Per module die dit nodig heeft (zie de referentie):
    # - secretKey: NLPORTAL_CONFIG_BESLUITENAPI_PROPERTIES_SECRET
    #   remoteRef: { key: nl-portal/debosrand, property: besluiten-secret }
    # - secretKey: NLPORTAL_CONFIG_OPENKLANT2_PROPERTIES_TOKEN
    #   remoteRef: { key: nl-portal/debosrand, property: openklant-token }
    # - secretKey: NLPORTAL_CONFIG_OPENPRODUCT_PROPERTIES_TOKEN
    #   remoteRef: { key: nl-portal/debosrand, property: openproduct-token }
    # De geheimen waar het tenantbestand naar verwijst, per OIN:
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET
      remoteRef: { key: nl-portal/debosrand, property: zaken-secret }
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_CATALOGIAPI_SECRET
      remoteRef: { key: nl-portal/debosrand, property: catalogi-secret }
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_OBJECTENAPI_TOKEN
      remoteRef: { key: nl-portal/debosrand, property: objecten-token }   # waarde: "Token <token>"
    # - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_DOCUMENTEN_API_SECRET
    # - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_VERIFICATIE_SECRET
---
# Clientsleutel per tenant (private_key_jwt): één Secret per OIN
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: nl-portal-sleutel-00000099000000000001
spec:
  secretStoreRef: { kind: ClusterSecretStore, name: vault }
  target: { name: nl-portal-sleutel-00000099000000000001 }
  data:
    - secretKey: clientsleutel.jwk.json
      remoteRef: { key: nl-portal/debosrand, property: clientsleutel }
```

Wijs de Secrets aan in de values: `backend.existingSecret: nl-portal-backend` en per tenant `tenancy.keys[].existingSecret`.

## Na een rotatie

Een pod ziet een gewijzigde secretwaarde pas na een herstart. Verhoog na een rotatie `backend.secretVersion` (de waarde komt als annotatie op de pod, dus de pods
herstarten), of gebruik een secret-reloader via `backend.podAnnotations`.

## Wat de chart wel en niet controleert

- Een ontbrekende `backend.existingSecret` laat het renderen falen. De chart kan het Secret zelf niet lezen (geen `lookup`).
- De keys die per stuk worden gerefereerd (Redis-gebruiker, beheerhash, module-secrets) laten de pod bij een ontbrekende key stoppen met
  `CreateContainerConfigError`, in plaats van pas tijdens runtime te falen.
- De `NL_PORTAL_GEHEIMEN_*`-keys hangen van de tenants af; het hele Secret komt daarom als omgeving in de backend-container.
