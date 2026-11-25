# 8. Dwarsdoorsnede-concepten

Concepten die meerdere bouwblokken overstijgen en dus niet in één bouwblok thuishoren.

## 8.1 Authenticatie en autorisatie

Zie [`site/architectuur/authentication-en-authorization.md`](../authentication-en-authorization.md).

> **Gebouwd (T7, #506):** [ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)
> beschrijft de backend-for-frontend die hieronder staat. De tokenvalidatie op elke GraphQL-aanroep, de token exchange en de
> publieke SPA-client bestaan niet meer.

**BFF-sessie.** De backend is de enige OIDC-client (`nl-portal-backend`, vertrouwelijk, `private_key_jwt` PS256, PKCE, `state`,
`nonce`, RFC 9207 `iss`) van het realm van de tenant die bij de host hoort. De browser heeft alleen cookies en nooit een token;
de frontend vraagt `GET /api/public/sessie` om te weten of en als wie de gebruiker is ingelogd (zonder tokens of BSN). Na de
callback toetst de `Inlogbeslisser` issuer, client, inlogmiddel, niveau, BSN (negen cijfers, elfproef) en vertegenwoordiging; een
weigering is een securityevent met OIN en actor, nooit met BSN. De sessie staat in Redis (vluchtig, ADR 0017: ACL, geen
persistentie, `noeviction`, TTL op elke sleutel) en bevat de afgeleide identiteit (met het BSN van de vertegenwoordigde: een restrisico, zie ADR 0022) en het ID-token voor RP-initiated logout.
Het BSN komt bij de callback uit de userinfo van Keycloak en staat niet in het ID-token; het ID-token bevat alleen `sub` (Keycloak-gebruikers-ID),
`acr`, `inlogmiddel`, `auth_time`, `sid` en de standaardclaims, geen `preferred_username`, `name` of `email`.
De tenant is een gecontroleerd sessieattribuut (`nlp.tenant`), geen onderdeel van de sleutel; een sessie die op de host van een
andere tenant binnenkomt, wordt afgewezen.

**Cookies.**

| Cookie | Doel | Attributen |
|---|---|---|
| `__Host-Http-NLP` | de portaalsessie | `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Strict`; nieuw sessie-ID bij elke login |
| `__Host-nlp-flow` | verwijst naar de lopende login in Redis (eenmalig, `GETDEL`, tenantgebonden, korte levensduur) | `HttpOnly`, `Secure`, `SameSite=Lax` (de callback is cross-site) |
| `__Host-XSRF-TOKEN` | CSRF-token (double submit) | leesbaar voor de SPA, `Secure`, `SameSite=Strict` |

Alle drie de cookies zijn altijd `Secure`. Voor lokale ontwikkeling over http werkt dat omdat browsers `localhost` en `*.localhost` als
veilige context behandelen.

**CSRF en Origin-regel.** Elk muterend request (`POST`, `PUT`, `PATCH`, `DELETE`; ook `POST /graphql`) moet het CSRF-token in
`X-XSRF-TOKEN` meesturen (formulier-POST van uitloggen: parameter `_csrf`) en een `Origin` hebben die gelijk is aan de origin van de
host-tenant, of `Sec-Fetch-Site: same-origin`. GraphQL accepteert alleen `application/json`. Een Bearer-header wordt niet
gelezen: er is geen resource-server. **Uitzondering:** de postsale-webhook van payment-direct (`POST /api/public/payment/direct/postsale`)
komt niet uit een browser en authenticeert met zijn eigen handtekening/`keyId`; die ene route is vrijgesteld van CSRF- en Origin-controle.
Back-channel logout is nog niet gebouwd en dus ook geen uitzondering.

**Niveau (`acr`) per inlogmiddel.** De callback zet de `acr` van het ID-token om via een vaste tabel; een onbekende of lege waarde
(ook `""`) geldt als afwezig en wordt geweigerd. Daarna geldt het minimum van de tenant. Keycloak laat zelf een lager niveau door,
dus de backend toetst altijd.

| Inlogmiddel | `acr`-waarde | Niveau |
|---|---|---|
| DigiD | `urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport` | Basis |
| DigiD | `...:MobileTwoFactorContract` | Midden |
| DigiD | `...:Smartcard` | Substantieel |
| DigiD | `...:SmartcardPKI` | Hoog |
| Yivi (nog uit) | `http://eidas.europa.eu/LoA/low`, `substantial`, `high` | Basis, Substantieel, Hoog |
| eHerkenning | (nog geen waarden: geweigerd) | n.v.t. |
| Gebruikersnaam (niet in productie) | `Basis` | Basis |

**Sessiecookie na de tussenpagina.** De callback antwoordt met een tussenpagina (`meta refresh`) zodat de sessiecookie `Strict` kan blijven. Dat is
niet in een echte browser getoetst; de Playwright-toets hoort bij T25 (#524).

**Nginx-sidecar: headers en proxy.** De nginx van de frontend overschrijft `X-Forwarded-Host` (met `$http_host`) en `Forwarded` (leeg), zodat een
client de hostbepaling van de tenant niet kan sturen, en zet per location een CSP (`default-src 'self'`, `object-src 'none'`, `base-uri 'self'`,
`frame-ancestors 'none'`), `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin` en HSTS (alleen als de ingress `X-Forwarded-Proto: https`
doorgeeft). `form-action` ontbreekt bewust: het uitloggen stuurt door naar Keycloak en de sidecar kent de broker-URL niet. Het adres van de sidecar hoort nooit in
`nl-portal.tenancy.trusted-proxies` (`NL_PORTAL_TRUSTED_PROXIES`): dan zou de backend `X-Forwarded-*` van de browser vertrouwen. Alleen de eigen ingress mag daar staan.

**Sleutelmap.** De private clientsleutel van elke tenant (RSA van minimaal 3072 bit, `alg: PS256`, met `kid`) staat als bestand onder
`nl-portal.sleutelmap` (`NL_PORTAL_SLEUTELMAP`) in een map met de OIN als naam; `authenticatie.sleutelBestand` in het tenantbestand
is relatief daaraan. Bij het opstarten moet het echte pad (symlinks opgelost) onder `<sleutelmap>/<oin>/` liggen, anders weigert de
backend. Het bestand is alleen leesbaar voor het uid van de backend (0600); dit is niet op Linux met een tweede uid getoetst.

**Sessietijden.** Standaard 15 minuten inactiviteit en 8 uur absoluut; per tenant strenger in te stellen. `GET /api/public/sessie`
telt niet als activiteit. Uitloggen is een `POST /logout` (CSRF) dat de sessie beëindigt en bij Keycloak uitlogt met `id_token_hint`.

Samengevat is het doelbeeld voor de authenticatie dat DigiD (burgers) en eHerkenning (ondernemers/ketenpartners) via een
OIDC-broker (Keycloak) lopen; zie ook [authentication-en-authorization](../authentication-en-authorization.md).

## 8.2 Het ZGW-integratiepatroon

Elke ZGW-bron wordt vandaag ontsloten via een eigen Gradle-module met een eigen HTTP-client, eigen
auth-token-injectie en eigen statuscode-afhandeling (zie hoofdstuk 5.2 en de architectuurreview,
kandidaten 1-2). **Voor nieuwe functionaliteit die een nieuwe of uitgebreide ZGW-integratie behelst,
betekent dit vandaag: kopieer het patroon van een bestaande, vergelijkbare module.** Zodra de
review-kandidaat "één diepe HTTP-adapter" is doorgevoerd, verandert dit in: implementeer alleen de
resource-specifieke aanroepen tegen de gedeelde adapter.

## 8.3 Generieke foutafhandeling

Een generieke foutpagina is recent toegevoegd (zie `feat: generic error page` in de git-historie).
**Niet geverifieerd in deze review:** of er ook een generiek, gedeeld patroon bestaat voor
GraphQL-errors (error codes, client-vertaalbare foutmeldingen) — dit verdient een expliciete check
voordat nieuwe functionaliteit een eigen ad-hoc foutconventie introduceert.

## 8.4 Internationalisatie

`@nl-portal/nl-portal-localization` verzorgt vertalingen; componenten gebruiken translation-ID's
(bijv. `pageTitles.${slug}Mutate`, zichtbaar in de architectuurreview-kandidaat over
`ThemeMutatePage`/`ThemeHistoryPage`). Nieuwe klant-gerichte tekst hoort via een translation-ID te
lopen, niet als hardcoded string.

## 8.5 Testen

- **Ingang**: `mise run test`, `mise run lint` en `mise run arch-check` vanaf de repo-root dekken
  backend en frontend tegelijk; `mise tasks` toont de taken per kant. Zie `CONTRIBUTING.md`.
- **Backend**: `mise run backend:test` draait de unit-tests, `mise run backend:integration-test` de
  integratietests; die hebben geen database nodig; alleen de `zgw:openklant`-tests starten via de
  Gradle docker-compose-plugin een OpenKlant-container (Docker moet dan draaien). De CI-poort
  `mise run backend:check` draait beide via `./gradlew build`.
- **Frontend**: `mise run frontend:test` (vitest, workspace-breed via `vitest.workspace.ts`) en
  `mise run frontend:lint` (ESLint en Stylelint); de CI-poort `mise run frontend:check` voegt
  build, typecheck en Prettier toe.
- **Observatie uit de architectuurreview**: de ZGW-HTTP-clients hebben vandaag geen test-dubbel
  (geen tweede adapter), dus geen unit test die een foutscenario simuleert zonder een live ZGW-API.
  Dit is zowel een hoofdstuk-10-testbaarheidsrisico als hoofdstuk-11-technische-schuld.

## 8.6 Kwaliteits- en beveiligingspoorten in CI

- **SonarCloud** (`sonarcloud.yml`) — codekwaliteit or draait niet op fork-PR's (zie
  `fix(ci): don't run sonar scans from fork PRs`).
- **OWASP Dependency-Check** (`dependency-check.yml`, met uitzonderingen in
  `backend/cve-exclusions.xml`) — bekende kwetsbaarheden in dependencies.
- **Renovate** (`.github/renovate.json`) — geautomatiseerde dependency-updates (zie deliverable 4).
- **SBOM-generatie** bestaat al voor de backend-release (`anchore/sbom-action` in
  `_stage-be.yml`, CycloneDX-JSON als GitHub-Release-asset) maar is backend- en
  release-only, en nergens gedocumenteerd als ontwikkelaarsworkflow (zie deliverable 5).

## 8.7 Copyright-headers

Elk backend- (`.kt`/`.kts`) en frontend-bestand (`.ts`/`.tsx`/`.js`/`.jsx`) draagt een EUPL
1.2-header, afgedwongen door Spotless (backend) resp. een ESLint-regel + Husky (frontend). Bestanden
uit NL Portal houden de Ritense-header; nieuwe bestanden krijgen de PinkRoccade-header
([ADR 0015](../../development/adr/0015-copyright-headers-pink-edition.md)). Zie `CONTRIBUTING.md`
voor de exacte tooling.

## 8.8 Feature flags

Gedrag dat per omgeving of tenant moet kunnen verschillen, wordt gestuurd met feature flags
([ADR 0014](../../development/adr/0014-feature-flags-via-openfeature.md)). De backend evalueert flags
via de open standaard OpenFeature; `FeatureGate` (module `core`) is het enige aanspreekpunt voor
featurecode. Zonder aparte flag-voorziening gebruikt NL Portal een flagbestand (flagd-formaat) dat
via een rolling update wordt uitgerold, niet via hot reload; een andere OpenFeature-provider sluit je aan met een eigen bean.

- **Typen**: ops (runtime-gedrag), permission (toegang; bij twijfel geweigerd), experiment en release
  (kortlevend). Flagsleutels hebben het formaat `<type>.<module>.<naam>`.
- **Evaluatiecontext**: `FeatureContextFactory` bouwt per request een context (tenant, omgeving,
  en gebruikerstype) uit de reactive security context. De context wordt expliciet aan elke evaluatie
  meegegeven, niet thread-gebonden opgeslagen, en bevat nooit persoonsgegevens en geen gebruikers-id;
  een gebruiksgeval voor targeting per burger vraagt een eigen besluit (ADR 0020).
- **Frontend**: de uitkomst voor de frontend komt via `GET /api/public/frontend`; de browser praat
  nooit met een flag-systeem.
- **Nu flag-gedreven**: alle frontend-toggles (`ops.frontend.*`), de vrije custom-toggles en de module
  berichten (`ops.berichten.enabled`): `ModuleGate` (module `core`) beslist per request of een query of
  resource beschikbaar is en de `modules`-lijst volgt dezelfde flag. Een module-flag declareert de flags die hij
  vereist (berichten vereist documentenapis); een uitgeschakelde afhankelijkheid maakt de module fail-closed
  niet beschikbaar; zie
  [het patroon](../../development/workflows/module-gate-patroon.md). De andere modules volgen (#143).
- **Grens**: credentials en andere secrets horen niet in een flag-systeem.

## 8.9 Tenantscheiding en configuratiemodel (voorgesteld)

> **Geaccepteerd (2026-11-21), nog niet gebouwd.** Uitgewerkt in
> [ADR 0021](../../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md). Vandaag is NL Portal één
> tenant per deployment en geldt het beeld uit 8.1 en 8.8 ongewijzigd; dit is het doelbeeld voor een gedeelde
> runtime.

- **Tenantresolutie.** Het hostdomein bepaalt de tenant (allowlist van domein naar OIN; een onbekend domein
  geeft 404, nooit een standaardtenant). De tenant leeft in de Reactor Context, niet in een `ThreadLocal`.
  Bij een geauthenticeerd request moet de issuer van het token bij dezelfde tenant horen, anders 403: de
  issuer is de tenantbinding, er komt geen aparte tenant-claim bij.
- **Identiteit.** Een realm per tenant op de publieke ID-broker, gegenereerd uit één template. De runtime
  vertrouwt alleen issuers uit de tenantconfiguratie en nooit de private ID-broker (zie ook 8.1).
- **Per-tenant clientregister.** Een onveranderlijk register `OIN naar koppelingen` (`KoppelingRegister` in
  `core.koppeling`), bij opstarten volledig gebouwd (alle clients, limieten en circuit breakers; een koppeling zonder volledige configuratie staat er niet in en telt als niet geconfigureerd, een ongeldige URL laat het opstarten falen). `registry.current(module)` leest de tenant uit de Reactor Context
  (zonder tenant volgt een fout, nooit een standaardtenant) en geeft de `KoppelingClient` van die tenant. De client
  is de enige weg naar een bron: ophalen en muteren met de authenticatie van de koppeling, één foutvorm volgens
  RFC 9457 (`KoppelingException`; gesynthetiseerd bij een niet-conforme bron, een netwerkfout of een 502), één
  `ResultPage`, **host-binding** (een absolute URL, zoals `next`, wordt alleen gevolgd onder de basis-URL van
  dezelfde koppeling: schema, host, poort en padprefix; anders een fout zonder aanroep en dus zonder credentials;
  redirects worden nooit gevolgd: de connector van elke koppeling staat in code op niet-volgen, onafhankelijk van `spring.http.clients.redirects`; padtekens `;` en gecodeerde `/`, `\`, NUL en `%` worden geweigerd), en een limiet op gelijktijdige aanroepen plus een circuit breaker per tenant en
  koppeling (Resilience4j; standaardwaarden onder `nl-portal.koppeling-resilience`; alleen storingen van de bron
  tellen, een 4xx of eigen weigering niet). Een grote download (`getStream`) heeft een eigen limiet (`maxGelijktijdigeStromen`), zodat lopende downloads de metadata-aanroepen niet verdringen; de circuit breaker en de timeout beoordelen alleen tot de eerste respons, daarna geldt een stiltetimeout tussen twee brokken die niet als storing van de bron telt. Een client hoort bij één tenant en weigert een aanroep in de context van een andere tenant. Er worden geen headers of bodies gelogd en foutdetails van een bron gaan niet naar de
  browser: het bericht van een `KoppelingException` is generiek met een correlatie-ID, en de GraphQL-foutafhandelaar
  toont alleen dat. Koppelingen staan per tenant in het tenantbestand (`koppelingen.<naam>`, zie de volgende bullet); het register bouwt `perTenant` uit `TenantDefinition.koppelingen`.
  Tussenfase (tot de laatste module is gemigreerd): een nog niet gemigreerde module leest nog `nl-portal.koppelingen` (bij meer dan één tenant weigert het opstarten); dezelfde naam in het tenantbestand en in `nl-portal.koppelingen` weigert het opstarten. De migratie van elke module (T9 tot en met T21) haalt ook zijn
  pakket uit `HttpClientRules.NOT_YET_MIGRATED`.
  Architectuurchecks (`backend/architecture-test`, `HttpClientRules`) bewaken dat buiten `core.koppeling` niemand een
  `WebClient` (of `RestClient`, `RestTemplate`, het JDK-`HttpClient`, OkHttp, Apache of Jetty) bouwt of kent, `wiretap` aanzet, verzoekdetails logt of een `KoppelingClient` in een veld bewaart; elke regel heeft een overtredende fixture. De enige uitzondering is `core.modulestatus`, dat alleen het type `ExchangeFilterFunction` kent (alleen vrijgesteld van de typeregel, niet van de andere). `NOT_YET_MIGRATED` is een ratchet: een test faalt zodra een vermeld pakket geen overtreding meer heeft, en `nl.nlportal.form` en `nl.nlportal.commonground.authentication` staan er nog in zonder migratieticket (aandachtspunt voor het BFF-ticket en de formulieren).
- **Modulegegevens en koppelingen in het tenantbestand.** Een module draagt zijn getypeerde configuratieklasse bij met de SPI
  `TenantModuleConfiguratie<T>` (`core.tenancy`): naam, Kotlin-dataclass en eigen validatieregels. De bijdrage wordt gevonden via
  `ServiceLoader` (`META-INF/services`), dus ook de validator-CLI zonder Spring neemt hem mee; schema, loader en opstartvalidatie
  lezen dezelfde bijdrage en core kent de module niet. `modules.<naam>` staat onder die sleutel in het tenantbestand en komt gebonden
  in `TenantDefinition.modules` (`tenant.module<T>()`; `TenantDirectory.module<T>()` leest de tenant uit de context). Een module die niet
  op het klassenpad staat is een onbekende eigenschap en weigert het opstarten. `koppelingen.<naam>` (url, auth, leverancier, versie) volgt hetzelfde pad:
  geheimen zijn alleen een `GeheimVerwijzing` (de naam in de secret store, opgelost via `GeheimenBron`, standaard `nl-portal.geheimen.<naam>` uit
  de omgeving). Elke verwijzing valt onder de naamruimte van de eigen OIN: de naam begint met `<oin>.` (of `<oin>-`, `<oin>_`) en een sleutelbestand staat in een
  map `<oin>` (ADR 0021 besluit 4 punt 5); loader en validator-CLI weigeren een verwijzing naar een andere OIN (`GEHEIM_VAN_ANDERE_TENANT`). Het register is bij
  opstarten volledig: ook elk geheim in `modules.<naam>` wordt opgelost (de melding noemt OIN en veld, nooit de naam of waarde), en `url` en `tokenUrl` zijn in
  productie alleen `https`. Dezelfde regels draaien in de testhulp (`KoppelingRegister.bouwUitTenants`). Een waarde die geen naam is, een onvolledige koppeling (per auth type de verplichte velden), een ongeldige url of een geheim dat in de secret
  store ontbreekt weigert het opstarten, met de OIN in de melding (story 14). Credentials (elke `GeheimVerwijzing`, en elk veld met `@NietErfbaar`)
  worden nooit uit de gedeelde basis geërfd (story 11). Het gepubliceerde schema (`backend/schema/tenant.schema.json`) wordt in `:app` gegenereerd,
  waar alle modules op het klassenpad staan.
  Auth-type `geen` is een bewuste keuze voor een bron achter de cloud gateway (die per gemeente zelf authenticeert); een weggelaten `auth` blijft een weigering.
  `ModuleContext` geeft een module naast de koppelingnamen ook de auth-headernamen per koppeling, zodat een moduleregel een botsing bij het opstarten weigert.
  Een gemigreerde module registreert een `VervallenProperties`-bean met haar oude prefix: staat daar nog iets gezet (ook als omgevingsvariabele), dan weigert het opstarten met een verwijzing naar `modules.<naam>`.
  `GraphQLExceptionHandler` toont een module- of tenantfout (`ModuleNotConfiguredException`, `TenantContextMissingException`, `KoppelingConfiguratieException`) met een generieke code en melding; de details staan alleen in de log.
- **Catalogi API per tenant en zaaktype-uitsluiting.** De Catalogi API is een gewone koppeling per tenant (`koppelingen.catalogiapi`, geen `modules.catalogiapi`);
  `CatalogiApiClient` haalt per aanroep `register.current("catalogiapi")` op en bewaart niets. De uitgesloten zaaktypen (`modules.zakenapi.uitgeslotenZaaktypen`) werken op drie plekken:
  het bronfilter `zaaktype__not_in` met zaaktype-URL's uit de Catalogi-koppeling van de tenant, een nafilter op zaaktype-UUID in de lijst, en een controle in `getZaak` en in de
  documentdownload (404, ook als de bron de zaak teruggeeft). Een zaaktype-URL zonder herkenbare UUID geldt als uitgesloten (gesloten falen). De koppeling-URL moet gelijk zijn aan de
  canonieke zaaktype-URL's in de Zaken API; anders filtert de bron niets en corrigeert alleen het nafilter, waardoor het totaal en de paginering afwijken.
- **Drie lagen configuratie.** Het *tenantbestand* (Git: domeinen, URL's, thema, verwijzingen naar secrets;
  rolling restart), de *flag* (alleen aan/uit per module en tenant; `ops.<module>.enabled`, ook via een rolling restart) en de *secret store* (de secrets
  zelf, via ESO). Een module is bruikbaar als configuratie aanwezig is én de flag aanstaat. Zie
  [Tenantbestand, flag en secret store](../../hosting-en-configuratie/tenantbestand-flag-en-secret-store.md).
- **Geen hot reload, geen uitzondering.** Wijzigingen in tenantconfiguratie, secrets en flags gaan altijd via
  een rolling restart van de eigen cel (ADR 0014 punt 7). Flags dragen alleen aan/uit, geen configuratie en
  geen beveiligingsbesluiten.
- **Gedeelde publieke ID-broker.** Een gedeelde afhankelijkheid van alle cellen en een geaccepteerd risico,
  met eigen HA en een herstelplan; een cel belooft geen hogere beschikbaarheid dan de broker.
- **Logging.** De tenant (OIN) hoort in elke logregel (BIO 8.15.01, ASVS 16.2.1); de uitwerking van
  observability per tenant is nog niet besloten.
