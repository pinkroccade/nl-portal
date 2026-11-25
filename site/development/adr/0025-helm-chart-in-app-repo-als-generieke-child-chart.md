# 25. Helm-chart in de app-repo als generieke child-chart

## Status

Geaccepteerd (2026-11-25). De chart staat er al (#570, #583, #599); deze ADR legt de keuzes achteraf vast. Epic #191, ticket #192.
Het contract met de base-chart is nog niet beproefd en wordt pas definitief na de eerste beproeving (zie "Open punten voor de beproeving").

## Context

NL Portal deployt zelf niets naar productie: afnemers rollen uit, in het geval van Pink via ArgoCD met een eigen
**base-chart** van DevOps die het clusterbeleid regelt (routing, NetworkPolicy, secrets uit Vault). Zo'n afnemer heeft een
uitrolbaar artefact nodig dat de applicatie beschrijft zonder dat beleid te dupliceren. De chart is ontworpen zonder
de base-chart te kennen.

Deze ADR bevat alleen besluiten die moeilijk terug te draaien zijn: ze bepalen waar de chart woont, hoe hij verspreid wordt
en welk contract afnemers erop bouwen. De volledige tabel met ontwerpkeuzes en uitleg staat in
[Uitleg: ontwerpkeuzes van de chart](../../hosting-en-configuratie/kubernetes/uitleg-ontwerpkeuzes.md); die wordt hier niet herhaald.

## Beslissing

1. **De chart staat in de app-repo (`helm/nl-portal`)**, niet in een aparte charts-repo. Een wijziging van app en chart gaat in één PR.
2. **Distributie als OCI-artifact op ghcr, gesigneerd met cosign.** Een vaste versie, verifieerbare herkomst, geen index om te beheren.
   Consumenten pinnen de chartversie (ook bij Harbor, met behoud van de handtekening).
3. **De chart is een generieke child-chart**: alleen de applicatie (backend en frontend). De base-chart is de parent en neemt de
   chart op als dependency onder de alias `nl-portal`. De chart bundelt geen subcharts en maakt geen Secret, NetworkPolicy,
   PodDisruptionBudget, HPA, ExternalSecret, ServiceMonitor of Argo-resource.
4. **Secret-contract: geen secrets in de chart.** Per component één bestaand Secret (`backend.existingSecret`, verplicht) met vaste keys;
   de keynamen zijn te overschrijven via `backend.secretKeys`. Per tenant een Secret met de clientsleutel
   (`tenancy.keys[].existingSecret`). De base-chart vult die Secrets via External Secrets Operator uit Vault. De chart kan ze niet lezen
   (geen `lookup`) en faalt bij het renderen alleen op een ontbrekende `existingSecret`.
5. **Contract met de base-chart**, definitief na de eerste beproeving:
   - Service-namen `<release>-nl-portal-backend` en `-frontend` (aan te passen met `nameOverride` of `fullnameOverride`).
   - Selector en labels: `app.kubernetes.io/name`, `instance` en `component` vormen de selector en veranderen na installatie niet; daarnaast
     `version`, `part-of`, `managed-by` en `helm.sh/chart`.
   - Globals: alleen `global.imageRegistry` en `global.imagePullSecrets` worden gelezen.
   - De Ingress staat standaard uit; de base-chart regelt de routing en geeft `Host` ongewijzigd door (de tenant volgt daaruit).
   - Multitenancy: `tenancy.tenants` (niet-geheime tenantbestanden, alleen namen van geheimen) en de clientsleutel per tenant
     ([ADR 0021](./0021-gedeelde-multi-tenant-runtime-met-cellen.md), [ADR 0022](./0022-backend-for-frontend-als-authenticatiepatroon.md)).
6. **Versiebeleid: eigen SemVer naast de app-releases.** De chartversie in `Chart.yaml` staat los van `appVersion`. Er zijn twee publicatie-triggers:
   een versiebump van `Chart.yaml` op `main` (publiceert, tagt en maakt de GitHub Release; idempotent, en wacht op de images van `appVersion`) en
   de tag `chart-nl-portal-X.Y.Z`, die de app-release zet nadat de images bestaan. Die tags botsen niet met de app-tags `X.Y.Z`.
   Dit beschrijft de huidige situatie. [ADR 0019](./0019-trunk-based-continuous-delivery.md) (build per commit, promotie op digest) is de richting;
   een toezegging over hoe chart en promotie samengaan is dit niet.

### Alleen een verwijzing, geen eigen besluit

- De actuator draait op een aparte poort (8091) buiten de Service; zie [Beheerpoort: modulestatus](../../hosting-en-configuratie/beheer-modulestatus.md).
- Niets meebundelen (geen Redis, Keycloak of database) volgt uit [ADR 0013](./0013-stateless-backend-zonder-database.md).
- Redis is een vereiste voor sessies: [ADR 0017](./0017-vluchtige-state-toegestaan.md) en [ADR 0022](./0022-backend-for-frontend-als-authenticatiepatroon.md).

## Afgewezen alternatieven

- **Een aparte charts-repo.** Levert alleen iets op bij een centrale catalogus of een ander eigenaarsteam; hier kost elke app-wijziging twee PR's in twee repo's.
- **Een fork van de upstream-charts.** Erft onderhoud, een eigen updateritme en meegebundelde (verouderde) Bitnami-subcharts, en moet het clusterbeleid van de base-chart dupliceren.
- **Meegebundelde subcharts** (Redis, Keycloak, database). Die hebben een eigen beheer en updateritme; er is geen database (ADR 0013).
- **argocd-vault-plugin.** Zet secrets in het gerenderde resultaat en maakt de render afhankelijk van Vault; dat botst met de deterministische render en met
  "geen secrets in de chart". ESO houdt Vault buiten de render.

## Consequenties

- **Eén-weg-deur:** gepubliceerde chartversies en hun tags kunnen niet terug. Het secret-contract (keynamen), de Service-namen en de selectorlabels zijn voor afnemers
  een API; een wijziging daarvan is een breaking change van de chart (SemVer-major).
- **Elke chartwijziging vraagt een versiebump**; zonder bump publiceert er niets.
- **Afnemers richten de base-chart zelf in**: routing, NetworkPolicy, PDB, HPA, ServiceMonitor en ExternalSecret zitten niet in de chart.

## Open punten voor de beproeving

De aannames over de base-chart staan als checklist in [de beproeving met de base-chart](../../hosting-en-configuratie/kubernetes/how-to-beproeving.md) en zijn pas
definitief na die beproeving. De belangrijkste voor dit besluit:

- Klopt de alias `nl-portal`, en bouwt de base-chart op de Service-namen en selectorlabels?
- Zijn `global.imageRegistry` en `global.imagePullSecrets` genoeg, of leest de base-chart meer globals?
- Passen de secret-keynamen in de Vault-indeling, of zijn de overrides in `backend.secretKeys` genoeg?
- Waar horen de tenantbestanden: per omgeving in de GitOps-repo of centraal?
- Werkt de OCI-sync met een gepinde versie in Argo CD met de plugins en `valuesObject` van de base-chart?
- **`appVersion` en de image-tag als ADR 0019 landt.** Nu wijst `appVersion` naar een vaste appversie, terwijl ADR 0019 images op digest of `<versie>-<sha>` promoveert
  en `version.txt` laat vervallen. Open: blijft `appVersion` de standaard image-tag, komt er een digest-override in de values, en wie bepaalt de combinatie
  chartversie/app-digest (de chart-release of de GitOps-repo)? Nog niet besloten; dit hoort bij de uitwerking van epic #604 (#610).
