# How-to: installeren met helm install of Argo CD

*Diátaxis: how-to.* Een referentie-installatie. Voor productie neem je de chart op in een [base-chart](./how-to-base-chart.md).

Maak eerst de Secrets ([Secrets uit Vault via ESO](./how-to-secrets-eso.md)) en de tenantbestanden, en schrijf dan een values-bestand. Een minimale set:

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
      thema:
        titel: Mijn De Bosrand
      authenticatie:
        issuer: https://login.gemeente.nl/realms/debosrand
        sleutelBestand: 00000099000000000001/clientsleutel.jwk.json
  keys:
    - oin: "00000099000000000001"
      existingSecret: nl-portal-sleutel-00000099000000000001
```

```shell
helm install nl-portal oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0 -n nl-portal -f values.yaml
```

Een ontbrekende verplichte waarde (Secret-naam, Redis-host, Keycloak-URL, tenantbestanden, sleutels) laat het renderen falen, zodat de fout
zichtbaar wordt vóór Argo synct. Het schema (`values.schema.json`) weigert onbekende sleutels en verkeerde types. Voor wat de chart nog niet modelleert
bestaat `backend.extraEnv` en `frontend.extraEnv`. De pods voldoen standaard aan Pod Security "restricted".

## Een Argo CD-`Application`

Argo CD haalt de chart rechtstreeks uit het OCI-register. Pin de `targetRevision`; de values staan in de GitOps-repository, zonder secrets.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: nl-portal
  namespace: argocd
spec:
  project: default
  source:
    repoURL: ghcr.io/pinkroccade/charts   # OCI-register, zonder oci://
    chart: nl-portal
    targetRevision: 0.1.0
    helm:
      valuesObject:
        backend:
          existingSecret: nl-portal-backend
          keycloakBrokerUrl: https://login.gemeente.nl
          redis: { host: redis.redis.svc, username: nlportal }
        # tenancy: zie het voorbeeld hierboven
  destination:
    server: https://kubernetes.default.svc
    namespace: nl-portal
  syncPolicy:
    syncOptions: [CreateNamespace=true]
```

Heb je een base-chart, dan is dit de `Application` van die base-chart en staat NL Portal erin als dependency; zie [de chart opnemen in een base-chart](./how-to-base-chart.md).
De chart rendert deterministisch (geen `lookup`, `rand*`, `now` of hooks) en is daarom veilig voor Argo.

## Tenants en tenantbestanden

NL Portal is multitenant: één release draagt één of meer tenants, en de tenant volgt uit de `Host`-header van het verzoek. De Ingress en de nginx van de
frontend geven `Host` ongewijzigd door; zet de domeinen van de tenants dus in `domeinen` van het tenantbestand en in `ingress.hosts`.

- `tenancy.tenants` bevat de tenantbestanden op bestandsnaam (`base.yaml` en `<oin>.yaml`). Ze bevatten alleen de *namen* van geheimen, nooit de waarden. Heb je de
  bestanden al in een eigen ConfigMap, noem die dan in `tenancy.existingConfigMap`.
- `tenancy.keys` koppelt per OIN een Secret met de clientsleutel. De chart mount die als `/etc/nl-portal/sleutels/<oin>/clientsleutel.jwk.json`; het tenantbestand
  verwijst ernaar met `authenticatie.sleutelBestand` (relatief aan de sleutelmap). Zie [Tenantbestand, flag en secret store](../tenantbestand-flag-en-secret-store.md).
- De geheimen waarnaar een tenantbestand verwijst (`<oin>.zaken-secret`) zijn keys `NL_PORTAL_GEHEIMEN_<OIN>_ZAKEN_SECRET` in het backend-Secret.
- Een wijziging van de tenantbestanden, flags of omgevingsconfiguratie herstart de pods automatisch (checksum-annotaties).

Een tenant per release of meerdere tenants in één release: beide kan; zie [Ontwerpkeuzes](./uitleg-ontwerpkeuzes.md) en
[ADR 0021](../../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md).
