# 22. Backend-for-frontend als authenticatiepatroon

## Status

Geaccepteerd op 2026-11-21, in de grilling-sessie van fase 0 voor de multitenancy-epic (zie het
[besluitenlog fase 0](../research/fase0-besluiten-multitenancy-bff.md), vragen Q14 en Q18 tot en met Q23).
Gebouwd in T7 (#506), met de correcties onder "Correctie na de bouw". Hoort bij [ADR 0021](./0021-gedeelde-multi-tenant-runtime-met-cellen.md), dat de
tenantbinding via de issuer hierop laat steunen. Steunt op [ADR 0017](./0017-vluchtige-state-toegestaan.md)
voor de sessieopslag. Raakt de ASVS-toetsing van de token exchange
([`asvs-5-l3-toetsing-token-exchange.md`](../research/asvs-5-l3-toetsing-token-exchange.md)) en de issues
#165, #166 en #167. De onderbouwing met bronnen staat in
[`auth-bff-nlgov-oauth.md`](../research/auth-bff-nlgov-oauth.md).

## Context

Tot nu toe is de frontend (een React-SPA) een publieke OIDC-client (`nl-portal`, code-flow met PKCE via
`oidc-client-ts`). Het access-token staat in de browser en gaat als `Bearer` naar de backend. De backend is
een resource server en wisselt het token met de vertrouwelijke client `nl-portal-m2m` om (RFC 8693) voor een
token met de BSN-claims.

Dat patroon past niet bij waar NL Portal heen gaat: een productiewaardige portal met BSN-gegevens die een
externe pentest moet doorstaan, in een gedeelde runtime voor meerdere gemeenten.

- **RFC 10017** (BCP 212, augustus 2026, OAuth 2.0 for Browser-Based Applications) rangschikt drie patronen
  naar veiligheid: backend-for-frontend (BFF), token-mediating backend, en browser-based client. Het raadt
  het laatste, ons huidige patroon, af voor zakelijke toepassingen en voor toepassingen met persoonsgegevens
  (§6.3.4.3), en beveelt een BFF daarvoor met nadruk aan (§6.1.4.3).
- **ASVS 5.0** eist op L2 dat tokens alleen bereikbaar zijn voor componenten die ze nodig hebben, in een
  BFF-opzet alleen de backend (10.1.1). Daar voldoen we nu niet aan. Op L3 moeten clients vertrouwelijk zijn
  met `private_key_jwt` of mTLS (10.4.16); een publieke SPA-client kan daar nooit aan voldoen.
- **De NL GOV-profielen** voor OAuth 2.0 (v1.1.0) en OpenID Connect (1.0.1) zijn pas-toe-of-leg-uit en staan
  voor vertrouwelijke clients geen `client_secret` toe, alleen `private_key_jwt` of mTLS. De huidige
  `nl-portal-m2m` gebruikt een `client_secret`.
- **Tokentype.** Keycloak 26.3 geeft access- en ID-tokens standaard allebei `typ: JWT`, en Spring accepteert
  dat. Een ID-token komt nu dus als `Bearer` door de backend.
- De oorspronkelijke productowner noemde het BFF-patroon al als doel; ADR 0017 maakte de sessieopslag ervoor
  mogelijk, maar het patroon zelf was nooit besloten.

Het modulaire karakter van de backend (modules die per tenant aan staan en via GraphQL functionaliteit in de
frontend activeren) staat los van deze keuze en blijft ongewijzigd. Deze ADR gaat over waar de tokens staan
en wie de OAuth-client is.

## Overwogen opties

| Optie | Tokens in de browser | ASVS 10.1.1 (L2) / 10.4.16 (L3) | Prijs |
|---|---|---|---|
| **Browser-based client** (huidig) | Ja, access-token en mogelijk refresh-token | Niet / niet haalbaar | Geen extra component, maar DPoP in de browser nodig om tokendiefstal te dempen; RFC 10017 raadt het af |
| **Token-mediating backend** | Access-token wel, refresh-token niet | Niet / haalbaar | Halve stap: het access-token blijft te stelen, en een aanvaller kan via de backend nieuwe tokens laten ophalen (RFC 10017 §5.1.4) |
| **BFF in de bestaande backend** (gekozen) | Nee, alleen een sessiecookie | Wel / haalbaar | Sessieopslag (Redis), CSRF-bescherming, sessiebeheer (ASVS V7), ombouw van de frontend-authenticatie |
| BFF als aparte gateway (Spring Cloud Gateway met `TokenRelay`) | Nee | Wel / haalbaar | Een extra component per cel, en een resource-server-pad dat blijft bestaan |

## Besluit

1. **De backend is de backend-for-frontend.** De bestaande Spring Boot-backend is per tenant-realm de enige
   vertrouwelijke OIDC-client, met de naam **`nl-portal-backend`**. Hij doet de login met de
   authorization code flow en PKCE. De client-registratie per tenant komt uit het tenantbestand en wordt
   gekozen op de host-tenant (ADR 0021 besluit 2). De publieke client `nl-portal` vervalt; de SPA doet geen
   OIDC meer.
2. **Tokens blijven op de server.** De browser krijgt alleen een sessiecookie met het prefix `__Host-`,
   `Secure`, `HttpOnly`, `Path=/` en zonder `Domain`. Doel is `SameSite=Strict`; of de login-callback (een
   cross-site redirect van Keycloak) daarvoor een `Lax`-cookie of een tussenstap nodig heeft, wordt in de spec
   met een test vastgesteld (RFC 10017 §6.1.3.2, ASVS 3.3.1 tot 3.3.4).
3. **Sessies in Redis**, als vluchtige state volgens ADR 0017, met de tenant als gecontroleerd sessieattribuut
   (`nlp.tenant`, door de backend gezet bij het opslaan; zie "Correctie na de bouw"). Een sessie van tenant A is
   op het domein van tenant B nooit geldig.
4. **CSRF-bescherming is verplicht** (RFC 10017 §6.1.3.3, ASVS 3.5). Het mechanisme (anti-forgery-token of
   een verplichte eigen header met strikte CORS) wordt in de spec gekozen. Muterende functies gaan nooit via
   `GET`.
5. **Sessietijden** (ASVS 7.2 en 7.3): een nieuwe sessie-ID bij elke (her)authenticatie, een
   inactiviteitstimeout van 15 minuten en een absoluut maximum van 8 uur. Een tenant mag dit binnen grenzen
   strenger instellen. Dit volgt de DigiD-eis van maximaal 15 minuten inactiviteit voor de lokale sessie
   (Koppelvlakspecificatie DigiD SAML Authenticatie 3.7, "Lokale sessie"; checklist T15); DigiD stelt geen
   absoluut maximum voor de lokale sessie, de 8 uur is een eigen keuze. Uitloggen moet de lopende sessie
   beëindigen (checklist T14).
6. **Uitloggen beëindigt alle sessies** (ASVS 7.4.1, 7.6.1): de backend maakt zijn sessie ongeldig en doet
   RP-initiated logout bij Keycloak. Back-channel logout van Keycloak naar de backend maakt de sessie ook
   ongeldig als de Keycloak-sessie eindigt.
7. **Geen token exchange meer.** Met een vertrouwelijke client krijgt de backend de BSN-claims rechtstreeks
   via mappers in de tokens voor `nl-portal-backend`. De client `nl-portal-m2m` en de exchange-stap vervallen,
   en daarmee het terugvalpad op het originele token (#166). Downscoping per downstream-API met een token
   exchange op zichzelf kan later, als een bron-API tokens met een eigen `aud` eist.
8. **Geen `Bearer`-pad van buiten.** De backend accepteert geen access-tokens van de browser of van derden.
   De enige andere ingang, de postsale-webhook, authenticeert met zijn `keyId`. In het realm-template staat
   `at+jwt` (RFC 9068) voor access-tokens toch aan, als verdediging in de diepte en voor toekomstige API's.
   Waar de backend een token ontvangt, valideert hij `iss` en `aud` tegen de client-registratie van de
   host-tenant.
9. **Clientauthenticatie met `private_key_jwt`, vóór livegang** (NL GOV, ASVS 10.4.16). Elke tenant heeft een
   eigen sleutelpaar. De private sleutel komt uit de secret store (ESO, ADR 0021 besluit 4.5), de publieke
   sleutel staat in het realm-template. Er is een gedocumenteerd rotatieproces.
10. **DPoP is voor de browser niet nodig**, omdat er geen token naar de browser gaat. Sender-constrained
    tokens tussen backend en Keycloak zijn geen eis voor de livegang.
11. **Identiteit.** Het identiteitsmodel kent een **gebruiker** (wie inlogt) en een **vertegenwoordigde**
    (namens wie, en wiens gegevens het portaal toont); zie de glossary. Bij de livegang zijn die altijd
    dezelfde burger.
    - De sleutel voor datatoegang is (tenant, BSN van de vertegenwoordigde). Het BSN van de vertegenwoordigde
      is `LegalSubjectID` als die er is, anders `ActingSubjectID` (Stelsel Toegang SAML 1.0.0).
    - Logs en securityevents gebruiken (`iss`, `sub`) als actor, nooit het BSN.
    - Een token met `LegalSubjectID` wordt geweigerd zolang machtigen voor de tenant uit staat; een token met
      een KvK-nummer wordt geweigerd zolang het inlogmiddel eHerkenning voor de tenant uit staat.
    - In de code wordt de term `aanvrager` vervangen door `vertegenwoordigde`.
12. **Minimum betrouwbaarheidsniveau per tenant.** Het tenantbestand legt het minimum vast (standaard
    DigiD Substantieel). De backend weigert een login met een lager niveau. Dat DigiD Substantieel niet
    voldoet aan de L3-eis van hardwaregebonden, phishingbestendige authenticatie (ASVS 6.3.3), is een
    vastgelegde afwijking. DigiD geeft het niveau terug als `AuthnContextClassRef` (Basis
    `PasswordProtectedTransport`, Midden `MobileTwoFactorContract`, Substantieel `Smartcard`, Hoog
    `SmartcardPKI`) en eist dat een inlogpoging met een te laag niveau wordt afgebroken. Of de
    DigiD-koppeling in Keycloak dit niveau als claim doorgeeft, toetst een prototype.
13. **Keycloak: laatste stabiele release** (nu 26.8; minimaal 26.4 vanwege `at+jwt` sinds 26.2 en
    DPoP-ondersteuning sinds 26.4). Docker-compose en de documentatie volgen de laatste stabiele release; een
    upgrade gaat eerst via dev, en keycloak-config-cli moet die versie ondersteunen (zie ADR 0021 besluit 6).
    Beveiligingspatches gaan vóór de tool: zit een patch alleen in een release die keycloak-config-cli niet
    ondersteunt, dan wint de patch en worden realm-wijzigingen bevroren of handmatig onder change-procedure
    doorgevoerd tot de tool volgt, of wordt de tool vervangen (Crossplane, ADR 0021 besluit 6).

14. **Eén authenticatiepad, architectonisch geborgd.** De BFF-login is het enige authenticatiepad. Dit staat
    in arc42 §8 en wordt met architectuurregels (ArchUnit) afgedwongen: geen resource-server- of
    `Bearer`-configuratie, geen `JwtDecoder` en geen token exchange in de code; de identiteit wordt alleen
    via de sessie gelezen, via één leesroute in de authenticatiemodule; geen module bouwt zelf een
    `Authentication`.

## Correctie na de bouw

- **Tenant als sessieattribuut, niet in de Redis-sleutel.** De sleutel is de sessie-ID zelf (256 bit, door Spring Session
  onder één vaste naamruimte). Een tenant in de sleutel zou de sleutel voorspelbaar maken en dwingt de Redis-ACL per
  tenant af, terwijl de sessie-ID uit een cookie komt dat al aan het domein van de tenant gebonden is. In plaats daarvan
  slaat de backend bij het opslaan de tenant (OIN) op als attribuut, en vergelijkt `SessieBewakingFilter` dat bij elk
  request met de tenant van de host. Een afwijking maakt de sessie ongeldig (401) en wordt als securityevent gelogd. De
  tenant is dus een gecontroleerd attribuut van de sessie, geen onderdeel van haar adres.
- **`SameSite=Strict` met een tussenpagina.** De sessiecookie (`__Host-Http-NLP`) is `Strict`. De callback zet hem op een
  cross-site redirect; daarom antwoordt de callback met een tussenpagina met `meta refresh` naar het terugkeerpad, zodat de
  eerste navigatie al first-party is. De flowcookie (`__Host-nlp-flow`) is `Lax`, wordt bij de callback eenmalig gelezen
  (`GETDEL`) en bevat alleen een verwijzing.
- **Niveau per inlogmiddel.** De callback zet de `acr` van het ID-token om via een vaste tabel (arc42 8.1) en weigert een
  onbekende of lege waarde; het minimum per tenant komt uit het tenantbestand.
- **Back-channel logout is nog niet gebouwd.** De realm kent de URL; de backend beantwoordt hem nog niet (404).

## Gevolgen

- **Frontend:** `react-oidc-context`, `oidc-client-ts` en de token-afhandeling verdwijnen. De SPA praat
  same-origin met `/graphql` en `/api`, met de sessiecookie en de CSRF-bescherming. Inloggen en uitloggen
  zijn navigaties naar de backend. Wat de UI over de gebruiker weet, komt van een backend-endpoint, niet uit
  een gedecodeerd token. Dit sluit aan op de eigen inlogpagina (#485): de backend start de login met het
  gekozen inlogmiddel en weigert een uitgeschakeld inlogmiddel.
- **Backend:** `oauth2Login` per tenant, Spring Session met Redis, CSRF, logout (RP-initiated en
  back-channel) en `private_key_jwt`. `common-ground-authentication` verliest de converter, de token exchange
  en het resource-server-pad. Resolvers lezen de identiteit uit de sessie.
- **Keycloak:** per tenant-realm één client `nl-portal-backend` (vertrouwelijk, "Signed JWT", `at+jwt` aan,
  exacte redirect-URI's op het gemeentedomein) met mappers voor de BSN-claims en het betrouwbaarheidsniveau.
  `nl-portal` en `nl-portal-m2m` vervallen in het realm-template en in de docker-compose-realms.
- **Hosting:** Redis is verplicht voor elke cel (ADR 0017 punt 3); bij uitval moet de gebruiker opnieuw
  inloggen, er gaan geen gegevens verloren.
- **Documentatie:** het authenticatiepatroon in arc42 en het C4-model (#215) beschrijft voortaan de BFF; de
  ASVS-toetsing van de token exchange wordt herzien.
- **Breaking change**, toegestaan onder [ADR 0012](./0012-breaking-changes-tot-eerste-productie-release.md):
  eigen apps op de frontend-library die zelf tokens gebruiken, moeten mee.

## Normatieve herleiding

| Bron | Eis | Hoe dit besluit eraan voldoet |
|---|---|---|
| RFC 10017 §6.1 | BFF voor toepassingen met persoonsgegevens; vertrouwelijke client; cookie-eisen; CSRF | Besluit 1 tot en met 4 |
| ASVS 5.0 10.1.1 (L2) | Tokens alleen bij de backend | Besluit 2 en 8 |
| ASVS 5.0 10.4.16 (L3) | Vertrouwelijke client met `private_key_jwt` of mTLS | Besluit 1 en 9 |
| ASVS 5.0 9.2.2, RFC 9068 | Tokentype afdwingen, ID-token niet als access-token | Besluit 8 (geen `Bearer`-pad, `at+jwt` aan) |
| ASVS 5.0 9.2.3, 9.2.4, 10.3.1 | `iss` en `aud` valideren | Besluit 8, ADR 0021 besluit 2.6 |
| ASVS 5.0 10.3.3 | Unieke gebruikersidentiteit over issuers | Besluit 11 |
| ASVS 5.0 3.3, 3.5 | Cookies en CSRF | Besluit 2 en 4 |
| ASVS 5.0 7.2, 7.3, 7.4, 7.6 | Sessiebeheer, timeouts, uitloggen, federatie | Besluit 3, 5 en 6 |
| ASVS 5.0 6.3.3 (L3) | Hardwaregebonden, phishingbestendige authenticatie | Besluit 12 (gedocumenteerde afwijking voor DigiD Substantieel) |
| NL GOV OAuth 2.0 v1.1.0, OpenID.NLGov 1.0.1 | PKCE (S256); geen `client_secret` | Besluit 1 en 9 |
