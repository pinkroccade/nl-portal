# 8. Dwarsdoorsnede-concepten

Concepten die meerdere bouwblokken overstijgen en dus niet in één bouwblok thuishoren.

## 8.1 Authenticatie en autorisatie

Zie [`site/architectuur/authentication-en-authorization.md`](../authentication-en-authorization.md).

> **Besloten, nog niet gebouwd:** [ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)
> vervangt het patroon hieronder door een backend-for-frontend. Tot de bouw klaar is, beschrijft deze paragraaf de
> huidige werking.
Samengevat: DigiD (burgers) en eHerkenning (ondernemers/ketenpartners), gefedereerd via een OIDC-
broker (Keycloak in de huidige stack). De backend valideert het token op elke GraphQL-aanroep; de
frontend bewaart geen eigen sessie-state buiten wat de OIDC-flow vereist
(`@nl-portal/nl-portal-authentication`).

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
  gebruikerstype en een opaque gebruikers-id: HMAC van het subject met een geheime sleutel per tenant)
  uit de reactive security context. De context wordt expliciet aan elke evaluatie meegegeven, niet
  thread-gebonden opgeslagen, en bevat nooit persoonsgegevens.
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
  redirects worden nooit gevolgd: de connector van elke koppeling staat in code op niet-volgen, onafhankelijk van `spring.http.clients.redirects`; padtekens `;` en gecodeerde `/`, ``, NUL en `%` worden geweigerd), en een limiet op gelijktijdige aanroepen plus een circuit breaker per tenant en
  koppeling (Resilience4j; standaardwaarden onder `nl-portal.koppeling-resilience`; alleen storingen van de bron
  tellen, een 4xx of eigen weigering niet). Een client hoort bij één tenant en weigert een aanroep in de context van een andere tenant. Er worden geen headers of bodies gelogd en foutdetails van een bron gaan niet naar de
  browser: het bericht van een `KoppelingException` is generiek met een correlatie-ID, en de GraphQL-foutafhandelaar
  toont alleen dat. Tussenfase (stap 2 tot en met 6): elke tenant krijgt de koppelingen uit `nl-portal.koppelingen` (bij meer dan één tenant weigert het opstarten); de
  koppelingen per tenant uit het tenantbestand komen met de migratie van elke module (T9 tot en met T21), die ook zijn
  pakket uit `HttpClientRules.NOT_YET_MIGRATED` haalt.
  Architectuurchecks (`backend/architecture-test`, `HttpClientRules`) bewaken dat buiten `core.koppeling` niemand een
  `WebClient` (of `RestClient`, `RestTemplate`, het JDK-`HttpClient`, OkHttp, Apache of Jetty) bouwt of kent, `wiretap` aanzet, verzoekdetails logt of een `KoppelingClient` in een veld bewaart; elke regel heeft een overtredende fixture. De enige uitzondering is `core.modulestatus`, dat alleen het type `ExchangeFilterFunction` kent (alleen vrijgesteld van de typeregel, niet van de andere). `NOT_YET_MIGRATED` is een ratchet: een test faalt zodra een vermeld pakket geen overtreding meer heeft, en `nl.nlportal.form` en `nl.nlportal.commonground.authentication` staan er nog in zonder migratieticket (aandachtspunt voor het BFF-ticket en de formulieren).
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
