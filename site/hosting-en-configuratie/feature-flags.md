# Feature flags: referentie

Hier staat welke schakelaars NL Portal kent, wat ze doen en waar je ze instelt: de lijst van flags en toggles vind je alleen hier. Hoe je een flagbestand aanwijst, de evaluatiecontext en targeting staan in de [deployment guide](./deployment-guide.md#feature-flags); de keuze achter flags staat in [ADR 0014](../development/adr/0014-feature-flags-via-openfeature.md).

Alle frontend-schakelaars zijn feature flags (flagd-bestand, zie [ADR 0014](../development/adr/0014-feature-flags-via-openfeature.md)). Ze komen samen in het antwoord van `GET /api/public/frontend` onder `toggles`. De eigenschappen `nl-portal.frontend.toggles.*` bestaan niet meer; een omgevingsvariabele voor een toggle werkt dus niet meer. Wijzigingen landen via een rolling update (pods herstarten).

## Feature flags

Evaluatie gebeurt per request met de [evaluatiecontext](./deployment-guide.md#feature-flags) (tenant, omgeving, gebruikerstype). Een flag die in je bestand ontbreekt, valt terug op de standaard in de code (uit).

| Flag | Type | Standaard | Effect |
|---|---|---|---|
| `ops.frontend.message-count` | ops | uit | Toont het aantal ongelezen berichten in de frontend. De frontend haalt het aantal dan periodiek op (`properties.messageCountPollingInterval`, standaard 30000 ms). Via `toggles.messageCountEnabled`. |
| `ops.frontend.cases-partial-search` | ops | uit | Zoeken op zaaknummer vindt ook gedeeltelijke overeenkomsten (`identificatieContains`) in plaats van alleen een exacte. Via `toggles.casesPartialSearchEnabled`. |
| `ops.frontend.open-product` | ops | uit | Haalt Open Product-thema's en producten op en toont ze op het overzicht. Via `toggles.openProductEnabled`. |
| `ops.frontend.cases-result-explanation` | ops | uit | Toont de toelichting op het resultaat van een zaak op de zaakpagina. Via `toggles.casesResultExplanationEnabled`. |
| `ops.frontend.my-inhabitant-count` | ops | uit | Toont het aantal inwoners op het adres op de accountpagina. Via `toggles.myInhabitantCountEnabled`. |
| `ops.frontend.cases-contact-moments` | ops | uit | Haalt de contactmomenten bij een zaak op en toont ze op de zaakpagina. Via `toggles.casesContactMomentsEnabled`. |
| `ops.frontend.overview-intro` | ops | uit | Toont de begroeting met de naam van de gebruiker (en de volmachtgever) bovenaan het overzicht. Via `toggles.overviewIntroEnabled`. |
| `ops.frontend.overview-maintenance-alert` | ops | uit | Toont de onderhoudsmelding op het overzicht; titel en tekst komen uit `properties.overviewMaintenanceAlert{Title,Text}{Nl,En}`. Via `toggles.overviewMaintenanceAlertEnabled`. |
| `ops.frontend.custom-toggles` | ops (object) | leeg | Vrije eigen toggles: een object met booleans, bijvoorbeeld `{ "betaBanner": true }`. De frontend ontvangt het als JSON-tekst in `toggles.custom`. Waarden die geen boolean zijn worden genegeerd. Staat niet in de meegeleverde bestanden; voeg hem toe aan je eigen bestand als je custom toggles nodig hebt. |

## Module-flags

Een backendmodule staat per tenant aan of uit met een permission-flag `permission.<module>.enabled`. Eén centrale gate beslist per
request, op de tenant van het request (de OIN in de evaluatiecontext). Een module is **bruikbaar** als de flag voor die tenant aan
staat **en** het tenantbestand de configuratie van de module bevat (koppelingen en `modules.<naam>`); een flag zonder configuratie
betekent uit, configuratie zonder flag is toegestaan (dark uitrollen). De flag is fail-closed: een flag die ontbreekt of niet te
evalueren is, en een request zonder tenant, weigeren. Is een module niet bruikbaar, dan geeft een GraphQL-query een fout
`Module '<module>' is niet beschikbaar` (`extensions.code` is `MODULE_NOT_AVAILABLE`) en een REST-resource HTTP 503; de velden blijven in het
GraphQL-schema staan. Een endpoint dat niet bij de gate is geregistreerd weigert (deny by default). De `modules`-lijst van
`GET /api/public/frontend` is per tenant en toont alleen de bruikbare modules. De `ops.*`-flags blijven voor noodremmen. Hoe je een module
hierop overzet staat in [het patroon](../development/workflows/module-gate-patroon.md).

Zet de flag voor een tenant aan met targeting op de OIN (zie de [deployment guide](./deployment-guide.md#feature-flags)):

```json
"permission.berichten.enabled": {
  "state": "ENABLED",
  "variants": { "on": true, "off": false },
  "defaultVariant": "off",
  "targeting": { "if": [{ "in": [{ "var": "tenant" }, ["00000099000000000001"]] }, "on", "off"] }
}
```

| Flag | Type | Standaard | Effect |
|---|---|---|---|
| `permission.berichten.enabled` | permission | uit | Zet de module berichten aan voor een tenant: de GraphQL-queries `getBericht`, `getBerichten` en `getUnopenedBerichtenCount` en de download `GET /api/berichten/{berichtId}/document/{documentId}/content`. Bruikbaar als ook `permission.documentenapis.enabled` aan staat (zie de afhankelijkheden hieronder) en de tenant `koppelingen.objectenapi` en `modules.berichten` heeft. Daarnaast heeft hij nog `nl-portal.config.documentenapis.enabled` nodig (blijft een opstartinstelling tot #162). De eigenschap `nl-portal.config.berichten.enabled` bestaat niet meer. |
| `permission.haalcentraal-brp.enabled` | permission | uit | Zet de BRP-module aan voor een tenant: de GraphQL-query `getPersoonV2` (met het aantal bewoners). Bruikbaar als de tenant `koppelingen.brp` en `modules.brp` heeft. Daarnaast heeft hij nog `nl-portal.config.haalcentraal-brp.enabled` nodig (blijft een opstartinstelling tot #162). De modulenaam voor de frontend is `haalcentraal-brp`. |
| `permission.taak.enabled` | permission | uit | Zet de taakmodule aan voor een tenant: de GraphQL-queries en -mutaties voor taken en formulieren en de upload `POST /api/taak/{taakId}/document/content`. Bruikbaar als de tenant `koppelingen.objectenapi` en `modules.taak` heeft. Daarnaast heeft hij nog `nl-portal.config.taak.enabled` en `objectenapi.enabled` nodig (opstartinstellingen tot #162). |
| `permission.zaken.enabled` | permission | uit | Zet de zakenmodule aan voor een tenant: de GraphQL-queries `getZaken` en `getZaak` (met statussen, rollen, besluiten en documenten) en de download `GET /api/zakenapi/zaakdocument/{zaakDocumentId}/content`. Bruikbaar als ook `permission.documentenapis.enabled` aan staat (zie de afhankelijkheden hieronder) en de tenant `koppelingen.zakenapi`, `koppelingen.catalogiapi`, `koppelingen.objectenapi` en `modules.zakenapi` heeft. Daarnaast heeft hij nog `nl-portal.config.zakenapi.enabled` (met `catalogiapi.enabled` en `objectenapi.enabled`) nodig (opstartinstellingen tot #162). De modulenaam voor de frontend is `zaken`. |
| `permission.payment-direct.enabled` | permission | uit | Zet de payment-direct-module aan voor een tenant: de GraphQL-query `getDirectPaymentStatus`, de mutatie `doDirectPayment` en de postsale-webhook `POST /api/public/payment/direct/postsale` die de bank aanroept (de gate beslist per tenant, die volgt uit de host; bij een uitgeschakelde module antwoordt de webhook 503). Bruikbaar als de tenant `modules.payment-direct` heeft; elk profiel verwijst naar een koppeling onder `koppelingen`, wat de tenantvalidatie bewaakt. De webhook heeft daarnaast `koppelingen.objectenapi` en `nl-portal.config.objectenapi.enabled` nodig. De eigenschap `nl-portal.config.payment.direct.enabled` bestaat niet meer. |
| `permission.documentenapis.enabled` | permission | uit | Afhankelijkheidsvlag van de documenten API. Modules die documenten serveren (nu berichten) vereisen hem; de tenant moet `modules.documenten-api` hebben. De module zelf wordt er nog niet door aan- of uitgezet; dat gebeurt nog met `nl-portal.config.documentenapis.enabled`. |

De overige backendmodules (kvk, besluiten, openklant, openproduct) staan nog niet op de gate: hun
endpoints zijn geregistreerd met `overgang = true` en volgen met #521 per module. Zolang ze nog geen permission-flag hebben, bepaalt
`nl-portal.config.<module>.enabled` bij het opstarten of ze er zijn.

### Modulematrix en overzicht

Op de beheerpoort toont `/actuator/modulestatus` per module op de gate de **modulematrix** over alle tenants: `geconfigureerd` (het
tenantbestand heeft de configuratie), `flag` (de permission-flag staat aan) en `bruikbaar` (allebei). Een tenant waar `flag` aan staat en
`geconfigureerd` uit, is een flag zonder configuratie: de module staat uit en de beheerder moet de configuratie aanvullen of de flag uitzetten.
Bij het opstarten logt de backend een waarschuwing voor elke combinatie van tenant en module waar de flag aan staat zonder configuratie. Een CI-overzicht uit tenantmap en flagbestand volgt apart.

### Afhankelijkheden tussen modules

Een module-flag declareert de flags die hij vereist. Een module is alleen bereikbaar als zijn eigen flag en alle vereiste flags (ook die van vereiste modules) aan staan. Een combinatie waarin een vereiste flag uit staat, of ontbreekt, is niet ongeldig voor de applicatie maar fail-closed: de module geeft "niet beschikbaar", ook als zijn eigen flag aan staat. De applicatie start gewoon door.

| Module | Vereist |
|---|---|
| `berichten` | `documentenapis` |
| `documentenapis` | (geen) |
| `haalcentraal-brp` | (geen) |
| `taak` | (geen) |
| `zaken` | `documentenapis` |
| `payment-direct` | (geen) |

De graaf staat in de code bij de module-flag (`BERICHTEN_MODULE`, `DOCUMENTENAPIS_MODULE`, `BRP_MODULE`, `ZAKEN_MODULE`); voeg bij een nieuwe module een regel toe aan deze tabel. Staat een vereiste module uit of mist de tenant zijn configuratie, dan is ook de module zelf niet bruikbaar.

Het meegeleverde bestand (`backend/core/src/main/resources/feature-flags/default.flagd.json`) zet alle toggles en modules uit (ook de afhankelijkheidsvlag `permission.documentenapis.enabled`). Het compose-bestand `docker-compose/imports/flags/nl-portal.flagd.json` zet ze voor de lokale stack aan (en ook `permission.berichten.enabled` en `permission.documentenapis.enabled`, voor elke tenant), behalve `open-product` en `cases-result-explanation`.
