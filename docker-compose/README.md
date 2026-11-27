# NL Portal met Docker Compose

NL Portal is een open source portaal waarin inwoners en ondernemers hun zaken, taken, berichten en
persoonsgegevens bij de gemeente inzien. Deze map start de portal lokaal, met Keycloak als
inlogvoorziening en een fictieve gemeente: **Gemeente De Bosrand**.

## Snel starten

Vereist: Docker met Compose 2.24 of hoger en [mise](https://mise.jdx.dev/) (of Node.js 24).

```shell
mise run dev:setup          # eenmalig, in de repo-root: willekeurige secrets, realm en sleutelpaar
mise run keycloak:image     # eenmalig: het eigen Keycloak-image (met de NL Portal-mappers)
cd docker-compose
docker compose up -d --build
```

Open <http://localhost:3000> en log in als `burger` / `burger` (BSN 999993847). Het inloggen loopt via de
backend (BFF): de browser krijgt alleen een `HttpOnly`-sessiecookie en nooit een token. De host bepaalt de
tenant: open het portaal via `localhost` of `debosrand.localhost` (de domeinen van de tenant). De beheerder
van Keycloak (<http://localhost:8082/admin>) is `admin` met `KEYCLOAK_ADMIN_PASSWORD` uit `.env`.

Backend en frontend worden lokaal gebouwd: de gepubliceerde images (`ghcr.io/pinkroccade`) bevatten de BFF nog niet.
De standaardopzet is frontend, backend, Keycloak en de sessiestore (Redis met ACL). Persoonsgegevens verschijnen
met de BRP-mock:

```shell
docker compose --profile brp-mock up -d
```

## Demo: zaken en taken

```shell
docker compose -f compose.yaml -f compose.demo.yaml up -d
```

Start daarnaast Open Zaak, Open Object en Open Notificaties met demo-fixtures en zet de modules voor zaken en
taken aan (`imports/flags/nl-portal.demo.flagd.json`). Het laden van de fixtures duurt de eerste keer een paar minuten.
De demo start ook de BRP-mock en een mock van het Sociaal Portaal (<http://localhost:8095>).

### Taak-demo: externe taak naar de backoffice

Een externe taak komt in Open Object en verschijnt voor de burger in NL Portal. De link in de taak opent de mock van het
Sociaal Portaal, een statische landingspagina die de backoffice voorstelt (`imports/sociaal-portaal-mock/index.html`).

```shell
bash voorbeelden/taak/taak-aanmaken.sh                       # vanuit docker-compose/
bash voorbeelden/taak/taak-aanmaken.sh "Eigen titel" 999993847   # eigen titel en BSN
```

Log daarna in op <http://localhost:3000> als `burger` / `burger` (BSN 999993847) en open de taak. Het script leest
`OBJECTS_API_TOKEN` uit `.env` en maakt een Taak V2 met `soort: url` aan.

## Optionele profielen

| Profiel | Start | Poort |
|---|---|---|
| `brp-mock` | BRP Personen-mock | 5010 |
| `bewoning` | Bewoning-mock | 5011 |
| `openzaak` | Open Zaak met fixtures | 8001 |
| `objecten` | Open Object en Open Notificaties met fixtures | 8010, 8012 |
| `openklant` | Open Klant met fixtures | 8014 |
| `openproduct` | Open Product met fixtures | 8015 |
| `clamav` | ClamAV-virusscanner | 3310 |

Profielen combineer je vrij: `docker compose --profile openzaak --profile clamav up -d`. De koppelingen
(URL's en verwijzingen naar secrets) staan in het tenantbestand `imports/tenants/00000099000000000001.yaml`.

## Poorten en netwerk

Elke service publiceert zijn eigen poorten op `127.0.0.1`; `docker compose ps` toont ze.

| Poort | Service | Variabele |
|---|---|---|
| 3000 | frontend | `FRONTEND_PORT` |
| 8080 | backend (API en GraphQL) | `BACKEND_PORT` |
| 8091 | backend-beheerpoort (actuator, modulestatus) | `BEHEER_PORT` |
| 8082 | Keycloak | `KEYCLOAK_PORT` |
| 6379 | sessiestore (alleen voor een `bootRun` op de host) | |
| 8000 | debugger van de backend, alleen dev-variant | `JDWP_PORT` |

Binnen de stack bereiken services elkaar via hun naam op het netwerk `mijngemeente` (`NL_PORTAL_NETWORK`), bijvoorbeeld
`http://nl-portal-openzaak:8001`. De browser bereikt Keycloak op `localhost:8082`; de backend bereikt het via het
netwerk (`interneBasisUrl` in het tenantbestand) en valideert de issuer van de tenant, ook als het token intern is opgehaald.

## Eigen URL's en mocks

Wijs een koppeling naar een eigen API door de `url` in het tenantbestand te wijzigen en de backend te herstarten.
Een eigen mock sluit als extern netwerk aan op `mijngemeente`; zie [voorbeelden/mockserver](voorbeelden/mockserver/compose.yaml) en de [beheerpagina](../site/hosting-en-configuratie/lokaal-draaien-met-compose.md).

## Dev-variant en bootRun

```shell
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

Bouwt backend en frontend uit deze werkboom met een debugger op poort 8000; combineer met `-f compose.demo.yaml`.
Wie de apps buiten Docker draait (`./gradlew :app-dev:bootRun`, `pnpm dev`), start de diensten zonder app-containers met
`mise run dev:diensten`; `dev:setup` schrijft daarvoor een tenantbestand met localhost-adressen naar `.generated/tenants-host`.

## Smoke-poort (CI)

`mise run smoke:images` bouwt backend, frontend en Keycloak als `nl-portal-*:smoke` (in CI uit de stage-bundels van de run met
`SMOKE_BUILD_MODE=ci`); `mise run smoke:stack` start deze compose ermee onder projectnaam `nlp-smoke`, wacht op health en draait
de login van `burger` op De Bosrand met een GraphQL-verzoek erachteraan. De images zijn in te stellen met
`NL_PORTAL_BACKEND_IMAGE`, `NL_PORTAL_FRONTEND_IMAGE` en `NL_PORTAL_KEYCLOAK_IMAGE`. De poorten zijn vast: stop een draaiende
quickstart eerst. In CI: `.github/workflows/_smoke-stack.yml` (#524).

## Configuratie

| Wat | Waar |
|---|---|
| Backend | `imports/backend.env` |
| Frontend | `imports/frontend.env` |
| Tenant: domeinen, inlog, koppelingen | `imports/tenants/` ([uitleg](../site/hosting-en-configuratie/tenantbestand-flag-en-secret-store.md)) |
| Feature flags | `imports/flags/` ([referentie](../site/hosting-en-configuratie/feature-flags.md)) |
| Secrets | `.env`, gegenereerd door `mise run dev:setup` |

`mise run dev:setup` opnieuw draaien geeft nieuwe secrets en een nieuw sleutelpaar; start de stack daarna leeg met
`docker compose down -v`.

## Meer

- Keycloak, realm en inlogmiddelen: [keycloak.md](../site/hosting-en-configuratie/keycloak.md)
- Demo-fixtures bijwerken: [demo-fixtures.md](../site/development/demo-fixtures.md)
- Performancetesten (`compose.perf.yaml`): [performance-testing.md](../site/development/workflows/performance-testing.md)
- Productie-inrichting: [deployment guide](../site/hosting-en-configuratie/deployment-guide.md)
- Op Windows: zet `core.autocrlf` op `false`, anders falen de init-scripts op `\r`.
