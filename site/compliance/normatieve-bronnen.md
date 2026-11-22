# Normatieve bronnen

Register van de externe en interne bronnen waaraan NL Portal zich bewust toetst (ASVS, RFC's, het
5-lagenmodel, BIO2, AVG, NORA, GEMMA, Forum Standaardisatie, NL API Design Rules, WCAG), met per bron een vast ID, de versie en de datum van raadpleging. De
traceerbaarheidsmatrix daaronder volgt elke bron naar de eis, de guardrail of check die de eis
afdwingt en het bewijs. Een bron die we bewust niet toepassen krijgt de status *Bewust niet
toegepast* met een verwijzing naar het besluit.

`mise run compliance:check` bewaakt dit register en draait in CI. Het faalt op een ID dat ergens in
`site/`, `backend/` (Kotlin) of `.github/workflows/` wordt genoemd maar hier niet bestaat, en op een
bron zonder check, afwijkingsbesluit of getoetst principe. Het
[principeregister](../architectuur/principes.md) is het tweede dekkingspad: een bron waarnaar een principe met
een geldige toetsing verwijst, telt als gedekt, tenzij de bron een *Open* matrixrij heeft
([ADR 0023](../development/adr/0023-architectuurprincipes-en-toetsing.md)). De uitvoer van de check noemt de
bronnen die alleen via een principe gedekt zijn: die zijn minder streng getoetst dan een bron met een
matrixrij. Periodiek nalopen of de versies nog actueel zijn staat in
[periodieke checks](../development/workflows/periodic-checks.md#6-bronversies-nalopen).

## Register

Een ID heeft de vorm `N-<BRON>-<VERSIE>`. Verwijs in ArchUnit-`.because()`-teksten, ADR's, workflows
en toetsingsdocumenten naar dit ID waar een bron wordt uitgevoerd.

| ID | Bron | Versie | Geraadpleegd op | Wijze van raadplegen |
|---|---|---|---|---|
| `N-ASVS-5.0` | [OWASP ASVS](https://github.com/OWASP/ASVS/tree/v5.0.0_release/5.0/en), hoofdstukken V6, V8, V9, V10, V12, V13 | 5.0.0 (2025-05-30); level 2 is de basis (P-01), level 3 waar de matrix het noemt | 2026-11-05 | Zelf gelezen (V6, V8, V9, V10 volledig; V12 en V13 alleen de eisentabellen) |
| `N-RFC-8693` | [RFC 8693, OAuth 2.0 Token Exchange](https://www.rfc-editor.org/rfc/rfc8693) | RFC 8693 (2020-01) | 2026-11-05 | Niet zelf gelezen: via een samenvattende fetch-tool |
| `N-KEYCLOAK-TOKEN-EXCHANGE` | [Keycloak, token exchange](https://www.keycloak.org/securing-apps/token-exchange) | Niet vastgesteld (de pagina noemt geen Keycloak-versie) | 2026-11-05 | Niet zelf gelezen: via een samenvattende fetch-tool |
| `N-CG-5LAGEN` | Common Ground 5-lagenmodel, zoals uitgewerkt in [5-lagenmodel](../architectuur/5-lagenmodel.md) | Interne uitwerking, geen versienummer | 2026-11-08 | Zelf gelezen (interne pagina; het externe model is niet opnieuw geraadpleegd) |
| `N-NLGOV-OAUTH-1.1.0` | [NL GOV Assurance profile for OAuth 2.0](https://gitdocumentatie.logius.nl/publicatie/api/oauth/v1.1.0/) (Logius), normatief voor de OAuth-inrichting; gaat boven RFC 9700 waar het specifieker is | 1.1.0, definitieve versie (2024-12-03) | 2026-11-21 | Deels zelf gelezen via een fetch-tool: hoofdstuk 1 t/m 5 letterlijk, de rest alleen als samenvatting |
| `N-RFC-9700` | [RFC 9700, Best Current Practice for OAuth 2.0 Security (BCP 240)](https://www.rfc-editor.org/rfc/rfc9700), basis waar `N-NLGOV-OAUTH-1.1.0` niets specifiekers zegt | RFC 9700 (2025-01) | 2026-11-21 | Niet zelf gelezen: versie en status via een samenvattende zoektool |
| `N-AVG-2016` | [Verordening (EU) 2016/679 (AVG)](https://eur-lex.europa.eu/eli/reg/2016/679/oj), art. 5 lid 1 onder c (minimale gegevensverwerking) en art. 25 (gegevensbescherming door ontwerp en standaardinstellingen) | Verordening 2016/679, geconsolideerde tekst | 2026-11-21 | Niet opnieuw gelezen: artikelen uit eerdere kennis, niet tegen de geconsolideerde tekst gecontroleerd |
| `N-BIO2-1.3` | [Baseline Informatiebeveiliging Overheid 2](https://www.bio-overheid.nl/), gebaseerd op ISO/IEC 27002:2022 | BIO2 versie 1.3 definitief (2026-01-09), Staatscourant 2026, 7416 | 2026-11-21 | Niet zelf gelezen: versie via een samenvattende zoektool; controlnummers volgen ISO/IEC 27002:2022 en zijn niet in de BIO2-tekst zelf nagekeken |
| `N-NORA-3.0` | [NORA, basisprincipes](https://www.noraonline.nl/wiki/Basisprincipes) | Basisprincipes uit NORA 3.0 (2010); actuele wikistand niet vastgesteld | 2026-11-21 | Niet zelf gelezen: via een samenvattende zoektool |
| `N-GEMMA-2` | [GEMMA, Gemeentelijke Model Architectuur](https://www.gemmaonline.nl/) | GEMMA 2 met de technische architectuur (sinds 2024); geen releasenummer vastgesteld | 2026-11-21 | Niet zelf gelezen: via een samenvattende zoektool |
| `N-FS-PTOLU` | [Forum Standaardisatie, lijst open standaarden "pas toe of leg uit"](https://www.forumstandaardisatie.nl/open-standaarden) | Lijst zonder versienummer, stand van de raadpleegdatum. Een bibliotheek: alleen de standaarden die op een keuze van toepassing zijn, tellen (P-09) | 2026-11-21 | Niet zelf gelezen: via een samenvattende zoektool |
| `N-NLAPI-2.2` | [NLGov REST API Design Rules](https://gitdocumentatie.logius.nl/publicatie/api/adr/2.2.1/) (versie 2.1 is verplicht op de pas-toe-of-leg-uitlijst; 2.2 is vastgesteld maar staat er nog niet op) | 2.2.1 (2.2 aangemeld bij Forum Standaardisatie 2026-06, nog niet op de lijst) | 2026-11-21 | Niet zelf gelezen: versie en status via een samenvattende zoektool |
| `N-WCAG-2.2` | [W3C Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/), niveau AA is de eis en baseline van NL Portal, AAA het streven (de wet verwijst naar EN 301 549, dus WCAG 2.1) | W3C Recommendation 2.2 (2023-10-05, bijgewerkt 2024-12-12) | 2026-11-21 | Niet opnieuw gelezen: versie uit eerdere kennis |

RFC 9068 en RFC 8707 zijn achtergrond bij ASVS-eisen 9.2.x en 10.3.1 en staan daarom niet als eigen bron in dit register; ze komen erbij zodra een guardrail of besluit ze uitvoert. Van de W3C- en IETF-standaarden staan alleen WCAG, RFC 9700 en RFC 8693 hier; de rest komt erbij zodra een principe of besluit ze uitvoert (P-09). RFC 8693 en de Keycloak-pagina staan er nog als bron van de token exchange die ADR 0022 heeft laten vervallen; ze zijn tweedehands en moeten voor hergebruik tegen de bron zelf gelezen worden. Het onderzoek
[ASVS 5.0.0 level 3 voor token-exchange](../development/research/asvs-5-l3-token-exchange.md#1-bronnen-en-versie)
beschrijft per hoofdstuk wat precies is gelezen.

## Statussen

| Status | Betekenis |
|---|---|
| Afgedwongen | Een geautomatiseerde guardrail (test, lint, CI-check) faalt bij een schending. Vereist guardrail en bewijs. |
| Getoetst | Handmatig getoetst aan de code of configuratie, met vastgelegd resultaat. Vereist guardrail (waar de eis in de code zit) en bewijs. |
| Bewust niet toegepast | We passen de eis niet toe. Vereist een verwijzing naar het besluit. |
| Open | Nog niet getoetst of nog niet voldaan; verwijst naar het issue of de bevinding. Telt niet mee als dekking. |

## Traceerbaarheidsmatrix

Bron → eis → guardrail of check → bewijs.

| Bron | Eis | Status | Guardrail of check | Bewijs | Besluit of verwijzing |
|---|---|---|---|---|---|
| `N-ASVS-5.0` | 9.1.1, 9.2.1 (L1): handtekening, `exp` en `nbf` van het token valideren | Open | - | - | De resource-server- en converter-toetsing is vervallen met [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md) (geen `JwtDecoder` meer); de toetsing voor de BFF is nog niet herhaald ([toetsing token exchange](../development/research/asvs-5-l3-toetsing-token-exchange.md) is verouderd) |
| `N-ASVS-5.0` | 10.4.10 (L2): clientauthenticatie met confidential client | Getoetst | `TenantClientRegistraties` (`private_key_jwt`, geen `client_secret`) | `InloggenTest` (login per tenant met `private_key_jwt`) | - |
| `N-ASVS-5.0` | 10.4.16 (L3): sterke clientauthenticatie (`private_key_jwt` of mTLS) | Getoetst | `TenantClientRegistraties` (`ClientAuthenticationMethod.PRIVATE_KEY_JWT`) | `InloggenTest` | - |
| `N-ASVS-5.0` | 9.2.3, 9.2.4, 10.3.1 (L2): `aud` valideren | Open | - | - | De resource-server-property is vervallen met [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md); de validatie van `iss` en `aud` van het ID-token (`CallbackTest`) is nog niet aan deze eisen getoetst |
| `N-ASVS-5.0` | 10.3.5, 10.4.14 (L3): sender-constrained tokens | Open | - | - | [Toetsing token exchange, open punten](../development/research/asvs-5-l3-toetsing-token-exchange.md) (besluit over DPoP/mTLS nodig; `private_key_jwt` is gebouwd) |
| `N-NLGOV-OAUTH-1.1.0` | 2.1.1, 2.3.3: een vertrouwelijke client authenticeert met `private_key_jwt` (of `tls_client_auth`) | Getoetst | `TenantClientRegistraties` (`private_key_jwt`, authorization code met PKCE) | `InloggenTest` (login per tenant met `private_key_jwt` van de eigen sleutel) | [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md), gebouwd in epic [#499](https://github.com/pinkroccade/nl-portal/issues/499) |
| `N-RFC-8693` | Token exchange zonder impersonation | Bewust niet toegepast | ArchUnit-regel in `AuthenticatiePadRulesTest`: er is geen token exchange | - | [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md), besluit 7: geen token exchange meer |
| `N-KEYCLOAK-TOKEN-EXCHANGE` | Standard token exchange V2 als enige pad | Bewust niet toegepast | ArchUnit-regel in `AuthenticatiePadRulesTest`: er is geen token exchange | - | [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md), besluit 7: geen token exchange meer |
| `N-CG-5LAGEN` | Core hangt niet af van een feature-module | Afgedwongen | ArchUnit-regel `ArchitectureRules.coreDoesNotDependOnFeature` | `LayerArchitectureTest` (draait in `./gradlew build`) | - |
| `N-CG-5LAGEN` | Gedeelde infrastructuur hangt niet af van een feature-module | Afgedwongen | ArchUnit-regel `ArchitectureRules.sharedInfraDoesNotDependOnFeature` | `LayerArchitectureTest` | - |
| `N-CG-5LAGEN` | Alleen `app` en `app-dev` stellen de modules samen | Afgedwongen | ArchUnit-regel `ArchitectureRules.nothingDependsOnApp` | `LayerArchitectureTest` | - |
| `N-CG-5LAGEN` | Modules hangen niet cyclisch van elkaar af | Afgedwongen | ArchUnit-regel `ArchitectureRules.cycleFree` | `LayerArchitectureTest` | - |

Aanvullende rijen komen erbij zodra een ticket een bron uitvoert; de overige eisen uit het
[onderzoek](../development/research/asvs-5-l3-token-exchange.md) staan in de
[toetsing](../development/research/asvs-5-l3-toetsing-token-exchange.md) en worden pas hier
opgenomen als ze een guardrail of besluit krijgen.
