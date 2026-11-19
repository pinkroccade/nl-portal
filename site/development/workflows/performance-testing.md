# Performancetesten

Hoe je de performance van de backend meet, een baseline bijwerkt en de nachtelijke resultaten leest. De
keuzes staan in [ADR 0018](../adr/0018-performancetesten-met-k6-en-otel-agent-in-overlay.md); de scripts en
alle variabelen in [`performance/README.md`](https://github.com/pinkroccade/nl-portal/blob/main/performance/README.md).

## Wat er gemeten wordt

Read-only journeys van een ingelogde burger, zonder browser, via GraphQL met een echte tokenflow:
inloggen (Keycloak), zakenlijst (`getZaken`), zaakdetail (`getZaak`), taken (`getTakenV2`) en profiel
(`getPersoonV2`, BRP). k6 rapporteert per GraphQL-operation de p95-latency, het foutpercentage en de doorvoer.

| Profiel | Belasting | Taak |
|---|---|---|
| smoke | 1 gebruiker, 1 iteratie | `mise run perf:journeys` |
| load | oplopend naar `PERF_PEAK_VUS` (standaard 10), 5 minuten vasthouden | `mise run perf:load` |
| stress | in stappen naar `PERF_PEAK_VUS` (standaard 100) | `mise run perf:stress` |

## Lokaal draaien

Vereist Docker. Van een lege stack tot een vergelijking met de baseline:

```shell
mise run dev:setup            # verse secrets; daarna altijd met lege volumes starten
mise run perf:stack-down      # oude volumes weg (wachtwoorden passen anders niet meer)
mise run perf:stack-up        # compose-stack met de perf-overlay, zonder tracing
mise run perf:wacht           # tot Keycloak, de imports en de backend klaar zijn
mise run perf:seed            # 10 testgebruikers met zaken, documenten en taken
PERF_PASSWORD=perf-wachtwoord PERF_CLIENT_ID=nl-portal-perf mise run perf:meet       # 3 runs, profiel load
mise run perf:vergelijk       # delta t.o.v. performance/baseline/load.json
```

`perf:meet` bewaart de k6-summaries in `performance/resultaten/` (niet in Git). `perf:vergelijk` toont per
operation de mediaan van de runs naast de baseline, met een waarschuwing bij een p95 die meer dan 30% stijgt,
een doorvoer die meer dan 30% daalt of een foutpercentage dat meer dan 1 procentpunt stijgt. Die marges
liggen boven de ruis van gedeelde runners (±20–30%).

## Een scenario toevoegen

1. Zet de query in `performance/lib/queries.ts`; de sleutel is de operationnaam en ook de `name`-tag in k6.
2. Voeg de operation toe aan `OPERATIES` in `performance/lib/thresholds.ts`. Pas dan exporteert k6 de
   submetrics per operation en neemt `perf:vergelijk` hem mee.
3. Roep hem aan in `performance/scenarios/journeys.ts` en controleer met `mise run perf:check` en een
   smoke-run.
4. Werk de baseline bij (hieronder): een nieuwe operation zonder baseline wordt niet vergeleken.

## De baseline bijwerken

De baseline staat in `performance/baseline/load.json` en verandert alleen via een PR. Na `perf:meet`:

```shell
PERF_OMGEVING="<waar gemeten>" mise run perf:baseline
```

Vermeld in de PR waarom de baseline verandert (bewuste verbetering, nieuwe operation, andere runner) en
waar gemeten is: een meting op een laptop is niet vergelijkbaar met een GitHub-hosted runner. Neem bij
voorkeur de mediaan uit het artifact van een nachtelijke run als die de referentie moet worden.

## Nachtelijke resultaten lezen

De workflow [`performance.yml`](https://github.com/pinkroccade/nl-portal/blob/main/.github/workflows/performance.yml)
draait elke nacht op `main`, bij elke push naar een `rc/*`-branch en handmatig. Hij bouwt de stack uit de
broncode, seedt, meet 3 keer met profiel load en zet de vergelijking als tabel in de workflow-summary.
Een verslechtering verschijnt als waarschuwing (`::warning::`) boven de summary; de workflow blijft groen.
De k6-summaries staan als artifact `k6-summaries` bij de run.

Een waarschuwing is een signaal, geen oordeel: kijk of hij de volgende nacht terugkomt voordat je zoekt.
Komt hij terug, bisect dan op de commits sinds de laatste groene nacht en gebruik tracing (hieronder) voor
het waarom.

## Tracing aanzetten

Voor de vraag *waarom* iets traag is, draai je de stack met de OpenTelemetry Java-agent en Tempo:

```shell
mise run perf:stack-up-tracing
```

De agent zit alleen in de perf-overlay, niet in het release-artefact. Meet met tracing aan nooit een
baseline: de agent vertraagt de backend.
