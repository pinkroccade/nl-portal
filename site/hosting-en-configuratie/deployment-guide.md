---
description: >-
  Met deze stappen moet het mogelijk worden om de NL Portal app images te
  deployen naar je gewenste omgeving, mocht dat Azure, AWS of een Kubernetes
  cluster zijn.
---

# Deployment guide

De NL Portal images (`ghcr.io/pinkroccade/nl-portal-backend` en `ghcr.io/pinkroccade/nl-portal-frontend`) worden volledig geconfigureerd via environment variabelen. De complete, versie-specifieke referentie met inline documentatie vind je in de monorepo, in de bestanden `docker-compose/imports/backend.env` en `docker-compose/imports/frontend.env`. Raadpleeg altijd het bestand dat hoort bij de release die je deployt.

De backend variabelen volgen de Spring Boot relaxed binding naamconventie: de property `nl-portal.config.zakenapi.enabled` wordt de environment variabele `NLPORTAL_CONFIG_ZAKENAPI_ENABLED`.

**Let op bij properties met koppeltekens in meerdere delen.** Relaxed binding accepteert twee schrijfwijzen, maar je moet er consequent één aanhouden: vervang *elk* koppelteken door een underscore, óf laat *elk* koppelteken weg. Een mengvorm wordt **stilzwijgend genegeerd** — geen foutmelding, geen waarschuwing, de property houdt gewoon zijn standaardwaarde. Voor `nl-portal.support.ping-endpoint.enabled`:

| Schrijfwijze | Environment variabele | Werkt |
| ------------ | --------------------- | ----- |
| elk koppelteken wordt `_` | `NL_PORTAL_SUPPORT_PING_ENDPOINT_ENABLED` | ja |
| elk koppelteken weggelaten | `NLPORTAL_SUPPORT_PINGENDPOINT_ENABLED` | ja |
| mengvorm | `NLPORTAL_SUPPORT_PING_ENDPOINT_ENABLED` | **nee** |

Bij properties waarvan alleen het voorste deel een koppelteken heeft — zoals `nl-portal.authentication.broker-url` — vallen beide schrijfwijzen samen en speelt dit niet. Gebruik bij voorkeur de variabelen van de app image (zoals `KEYCLOAK_BROKER_URL`), dan loop je hier niet tegenaan.

### Deployen met Helm (Kubernetes)

Voor Kubernetes bestaat een eigen Helm-chart, `nl-portal`, in de map `helm/nl-portal` van de monorepo (epic [#191](https://github.com/pinkroccade/nl-portal/issues/191)).
Een tutorial, how-to's (installeren, secrets, modules, upgraden), de referentie en de uitleg staan in de sectie [Deployen op Kubernetes met Helm](./kubernetes/index.md).

### Backend: module-configuratie

De functionele modules van de backend (onder andere Zaken API, Catalogi API, Documenten APIs, Open Object, OpenKlant 2, HaalCentraal BRP (`haalcentraal-brp`) en Handelsregister (`kvk`, als omgevingsvariabele `NLPORTAL_CONFIG_KVK_ENABLED`), Berichten, Taak, Prefill, DMN, OpenProduct, betalingen en virusscan) worden per module aangezet met een enable-vlag. Alle modules staan standaard uit.

Elke module die nog niet naar het tenantbestand is gemigreerd heeft daarnaast eigen properties die met hetzelfde patroon worden gezet: `NLPORTAL_CONFIG_<MODULE>_PROPERTIES_<PROPERTY>`. De Zaken API is gemigreerd: zij heeft alleen nog de enable-vlag, en haar URL, authenticatie en overige gegevens staan per tenant in het [tenantbestand](tenantbestand-flag-en-secret-store.md):

```shell
NLPORTAL_CONFIG_ZAKENAPI_ENABLED=true
# het geheim van koppelingen.zakenapi uit het tenantbestand, met het OIN van de tenant in de naam (minstens 32 tekens)
NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET=<secret>
```

```yaml
# <oin>.yaml in de map NL_PORTAL_TENANTS_DIRECTORY
koppelingen:
    zakenapi:
        url: https://openzaak.example.com
        auth:
            type: zgw-jwt
            clientId: nl-portal
            secret: 00000099000000000001.zaken-secret
modules:
    zakenapi:
        uitgeslotenZaaktypen: []              # UUID's van zaaktypen die nooit in de lijst komen
        nnpKvkQueryIdentificatoren: false     # bedrijven zoeken als niet-natuurlijk persoon (kvkNummer)
```

**Let op:** `koppelingen.zakenapi` en `modules.zakenapi` horen bij elkaar: het ene zonder het andere weigert het opstarten, met de OIN van de tenant in de melding. Zijn alle waarden standaard, schrijf dan `modules.zakenapi: {}` (de tenantbestand-loader accepteert dat; het script `mise run dev:setup` kent alleen de uitgeschreven vorm). Het geheim is willekeurig gegenereerd en minstens 32 tekens.

De Catalogi API is ook gemigreerd en heeft per tenant alleen een koppeling (geen `modules.catalogiapi`): `koppelingen.catalogiapi` (auth-type `zgw-jwt`, geheim als `NL_PORTAL_GEHEIMEN_<OIN>_CATALOGIAPI_SECRET`). Staat de module aan en heeft geen enkele tenant die koppeling, dan weigert het opstarten. De oude `NLPORTAL_CONFIG_CATALOGIAPI_PROPERTIES_*` zijn vervallen en weigeren het opstarten zolang ze gezet zijn.

**Let op:** de URL van `koppelingen.catalogiapi` moet gelijk zijn aan de canonieke zaaktype-URL's die de Zaken API opslaat (het deel vóór `/catalogi/api/v1/zaaktypen/`). Het filter `zaaktype__not_in` voor `modules.zakenapi.uitgeslotenZaaktypen` bouwt zijn waarden uit die URL. Wijkt de URL af (bijvoorbeeld een interne of gateway-URL), dan filtert de Zaken API niets en corrigeert alleen de nafilter op zaaktype-UUID: een zaak van een uitgesloten zaaktype wordt dan niet getoond, maar het totaal en het aantal pagina's tellen die zaken nog wel mee. Een tenant met uitgesloten zaaktypen heeft deze koppeling ook nodig; zonder faalt de zakenlijst bij de eerste aanvraag.

**Let op:** de zaken-functionaliteit vereist dat de Zaken API, Catalogi API én Open Object modules alle drie enabled zijn.

**Ontbrekende configuratie:** een module die nog niet naar het tenantbestand is gemigreerd controleert zijn verplichte instellingen (URL, `clientId`, `secret` of token) pas bij het eerste gebruik, niet bij het opstarten. Zet je een module aan zonder die instellingen, dan start de backend gewoon op en faalt de eerste aanroep van die module met een foutmelding als `Module 'documentenapis' is not configured: setting 'url' is missing`. Controleer na het aanzetten van een module dus altijd dat een aanroep slaagt; een goede opstart bewijst nog niet dat de configuratie klopt.

Alle module properties, inclusief de optionele, zijn met inline documentatie te vinden in `docker-compose/imports/backend.env` in de monorepo.

### Backend: Keycloak-koppeling en sessies

De backend is zelf de OIDC-client (BFF, [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)). De issuer, het
sleutelbestand en eventueel een interne route naar Keycloak staan per tenant in het tenantbestand (blok `authenticatie`); zie de
[Keycloak configuratie](./keycloak.md). De backend krijgt daarnaast:

| Variabele | Verplicht | Betekenis |
| --------- | --------- | --------- |
| `KEYCLOAK_BROKER_URL` | ja | Basis-URL van de publieke ID-broker. Elke issuer in de tenantbestanden moet eronder vallen. |
| `NL_PORTAL_SLEUTELMAP` | ja | Vaste map met per tenant een map met de OIN als naam, met daarin de private clientsleutel (`<OIN>/clientsleutel.jwk.json`, 0600, leesbaar voor de gebruiker van de backend). |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL` | ja | De Redis voor de portaalsessies: ACL, geen persistentie, `noeviction`, TLS standaard aan (`REDIS_SSL=false` alleen lokaal), één instantie per cel. Valt Redis uit, dan moeten gebruikers opnieuw inloggen. |

De backend leidt het token-endpoint nooit af uit een binnenkomend bericht. Heeft de backend een interne route naar Keycloak, zet dan
`authenticatie.interneBasisUrl` in het tenantbestand; de issuer blijft het publieke adres. Laat Keycloak de publieke URL in de tokens zetten,
ook bij een verzoek via het interne adres (`KC_HOSTNAME` op de publieke URL en `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`).

Het frontend-image heeft geen identity-instellingen meer. De reverse proxy (nginx in het image, of de ingress) moet `/graphql`, `/api`,
`/oauth2`, `/login` en `/logout` naar de backend sturen en de `Host`-header van de browser doorgeven: de host bepaalt de tenant.

### Backend: CORS

De backend staat standaard geen cross-origin verzoeken toe. Zonder configuratie stuurt hij geen CORS-headers (`Access-Control-Allow-Origin`) mee, waardoor de browser cross-origin antwoorden blokkeert. Dat is voldoende als de frontend en de backend via dezelfde host worden bediend, bijvoorbeeld achter een reverse proxy die beide onder één origin publiceert.

Draait de frontend op een andere origin, dan sta je die expliciet toe per omgeving via `nl-portal.security.cors`:

```properties
NLPORTAL_SECURITY_CORS_0_PATH=/**
NLPORTAL_SECURITY_CORS_0_CONFIG_ALLOWEDORIGINS=https://portal.example.nl
NLPORTAL_SECURITY_CORS_0_CONFIG_ALLOWEDMETHODS=GET,POST,OPTIONS
NLPORTAL_SECURITY_CORS_0_CONFIG_ALLOWEDHEADERS=*
```

Meerdere origins zet je komma-gescheiden in `ALLOWEDORIGINS`. Gebruik `*` voor origins alleen voor lokale ontwikkeling.

**ASVS:** de aanpak volgt het streefniveau uit [security](../development/workflows/security.md) (ASVS Level 2): een deployment vertrouwt standaard geen enkele vreemde origin en origins staan alleen aan als je ze expliciet instelt. Een toetsing per ASVS-eis is nog niet uitgevoerd.

### Frontend features

De frontend haalt zijn feature-toggles en -eigenschappen op via `GET /api/public/frontend`, gevuld vanuit `nl-portal.frontend.properties` en de feature flags op de backend. Eigenschappen volgen de relaxed-binding naamconventie.

- Toggles: alle toggles (`messageCountEnabled`, `overviewIntroEnabled`, ...) komen uit feature flags `ops.frontend.*` (zie [Feature flags](#feature-flags) en de [feature flags-referentie](./feature-flags.md)); `nl-portal.frontend.toggles.*` bestaat niet meer.
- `nl-portal.frontend.properties.*` — waarden, zoals `messageCountPollingInterval`, `overviewCurrentCasesPreviewLength`, `overviewCurrentTasksPreviewLength`, `themeClass`, de `overviewMaintenanceAlert{Title,Text}{Nl,En}` teksten en de BRP/adres-URL's.

De onderhoudsmelding op de overzichtspagina zet je aan met de flag `ops.frontend.overview-maintenance-alert` en de teksten:

```shell
NLPORTAL_FRONTEND_PROPERTIES_OVERVIEWMAINTENANCEALERTTITLENL=Onderhoud
NLPORTAL_FRONTEND_PROPERTIES_OVERVIEWMAINTENANCEALERTTEXTNL=Deze omgeving is tijdelijk in onderhoud.
```

### Feature flags

NL Portal stuurt gedrag aan met feature flags, geëvalueerd via de open standaard
[OpenFeature](https://openfeature.dev). **Je hoeft niets in te richten om te starten**: zonder aparte
instelling gebruikt NL Portal een meegeleverd flagbestand, en de standaardwaarden daarin zijn bedoeld
als veilige startpositie. Je wijzigt flags door een eigen flagbestand aan te wijzen.

**Eigen flagbestand gebruiken**

| Property | Omgevingsvariabele | Betekenis |
|---|---|---|
| `nl-portal.feature-flags.file` | `NLPORTAL_FEATUREFLAGS_FILE` | Pad naar je eigen flagbestand. Zonder deze instelling wordt het meegeleverde bestand gebruikt. |
| `nl-portal.feature-flags.poll-interval-ms` | `NLPORTAL_FEATUREFLAGS_POLLINTERVALMS` | Hoe vaak het bestand op wijzigingen wordt gecontroleerd (standaard 5000). Reken hier niet op: een flagwijziging rol je uit met een rolling update. |

Het bestand heeft het [flagd-formaat](https://flagd.dev/reference/flag-definitions/). Het meegeleverde
bestand is het uitgangspunt: kopieer het uit de broncode van NL Portal
(`backend/core/src/main/resources/feature-flags/default.flagd.json`) en pas het aan. Een flag aanzetten:

```json
{
  "$schema": "https://flagd.dev/schema/v0/flags.json",
  "flags": {
    "ops.frontend.message-count": {
      "state": "ENABLED",
      "variants": { "on": true, "off": false },
      "defaultVariant": "on"
    }
  }
}
```

Op Kubernetes mount je het bestand uit een ConfigMap en zet je `NLPORTAL_FEATUREFLAGS_FILE` op het
gemounte pad. Een flag die ontbreekt in je bestand valt terug op de standaardwaarde in de code, en een
flag voor toegang (permission) valt bij twijfel altijd terug op uit. Een wijziging van het flagbestand rol je uit met een
rolling update (zet een checksum van de ConfigMap als annotatie op de Deployment, zodat pods herstarten);
NL Portal belooft geen live herladen.

**Evaluatiecontext en targeting**

Elke flag wordt per request geëvalueerd met een evaluatiecontext. Daarmee kun je in je flagbestand
[targetingregels](https://flagd.dev/reference/flag-definitions/#targeting-rules) schrijven op deze
attributen:

| Attribuut | Inhoud |
|---|---|
| `tenant` | De tenant van deze installatie (`nl-portal.feature-flags.context.tenant`). |
| `environment` | De omgeving (`nl-portal.feature-flags.context.environment`); ontbreekt als die niet is ingesteld. |
| `userType` | Soort ingelogde gebruiker: `bsn`, `kvk` of `uid`; ontbreekt bij een anoniem request. |

De context bevat nooit persoonsgegevens zoals BSN, KVK-nummer of naam, en geen gebruikers-id (geen `targetingKey`): flags beslissen per tenant, niet per burger ([ADR 0020](../development/adr/0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md)).

| Property | Omgevingsvariabele | Betekenis |
|---|---|---|
| `nl-portal.feature-flags.context.tenant` | `NLPORTAL_FEATUREFLAGS_CONTEXT_TENANT` | Naam van de tenant (standaard `default`). |
| `nl-portal.feature-flags.context.environment` | `NLPORTAL_FEATUREFLAGS_CONTEXT_ENVIRONMENT` | Naam van de omgeving, bijvoorbeeld `test` of `productie`. |

Een flag die alleen voor één tenant aan staat:

```json
"ops.frontend.message-count": {
  "state": "ENABLED",
  "variants": { "on": true, "off": false },
  "defaultVariant": "off",
  "targeting": { "if": [{ "==": [{ "var": "tenant" }, "gemeente-a"] }, "on", "off"] }
}
```

**Beschikbare flags**

Per flag naam, type, standaard en effect staan in de [feature flags-referentie](./feature-flags.md), 

Een andere OpenFeature-provider (bijvoorbeeld een flag-server) sluit je aan door in een eigen
Spring-configuratie een bean van het type `FeatureProvider` te registreren; die vervangt dan het
flagbestand.

Zie [Eigen vormgeving](../gebruik-en-inrichting/eigen-vormgeving.md) voor `themeClass` en theming.

Zie de [Keycloak configuratie](./keycloak.md) pagina voor het instellen van de bijbehorende clients in Keycloak.
