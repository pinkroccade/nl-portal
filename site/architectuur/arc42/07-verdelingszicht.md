# 7. Verdelingszicht (deployment view)

## 7.1 Lokale ontwikkeling

`docker-compose/` start de portal op dezelfde manier als bij livegang: elke service een eigen hostname op
een benoemd netwerk (`mijngemeente`) en eigen poorten op `127.0.0.1`.

| Opzet | Wat draait er |
|---|---|
| `compose.yaml` | Frontend, backend, Keycloak (realm `de-bosrand`) en de module Haal Centraal BRP Personen; gepubliceerde images van GHCR |
| Profielen | `brp-mock`, `bewoning`, `openzaak`, `objecten`, `openklant`, `openproduct`, `clamav`: elk los op te starten |
| `compose.demo.yaml` | Plus Open Zaak, Open Object en Open Notificaties met demo-fixtures en de ZGW-modules aan |
| `compose.dev.yaml` | Backend en frontend gebouwd uit de sources, met debugger |

De browser haalt tokens bij Keycloak via `localhost`; de backend bereikt Keycloak via het netwerk
(`KEYCLOAK_TOKEN_URI`, `JWKS_URI`) en controleert de issuer. Zie
[`docker-compose/README.md`](https://github.com/pinkroccade/nl-portal/blob/main/docker-compose/README.md) voor
poorten en profielen.

## 7.2 Build en packaging

- **Backend**: Gradle multi-module build → `nl-portal-backend`-image (`backend/Dockerfile`).
- **Frontend**: pnpm-workspace build → `nl-portal-frontend`-image (`frontend/Dockerfile`).
- **Bibliotheken**: de backendmodules en de `@nl-portal/*`-pakketten worden niet los gepubliceerd
  (geen Maven Central of npm); alleen de twee images worden uitgeleverd.
- **CI** (`.github/workflows/`): `ci.yml` (algemene build/test), `_stage-be.yml`/`_stage-fe.yml`
  (buildartefacten + reports/SBOM), `build-and-publish-*.yml` (snapshot/release-candidate/release),
  `sonarcloud.yml` (kwaliteitspoort), `dependency-check.yml` (beveiligingspoort — zie deliverable 6).

## 7.3 Productie

Buiten deze repository: Helm-charts rollen de twee images (backend, frontend) uit op Kubernetes.
Ritense's `nl-portal/helm-charts` werd hier eerder voor gebruikt; pinkroccade's eigen equivalent is
nog niet ingericht, zie [epic #191](https://github.com/pinkroccade/nl-portal/issues/191). Dit
arc42-document beschrijft die laag niet in detail, maar benoemt wel de aanname die eruit volgt:
**NL Portal heeft geen eigen
database**: de backend vereist geen persistentielaag. Een BFF vraagt wel Redis voor sessies, zonder back-up ([ADR 0017](../../development/adr/0017-vluchtige-state-toegestaan.md)). Elke overheidsorganisatie
draait haar eigen instantie tegen haar eigen ZGW-landschap.

## 7.4 Voorgesteld doelbeeld: gedeelde runtime met cellen

> **Geaccepteerd (2026-11-21), nog niet gebouwd of ingericht.** Dit beschrijft het doelbeeld uit
> [ADR 0021](../../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md). Paragraaf 7.3 blijft het
> geldende beeld totdat die ADR is uitgevoerd; er draait nog niets in productie.

Pink biedt NL Portal als SaaS aan voor circa 50 gemeenten (tenants) binnen twee jaar. In plaats van een
instantie per gemeente (7.3) draait één multi-tenant runtime, ingedeeld in **cellen**:

- Een **cel** is dezelfde runtime (zelfde images en chart): een Helm-release met eigen values (cel,
  replica's, tenantlijst) voor een groep tenants. De uitrolvolgorde canarycel naar hoofdcel, met wachttijd
  en health-controle, is een eis aan de gitops-tooling en geen onderdeel van de chart.
  Een cel laadt alleen de tenants en secrets van die cel, en beperkt zo de gevolgen van een fout of
  kwetsbaarheid tot die cel.
- Livegang met minimaal twee productiecellen: een **canarycel** (Pink-eigen tenant en één of twee
  pilotgemeenten) en een **hoofdcel**. Een release gaat eerst naar de canarycel en daarna, na vaste
  controles, naar de hoofdcel. Een tenant met hogere eisen of een eis tot fysieke scheiding krijgt een eigen
  cel (een cel met één tenant).
- Per cel minimaal twee replica's, een PodDisruptionBudget en spreiding over nodes of zones.
- Verkeer loopt via de Kubernetes Gateway API (`HTTPRoute` per gemeentedomein, naar de juiste cel). De
  host-allowlist en het certificaat per tenant horen op die edge. De runtime is alleen via de ingress te
  bereiken; de actuator draait op een aparte managementpoort.
- Per omgeving twee gedeelde Keycloaks: een **publieke ID-broker** (met een realm per tenant, beheerd als code
  via gitops) en een **private ID-broker**, waarvan de portal-runtime nooit tokens accepteert.
- Configuratie komt per tenant uit een bestand in Git (uitgerold door gitops-tooling); secrets via External Secrets Operator,
  per tenant een eigen pad. Elke wijziging is een rolling restart van de eigen cel. Zie
  [8.9](./08-dwarsdoorsnede-concepten.md#89-tenantscheiding-en-configuratiemodel-voorgesteld).

**Context: ArgoCD.** De beoogde gitops-tool is ArgoCD. De Helm-charts blijven gitops-tool-agnostisch; Argo is
alleen de context waartegen we verifiëren dat de generieke charts niet botsen met Argo-best practices (zie
ADR 0021, "Vóór livegang").

De backend heeft geen database en bewaart geen persistente state
([ADR 0013](../../development/adr/0013-stateless-backend-zonder-database.md)); "stateless" geldt in die zin. Een cel vereist wel
Redis voor de BFF-sessies ([ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)).

**Stand van de runtime.** Er draait nu één tenant per cel: een tweede tenant weigert de loader
(`MEERDERE_TENANTS_TOEGESTAAN = false`) en de modulegate (T22) dwingt nog niet af. Zie de vervolgtickets
[#521](https://github.com/pinkroccade/nl-portal/issues/521) en [#522](https://github.com/pinkroccade/nl-portal/issues/522).

## 7.5 Open vraag

Er is geen architectuurdocument gevonden dat de productie-topologie (hoeveel replica's, welke
externe systemen zijn per-omgeving vs. gedeeld, hoe secrets worden aangeleverd) beschrijft binnen
déze repository. Als dat elders bestaat (bijv. in het helm-charts-repository), is een verwijzing
hiernaar een goede eerste aanvulling op dit hoofdstuk.
