# Deployen op Kubernetes met Helm

NL Portal heeft een eigen Helm-chart, `nl-portal`, in de map [`helm/nl-portal`](https://github.com/pinkroccade/nl-portal/tree/main/helm/nl-portal) van de repository
(epic [#191](https://github.com/pinkroccade/nl-portal/issues/191)). De chart bevat alleen wat de applicatie nodig heeft om te draaien: de backend en de
frontend als aparte Deployments en Services, een ConfigMap met de niet-geheime configuratie, een optionele ServiceAccount en een optionele Ingress.
Hij is bedoeld als child-chart in een base-chart die de cluster-instellingen (Argo CD, NetworkPolicy, ExternalSecret, ingress) regelt; een gewone
`helm install` werkt ook, als referentie. De volledige parameterreferentie en de tabel met secret-keys staan in de
[chart-README](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/README.md).

## De chart ophalen

De chart staat als OCI-artifact op ghcr, naast de images, en is gesigneerd met cosign:

| Wat | Locatie |
|---|---|
| Chart | `oci://ghcr.io/pinkroccade/charts/nl-portal` |
| Images | `ghcr.io/pinkroccade/nl-portal-backend` en `ghcr.io/pinkroccade/nl-portal-frontend` |

De chart heeft een eigen SemVer, los van de app-versie. Een release is een tag `chart-nl-portal-X.Y.Z`; `appVersion` in de chart is de standaard
image-tag. Pin altijd een chartversie, zodat een sync nooit onbedoeld een andere chart uitrolt:

```shell
helm pull oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0
helm install nl-portal oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0 -n nl-portal -f values.yaml
```

Controleer de handtekening voor je de chart gebruikt:

```shell
cosign verify ghcr.io/pinkroccade/charts/nl-portal:0.1.0 \
  --certificate-identity-regexp '^https://github.com/pinkroccade/nl-portal/.github/workflows/publish-helm-chart.yml@refs/tags/chart-nl-portal-' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

### Via Harbor (eigen registry)

Draai je in een cluster dat alleen uit Harbor haalt, kopieer dan chart en images naar een Harbor-project, met behoud van de handtekeningen
(`crane copy` en `cosign copy` nemen de digest mee):

```shell
crane copy ghcr.io/pinkroccade/charts/nl-portal:0.1.0 harbor.example.nl/nl-portal/charts/nl-portal:0.1.0
crane copy ghcr.io/pinkroccade/nl-portal-backend:0.1.0 harbor.example.nl/nl-portal/nl-portal-backend:0.1.0
crane copy ghcr.io/pinkroccade/nl-portal-frontend:0.1.0 harbor.example.nl/nl-portal/nl-portal-frontend:0.1.0
```

Wijs de chart daarna naar Harbor met `global.imageRegistry` en `global.imagePullSecrets` (zie de [chart-README](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/README.md)).
Een Harbor-proxy-cache naar `ghcr.io` kan ook; dan hoef je niet te kopiëren.

## Vereisten

De chart bundelt geen afhankelijkheden. Regel dit vooraf:

| Vereiste | Waarom |
|---|---|
| **Redis** (ACL-gebruiker, zonder persistentie, met TLS) | Sessies van de BFF ([ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)). Zonder bereikbare Redis kan niemand inloggen. |
| **Keycloak** (eigen image met mappers) met een realm per tenant en een `private_key_jwt`-client | De login; zie [Keycloak](./keycloak.md). |
| De **bronnen** van de modules die je aanzet | Open Zaak, Open Object, OpenKlant, OpenProduct, Haal Centraal, ClamAV. |
| Een **Secret** per component met vaste keys | Zie [Secrets](#secrets). |
| De **tenantbestanden** en een **clientsleutel** per tenant | Zie [Tenants](#tenants-en-tenantbestanden). |

**Let op: "stateless" is genuanceerd.** [ADR 0013](../development/adr/0013-stateless-backend-zonder-database.md) zegt dat de backend geen database
en geen persistente state heeft, en dat klopt voor de pods: je kunt ze zonder verlies herstarten en met meerdere replicas draaien. De portaalsessies
staan echter vluchtig in Redis. Redis is dus een harde runtimevereiste (niet meegebundeld) en een uitval van Redis betekent dat ingelogde burgers hun
sessie kwijt zijn.

## Installeren

Maak eerst de Secrets en de tenantbestanden (hieronder), en schrijf dan een values-bestand. Een minimale set:

```yaml
backend:
  existingSecret: nl-portal-backend
  keycloakBrokerUrl: https://login.gemeente.nl
  redis:
    host: redis.redis.svc
    username: nlportal
tenancy:
  tenants:
    base.yaml: |
      omgevingstype: productie
    "00000099000000000001.yaml": |
      oin: "00000099000000000001"
      naam: Gemeente De Bosrand
      domeinen:
        - portaal.debosrand.nl
      authenticatie:
        issuer: https://login.gemeente.nl/realms/debosrand
        sleutelBestand: 00000099000000000001/clientsleutel.jwk.json
  keys:
    - oin: "00000099000000000001"
      existingSecret: nl-portal-sleutel-00000099000000000001
```

```shell
helm install nl-portal helm/nl-portal -n nl-portal -f values.yaml
```

Een ontbrekende verplichte waarde (Secret-naam, Redis-host, Keycloak-URL, tenantbestanden, sleutels) laat het renderen falen, zodat de fout
zichtbaar wordt vóór Argo synct. Het schema (`values.schema.json`) weigert onbekende sleutels en verkeerde types. Voor wat de chart nog niet modelleert
bestaat `backend.extraEnv` en `frontend.extraEnv`. De pods voldoen standaard aan Pod Security "restricted".

## Secrets

De chart maakt geen Secrets. Je wijst per component één bestaand Secret aan; voor de backend is dat `backend.existingSecret`. De keys staan vast en zijn
gedocumenteerd in de [chart-README](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/README.md#secret-keys); per key kun je de naam in je secret store
overschrijven met `backend.secretKeys`. De frontend heeft geen secrets nodig.

Voorbeeld met External Secrets Operator en Vault (KV v2) als backend; de `ExternalSecret` hoort in je base-chart of GitOps-repository, niet in deze chart:

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
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET
      remoteRef: { key: nl-portal/debosrand, property: zaken-secret }
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_CATALOGIAPI_SECRET
      remoteRef: { key: nl-portal/debosrand, property: catalogi-secret }
    - secretKey: NL_PORTAL_GEHEIMEN_00000099000000000001_OBJECTENAPI_TOKEN
      remoteRef: { key: nl-portal/debosrand, property: objecten-token }   # waarde: "Token <token>"
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

Een pod ziet een gewijzigde secretwaarde pas na een herstart. Verhoog na een rotatie `backend.secretVersion` (de waarde komt als annotatie op de pod, dus de pods
herstarten), of gebruik een secret-reloader via `backend.podAnnotations`.

## Tenants en tenantbestanden

NL Portal is multitenant: één release draagt één of meer tenants, en de tenant volgt uit de `Host`-header van het verzoek. De Ingress en de nginx van de
frontend geven `Host` ongewijzigd door; zet de domeinen van de tenants dus in `domeinen` van het tenantbestand en in `ingress.hosts`.

- `tenancy.tenants` bevat de tenantbestanden op bestandsnaam (`base.yaml` en `<oin>.yaml`). Ze bevatten alleen de *namen* van geheimen, nooit de waarden. Heb je de
  bestanden al in een eigen ConfigMap, noem die dan in `tenancy.existingConfigMap`.
- `tenancy.keys` koppelt per OIN een Secret met de clientsleutel. De chart mount die als `/etc/nl-portal/sleutels/<oin>/clientsleutel.jwk.json`; het tenantbestand
  verwijst ernaar met `authenticatie.sleutelBestand` (relatief aan de sleutelmap). Zie [Tenantbestand, flag en secret store](./tenantbestand-flag-en-secret-store.md).
- De geheimen waarnaar een tenantbestand verwijst (`<oin>.zaken-secret`) zijn keys `NL_PORTAL_GEHEIMEN_<OIN>_ZAKEN_SECRET` in het backend-Secret.
- Een wijziging van de tenantbestanden, flags of omgevingsconfiguratie herstart de pods automatisch (checksum-annotaties).

Modules zet je aan onder `backend.modules.<module>.enabled`; de koppelingen en modulegegevens zelf staan in het tenantbestand. De chart weigert een module te renderen
waarvan een afhankelijkheid uit staat (zie [Module dependency guide](./module-dependency-guide.md)). Feature flags lever je als YAML onder `backend.featureFlags`; de chart
maakt er het flagbestand van ([Feature flags](./feature-flags.md)).

## Ingress

De Ingress staat standaard uit, zodat je base-chart de routing kan regelen. Zet je hem aan (`ingress.enabled`, met `ingress.hosts`), dan gaan `/graphql`, `/api`, `/oauth2`,
`/login` en `/logout` naar de backend en de rest naar de frontend. Bouw je de routing zelf, doe dan hetzelfde en geef `Host` ongewijzigd door.

De actuator staat op een eigen poort (`backend.management.port`, standaard 8091) die nooit in de Service of de Ingress zit. Publiceer die poort niet. De probes gebruiken de
health-groups op die poort. Zie [Beheerpoort: modulestatus](./beheer-modulestatus.md).

Als de Ingress het enige adres is dat doorstuurheaders mag zetten, zet je de CIDR in `backend.trustedProxies`; laat de lijst anders leeg.

## Upgraden

1. Lees de wijzigingen van de nieuwe chartversie en de appversie.
2. Werk `appVersion`/`image.tag` bij (de chart gebruikt standaard de `appVersion` als tag; zet voor een vaste uitrol een `image.digest`).
3. Draai `helm upgrade` (of laat Argo CD syncen). De Deployments gebruiken een rolling update; de readiness-probe op de management-poort bepaalt wanneer een pod verkeer krijgt.
4. Controleer `GET /actuator/modulestatus` op de beheerpoort als je `backend.beheer` hebt aangezet.

Een breaking wijziging in de values is een major-bump van de chart. Valideer je values altijd tegen het schema: `helm lint --strict helm/nl-portal -f values.yaml`.

## Lokaal blijft Docker Compose

Voor lokale ontwikkeling draait Redis in [docker-compose](./opzetten-nl-portal.md); de frontend-nginx wijst daar standaard naar de backend op `localhost:8080`
(`NL_PORTAL_BACKEND_UPSTREAM`). In de chart wijst hij naar de backend-Service.
