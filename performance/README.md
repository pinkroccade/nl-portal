# Performancetests (k6)

Performance- en loadtests voor NL Portal met [Grafana k6](https://k6.io/), zie
[ADR 0018](../site/development/adr/0018-performancetesten-met-k6-en-otel-agent-in-overlay.md) en epic #428.
k6 staat in `mise.toml`; de scripts zijn TypeScript en draaien direct onder k6 (geen build- of npm-stap).

## Indeling

| Pad | Inhoud |
|---|---|
| `lib/config.ts` | Configuratie uit de omgeving (URL's, realm, client, testgebruiker) |
| `lib/auth.ts` | Tokenflow: access token via Keycloak (direct access grant) |
| `lib/graphql.ts` | GraphQL-request en foutdetectie op een HTTP 200-antwoord |
| `lib/thresholds.ts` | Gedeelde thresholds (pass/fail) en de thresholds per GraphQL-operation van de journeys |
| `lib/profielen.ts` | Belastingsprofielen `smoke`, `load` en `stress` |
| `lib/queries.ts` | GraphQL-queries van de journeys |
| `seed/` | Deterministisch seed-script (`plan.ts` is het pure plan, `seed.ts` voert het uit) en de definitie van de perf-only Keycloak-client |
| `scenarios/smoke.ts` | Smoke: 1 gebruiker, 1 iteratie |
| `scenarios/journeys.ts` | Read-only journeys: inloggen, zakenlijst, zaakdetail, taken en profiel (BRP) |
| `seed/brp.ts` | Genereert de BRP-testdata voor de seed-gebruikers |
| `lib/baseline.ts` | Meetwaarden per operation uit een k6-summary en de vergelijking met de baseline |
| `baseline/` | De baseline (`load.json`) en de scripts voor meten, vastleggen en vergelijken |

## Seed-data en perf-client

`mise run perf:seed` vult de draaiende stack (profiel `zgw`, Keycloak, Open Zaak en Objecten API) met een vast volume:

- testgebruikers `burger-001`, `burger-002`, ... in Keycloak, elk met een eigen BSN (elfproef, buiten de BSN's van de demodata) en een gedeeld wachtwoord;
- per gebruiker zaken (met rol, status en documenten) in Open Zaak en taken (Taak V2) in de Objecten API;
- de client `nl-portal-perf` (publiek, direct access grants, alleen voor loadtests). Die staat bewust **niet** in de standaard realm-import `docker-compose/imports/keycloak`; het seed-script maakt hem aan via de Keycloak-admin-API uit `seed/keycloak/nl-portal-perf-client.json`.

De data is deterministisch: gebruikersnaam, BSN, omschrijvingen en datums volgen uit het volgnummer, nooit uit klok of toeval. Twee runs op een lege stack geven dus dezelfde data (zaak- en documentidentificaties, titels, datums, BSN's); alleen de door Open Zaak zelf gegenereerde UUID's verschillen. Een tweede run op een al geseede stack slaat bestaande gebruikers, zaken en taken over; bij een afwijkend aantal stopt hij met de melding een lege stack te starten (`docker compose down -v`).

```shell
mise run perf:seed
PERF_USERNAME=burger-001 PERF_PASSWORD=perf-wachtwoord PERF_CLIENT_ID=nl-portal-perf mise run perf:smoke
```

| Variabele | Standaard |
|---|---|
| `PERF_SEED_GEBRUIKERS`, `PERF_SEED_ZAKEN`, `PERF_SEED_TAKEN`, `PERF_SEED_DOCUMENTEN` (per zaak) | 10, 5, 2, 2 |
| `PERF_SEED_PASSWORD` (valt terug op `PERF_PASSWORD`) | `perf-wachtwoord` |
| `PERF_KEYCLOAK_ADMIN`, `PERF_KEYCLOAK_ADMIN_PASSWORD` | `admin`, het gegenereerde `KEYCLOAK_ADMIN_PASSWORD` uit `docker-compose/.env` |
| `PERF_OPENZAAK_URL`, `PERF_OBJECTEN_URL` | `http://localhost:8001`, `http://localhost:8010` |
| `PERF_ZGW_CLIENT_ID`, `PERF_ZGW_SECRET`, `PERF_OBJECTEN_TOKEN` | `nl-portal`, de gegenereerde `OPENZAAK_NLPORTAL_SECRET` en `OBJECTS_API_TOKEN` uit `docker-compose/.env` |

Secrets en tokens hebben geen vaste standaardwaarde: `mise run perf:seed` leest ze uit `docker-compose/.env` (draai eerst `mise run dev:setup`). De overige standaardwaarden zijn lokale demo-gegevens; zet voor een andere omgeving eigen waarden in `mise.local.toml`. De BSN's van de seed-gebruikers staan niet in de demodata van de BRP-mock. De perf-overlay (`docker-compose/compose.perf.yaml`) mount daarom `docker-compose/imports/haalcentraal-perf/test-data.json` met een persoon per seed-gebruiker; `mise run perf:brp-data` genereert dat bestand opnieuw.

## Draaien

Start eerst de compose-stack (zie [docker-compose/README.md](../docker-compose/README.md)) en geef een
bestaande testgebruiker op. Zet de waarden in je eigen (gitignored) `mise.local.toml` onder `[env]`:

```shell
PERF_USERNAME=<gebruiker> PERF_PASSWORD=<wachtwoord> mise run perf:smoke
```

| Variabele | Standaard |
|---|---|
| `PERF_USERNAME`, `PERF_PASSWORD` | verplicht |
| `PERF_BACKEND_URL` | `http://localhost:8080` |
| `PERF_KEYCLOAK_URL` | `http://localhost:8082/auth` |
| `PERF_REALM` | `nlportal` |
| `PERF_CLIENT_ID` | `nl-portal` |

`perf:smoke` eindigt met een exitcode ongelijk aan 0 bij een falend verzoek, een falende check of een
gemiste threshold.

## Journeys en belastingsprofielen

`scenarios/journeys.ts` logt per VU in als een eigen seed-gebruiker (`burger-001`, ... rondgewisseld) en bevraagt de zakenlijst, een zaakdetail, de taken en het profiel. Elk verzoek krijgt de operationnaam als `name`-tag, zodat k6 p95, foutpercentage en doorvoer per GraphQL-operation rapporteert.

```shell
PERF_PASSWORD=perf-wachtwoord PERF_CLIENT_ID=nl-portal-perf mise run perf:journeys   # profiel smoke
PERF_PASSWORD=perf-wachtwoord PERF_CLIENT_ID=nl-portal-perf mise run perf:load
PERF_PASSWORD=perf-wachtwoord PERF_CLIENT_ID=nl-portal-perf mise run perf:stress
```

| Variabele | Standaard |
|---|---|
| `PERF_PROFILE` | `smoke` (1 gebruiker, 1 iteratie); `load` en `stress` via hun eigen taak |
| `PERF_PEAK_VUS` | 10 bij `load`, 100 bij `stress` |
| `PERF_USERS`, `PERF_USERNAME_PREFIX` | 10, `burger-` |
| `PERF_THINK_TIME` (seconden) | 1 |
| `PERF_BRP` | `true`; `false` slaat de profiel/BRP-stap over |

De thresholds van de journeys (ook per operation op fouten en aantal verzoeken, zodat k6 die submetrics exporteert) zijn voorlopig signalerend (p95 < 2 s per operation, < 1% fouten) en worden aangescherpt zodra er een baseline is (#432).

## Taken

- `mise run perf:seed`: de seed hierboven.
- `mise run perf:smoke`: de smoke-test tegen de draaiende stack.
- `mise run perf:journeys`, `perf:load`, `perf:stress`: de journeys met profiel smoke, load of stress.
- `mise run perf:brp-data`: genereert de BRP-testdata voor de perf-overlay.
- `mise run perf:wacht`: wacht tot de stack klaar is voor `perf:seed` (Keycloak, imports en backend); ook de nachtelijke workflow gebruikt hem.
- `mise run perf:meet`: draait de journeys `PERF_RUNS` keer (standaard 3) en bewaart de k6-summaries in `performance/resultaten/`.
- `mise run perf:baseline`: legt de mediaan vast in `baseline/<profiel>.json` (alleen via een PR).
- `mise run perf:vergelijk`: delta t.o.v. de baseline, met een waarschuwing bij verslechtering.

- `mise run perf:test`: unit-tests van de helpers (`lib/*.test.ts` en `seed/*.test.ts`, `node --test`); onderdeel van `mise run test`.
- `mise run perf:check`: laadt het script zonder stack (syntax en imports); onderdeel van `mise run lint`.

De perf-overlay en de overige profielen volgen in de andere sub-tickets van #428.
