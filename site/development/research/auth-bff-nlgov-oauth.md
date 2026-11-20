# BFF, NL GOV OAuth/OIDC-profielen, tokentype, DPoP en eHerkenning-domeintaal

Geraadpleegd op: 2026-11-21. Bouwt voort op [ASVS 5.0.0 level 3 voor token-exchange](./asvs-5-l3-token-exchange.md), de [toetsing daarvan](./asvs-5-l3-toetsing-token-exchange.md) en [Keycloak bij ~50 tenants](./multitenancy-keycloak-realms.md). Kader: [ADR 0017](../adr/0017-vluchtige-state-toegestaan.md) (vluchtige state zoals Redis voor BFF-sessies mag) en [ADR 0021](../adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md) (tenant uit het hostdomein, realm per tenant, issuer als tenantbinding).

## Samenvatting

- **De IETF-BCP is nu RFC 10017 (BCP 212, augustus 2026).** Die rangschikt drie architecturen van veilig naar minst veilig: BFF, token-mediating backend, browser-based OAuth-client. Voor zakelijke en gevoelige toepassingen, en toepassingen met persoonsgegevens, raadt hij de BFF "strongly" aan en de browser-based client af. NL Portal is nu het derde patroon.
- **ASVS 5.0.0 10.1.1 (L2)** zegt dat access- en refresh-tokens in een BFF-opzet alleen voor de backend bereikbaar zijn. Met tokens in de browser voldoet NL Portal daar op L2 niet aan, tenzij je de huidige opzet als afwijking vastlegt.
- **NL GOV:** het OAuth-profiel (v1.1.0, pas-toe-of-leg-uit) en OpenID.NLGov (v1.0.1, pas-toe-of-leg-uit via "Authenticatie-standaarden") staan **geen `client_secret`** toe voor confidential clients, alleen `private_key_jwt` of mTLS. Ze eisen PKCE met S256 en noemen browser-apps public clients die RFC 10017 *SHOULD* volgen. Token exchange staat niet in v1.1.0, wel als *SHOULD* in de concept-v1.2.0. Een apart "NL GOV client credentials"-profiel bestaat niet: de client credentials-flow is het cliënttype *direct access client* (§2.1.3) binnen het OAuth-profiel.
- **Tokentype:** Keycloak 26.3 kan `typ: at+jwt` uitgeven (clientinstelling sinds 26.2, standaard uit). Spring Security 7.1.2 heeft `JwtValidators.createAtJwtValidator()`. Standaard zet Keycloak in de header van zowel access- als ID-token `typ: JWT`, dus de huidige decoder kan ze niet op `typ` onderscheiden. Met een BFF accepteert de API geen browsertokens meer en wordt de vraag kleiner, maar hij verdwijnt niet.
- **DPoP:** in Keycloak 26.3 is DPoP nog preview (supported sinds 26.4). Spring Security 7.1.2 ondersteunt DPoP alleen in de **servlet**-resource server, niet in WebFlux, en heeft geen DPoP-client. oidc-client-ts 3.5.0 kan DPoP. Een BFF maakt DPoP in de browser overbodig. Voor backend → Keycloak blijft `private_key_jwt` de L3-hefboom (10.4.16); Keycloak ondersteunt die ("Signed JWT") voor elke confidential client, dus ook voor de token exchange.
- **Domeintaal:** eToegang spreekt van *dienstafnemer* (de partij voor wie de dienst is), *gebruiker* (de handelende natuurlijke persoon), *vertegenwoordigde*, *gemachtigde*, *machtiging*, *ketenmachtiging*, *intermediaire partij* en *bevoegdheid*. Het Stelsel Toegang (DigiD Machtigen) gebruikt *EindGebruiker*, *Vertegenwoordigde*, *Belanghebbende* en de attributen `ActingSubjectID` en `LegalSubjectID`.

## 1. Bronnen en versies

### Versies in de repo

| Component | Versie | Waar |
|---|---|---|
| Keycloak | `quay.io/keycloak/keycloak:26.3` | `docker-compose/docker-compose.yaml` (alleen demo/dev; de productieversie staat niet in de repo) |
| Spring Boot | 4.1.2 | `backend/gradle.properties` (`springBootVersion`) |
| Spring Security | 7.1.2 | `backend/gradle/verification-metadata.xml` (`spring-security-config` e.a.) |
| Spring Session BOM | 4.1.1 | idem (alleen BOM; Spring Session wordt nu niet gebruikt) |
| oidc-client-ts | 3.5.0 | `frontend/packages/authentication/package.json` |
| react-oidc-context | ^3.3.1 | idem |

### Primaire bronnen

| Bron | Link |
|---|---|
| RFC 10017 / BCP 212, OAuth 2.0 for Browser-Based Applications | https://www.rfc-editor.org/rfc/rfc10017.txt, status: https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/ |
| OWASP ASVS 5.0.0, V10 OAuth and OIDC | https://github.com/OWASP/ASVS/blob/v5.0.0/5.0/en/0x19-V10-OAuth-and-OIDC.md |
| OWASP ASVS 5.0.0, V3 Web Frontend Security (cookies, CSRF) | https://github.com/OWASP/ASVS/blob/v5.0.0/5.0/en/0x12-V3-Web-Frontend-Security.md |
| OWASP ASVS 5.0.0, V7 Session Management | https://github.com/OWASP/ASVS/blob/v5.0.0/5.0/en/0x16-V7-Session-Management.md |
| NL GOV Assurance profile for OAuth 2.0 v1.1.0 | https://gitdocumentatie.logius.nl/publicatie/api/oauth/ |
| NL GOV OAuth-profiel, concept (api-oauth-1.2.0, 2026-08-27) | https://logius-standaarden.github.io/OAuth-NL-profiel/ |
| OpenID NLGov 1.0.1 | https://gitdocumentatie.logius.nl/publicatie/api/oidc/ |
| Forum Standaardisatie, OAuth-profiel | https://www.forumstandaardisatie.nl/open-standaarden/nl-gov-assurance-profile-oauth-20 |
| Forum Standaardisatie, OIDC-profiel | https://www.forumstandaardisatie.nl/open-standaarden/nl-gov-assurance-profile-oidc |
| RFC 9068 (JWT-profiel access tokens), RFC 8725 (JWT BCP), RFC 9449 (DPoP) | https://www.rfc-editor.org/rfc/rfc9068.html, https://www.rfc-editor.org/rfc/rfc8725.html, https://www.rfc-editor.org/rfc/rfc9449.html |
| Keycloak release notes 26.2.0 (at+jwt, standard token exchange supported) | https://www.keycloak.org/docs/26.2.5/release_notes/index.html |
| Keycloak release notes 26.4.0 (DPoP supported) | https://www.keycloak.org/docs/26.6.4/release_notes/ |
| Keycloak-bron 26.3.0: `DefaultTokenManager`, `TokenUtil` | https://github.com/keycloak/keycloak/blob/26.3.0/services/src/main/java/org/keycloak/jose/jws/DefaultTokenManager.java |
| Keycloak admin docs 26.3.0: clientauthenticatie, geavanceerde instellingen | https://github.com/keycloak/keycloak/tree/26.3.0/docs/documentation/server_admin/topics/clients/oidc |
| Keycloak token exchange | https://www.keycloak.org/securing-apps/token-exchange (actuele docs, niet versie-gepind) |
| Spring Security 7.1.2-bron | https://github.com/spring-projects/spring-security/tree/7.1.2 |
| Spring Security 7.1 reference (reactive OAuth2, CSRF, DPoP servlet) | https://docs.spring.io/spring-security/reference/7.1/ |
| Spring Session WebSession | https://docs.spring.io/spring-session/reference/web-session.html |
| Spring Cloud Gateway TokenRelay | https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/tokenrelay-factory.html |
| oidc-client-ts 3.5.0 API en releases | https://github.com/authts/oidc-client-ts/blob/v3.5.0/docs/oidc-client-ts.api.md, https://github.com/authts/oidc-client-ts/releases/tag/v3.1.0 |
| Afsprakenstelsel eToegang (AS1.24e), begrippenlijst en interfacespecificaties | https://afsprakenstelsel.etoegang.nl/Startpagina/as/begrippenlijst |
| Logius, Stelsel Toegang SAML 1.0.0 (2026-06-01) | https://gitdocumentatie.logius.nl/publicatie/st/saml/1.0.0/ |

Werkwijze: RFC's, ASVS en profielen zijn gelezen via een samenvattende fetch-tool. Spring Security en Keycloak zijn waar nodig in de broncode op de gebruikte tag gecontroleerd (`gh api`).

## 2. BFF-patroon

### 2.1 Wat RFC 10017 zegt

RFC 10017 is gepubliceerd als Best Current Practice (BCP 212) in augustus 2026; het laatste concept was `draft-ietf-oauth-browser-based-apps-27` ([datatracker](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/)). §6 beschrijft drie architecturen "in decreasing order of security":

| Patroon | Wat het is | Oordeel RFC 10017 | Waarom |
|---|---|---|---|
| **BFF** (§6.1) | Server-side component is de OAuth-client; de browser heeft alleen een sessiecookie; de BFF proxyt API-calls en voegt het token toe | §6.1.4.3: "strongly recommended for business applications", en voor gevoelige toepassingen en toepassingen met persoonsgegevens | Tokens komen nooit in de browser, dus diefstal van tokens (§5.1.1, §5.1.2) kan niet. De BFF is confidential, dus een gestolen autorisatiecode is onbruikbaar (§5.1.3). Wat overblijft is *client hijacking* (schadelijke JS doet requests via de sessie), dat "inherent to web applications" is. HttpOnly-cookies voorkomen dat dit escaleert naar sessiekaping. |
| **Token-mediating backend** (§6.2) | Backend haalt tokens op (confidential) en houdt het refresh-token; het access-token gaat naar de browser | §6.2.4.4: eerst "strongly recommended to evaluate" of een volledige BFF kan | Refresh-token en nieuwe flows zijn beschermd, maar het access-token is te stelen en een aanvaller kan via de backend steeds nieuwe tokens laten ophalen (§5.1.4). |
| **Browser-based OAuth client** (§6.3) | SPA is een public client met PKCE en houdt de tokens zelf | §6.3.4.3: "not recommended for business applications", ook niet voor gevoelige toepassingen en persoonsgegevens | Alle aanvalsscenario's uit §5 gelden. §6.3.4.1: een aanvaller die een *nieuwe flow* start (stille autorisatie in een iframe) krijgt een volledige tokenset, ook als opgeslagen tokens geïsoleerd zijn. DPoP helpt daar niet tegen (§6.3.4.2.2). |

Eisen aan een BFF uit RFC 10017:

- **Confidential client** (§6.1.3.1): de BFF "MUST act as a confidential client" en gebruikt de authorization code grant.
- **Cookies** (§6.1.3.2): `Secure` en `HttpOnly` *MUST*; `SameSite=Strict`, `Path=/`, geen `Domain` en het prefix `__Host-Http-` *SHOULD*. Bij client-side sessies (tokens ín de cookie) *SHOULD* de cookie versleuteld zijn.
- **CSRF** (§6.1.3.3): "The BFF MUST implement a proper CSRF defense." Opties: SameSite (alleen genoeg als de BFF nooit same-site is met andere apps; subdomeinovername breekt het), CORS met een **verplichte eigen request-header** (§6.1.3.3.2), of anti-forgery/double-submit-tokens (§6.1.3.3.3).
- §6.1.3.7: server-side clients kunnen makkelijker geavanceerde maatregelen nemen, met sender-constrained tokens als voorbeeld.

### 2.2 Wat ASVS 5.0.0 zegt

| Eis | Lvl | Inhoud (parafrase) | NL Portal nu |
|---|---|---|---|
| 10.1.1 | L2 | Tokens alleen naar componenten die ze strikt nodig hebben; in een BFF-opzet zijn access- en refresh-tokens alleen voor de backend bereikbaar | Niet voldaan: het access-token staat in de browser (`ApiContext.tsx`, `useDownload.tsx`, `FormIoUploader.tsx` sturen `Bearer`). |
| 10.1.2 | L2 | Code en ID-token alleen accepteren uit een flow van dezelfde user-agent-sessie; PKCE-verifier, `state`, `nonce` onraadbaar en sessiegebonden | oidc-client-ts doet dit in de browser; bij een BFF doet Spring het server-side. |
| 10.2.1 | L2 | CSRF op de code-flow tegengaan met PKCE of `state` | Idem. |
| 10.4.16 | L3 | Clients zijn confidential, met `private_key_jwt` of mTLS | Een public SPA-client kan hier nooit aan voldoen; een BFF wel. |
| 3.3.1 / 3.3.3 / 3.3.4 | L1/L2/L2 | Cookies `Secure` met `__Secure-`- of `__Host-`-prefix; op L2 `__Host-` tenzij bewust gedeeld; sessiecookies `HttpOnly` | Nieuw bij een BFF. |
| 3.3.2 | L2 | `SameSite` passend bij het doel | Nieuw bij een BFF. |
| 3.5.1 / 3.5.2 / 3.5.3 | L1 | CSRF: anti-forgery-token of niet-safelisted header, of steunen op CORS-preflight; gevoelige functies niet via GET | Nieuw bij een BFF (nu draagt de Bearer-header de CSRF-bescherming). |
| 7.2.1 / 7.2.3 / 7.2.4 | L1 | Sessie server-side verifiëren; referentietokens ≥128 bit CSPRNG; nieuwe sessie bij elke (her)authenticatie | Nieuw bij een BFF. |
| 7.3.1 / 7.3.2 | L2 | Inactiviteits- en absolute time-out | Nieuw bij een BFF (nu bepaalt Keycloak het via tokenlevensduur). |
| 7.4.1 | L1 | Na uitloggen of verlopen de sessie server-side ongeldig maken | Nieuw bij een BFF. |
| 7.1.3 / 7.6.1 | L2 | Alle sessie-aanmakende systemen in een federatie documenteren; RP- en IdP-sessies op elkaar afstemmen | Geldt nu al (Keycloak-SSO + DigiD/eHerkenning); bij een BFF komt er een derde sessie bij. |

### 2.3 Wat een BFF hier concreet betekent

**Wie is de confidential client.** De Spring Boot-backend (of een aparte BFF-gateway ervoor) wordt per tenant de OIDC-client met de code-flow, PKCE en `private_key_jwt` (zie §3). De public client `nl-portal` vervalt; de SPA doet geen OIDC meer. Of `nl-portal-m2m` blijft, hangt af van de token exchange (hieronder).

**Sessiecookie.** De SPA en de BFF moeten op hetzelfde origin draaien (hetzelfde gemeentedomein; dat sluit aan op ADR 0021). Cookie bijvoorbeeld `__Host-Http-SESSION` (RFC 10017 §6.1.3.2), `Secure; HttpOnly; SameSite=Strict; Path=/`, zonder `Domain`. Let op: na de terugkeer van Keycloak (cross-site redirect) wordt een `Strict`-cookie bij die eerste navigatie niet meegestuurd. De callback werkt met de autorisatieverzoek-state in de sessie, dus dat vraagt `Lax` voor die cookie of een tussenstap *(niet geverifieerd hoe Spring dit standaard oplost)*. Sessiedata (tokens, `OAuth2AuthorizedClient`) staan in Redis met TTL, wat ADR 0017 toestaat.

**CSRF.** Kies één van de opties uit RFC 10017 §6.1.3.3. In WebFlux: `CookieServerCsrfTokenRepository` (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`), standaard met `XorServerCsrfTokenRequestAttributeHandler` (BREACH-bescherming, sinds 6.0) ([Spring Security 7.1, reactive CSRF](https://docs.spring.io/spring-security/reference/7.1/reactive/exploits/csrf.html)). Een `.spa()`-snelkoppeling zoals in servlet staat niet in de reactive docs. Het alternatief: een verplichte eigen header (bijvoorbeeld `X-Requested-With`) plus strikte CORS, wat bij GraphQL (alleen `POST` met `application/json`) eenvoudig is.

**GraphQL en andere calls.** Apollo stuurt dan geen `Authorization`-header meer, maar `credentials: 'same-origin'` met de cookie (en de CSRF-header). Uploads (`FormIoUploader`) en downloads (`useDownload`) gaan net zo. Aan de backendkant zijn er twee varianten:

1. **BFF in dezelfde app:** de sessie authenticeert het verzoek (`oauth2Login`), en resolvers lezen de claims uit de sessie-`OidcUser` of het access-token in de `OAuth2AuthorizedClient`. Het resource-serverpad (`oauth2ResourceServer`) is dan voor de browser niet meer nodig.
2. **Aparte BFF-gateway** (bijvoorbeeld Spring Cloud Gateway met `TokenRelay`): de gateway zet het access-token van de sessie op het doorgestuurde verzoek. De bestaande resource server blijft zoals hij is ([TokenRelay](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/tokenrelay-factory.html)). Dat is een extra component per cel.

**Logout.** RP-initiated logout via `OidcClientInitiatedServerLogoutSuccessHandler` (bestaat in 7.1.2) en backchannel-logout van Keycloak naar de BFF via `OidcBackChannelServerLogoutHandler` (bestaat in 7.1.2, `config/web/server`) ([Spring Security 7.1.2-bron](https://github.com/spring-projects/spring-security/tree/7.1.2)). Daarmee kan de BFF de Redis-sessie ongeldig maken (ASVS 7.4.1) als de Keycloak-sessie eindigt.

**Is de token exchange nog nodig?** Nu dient hij om `aanvrager.bsn`/`aanvrager.kvk` in een token te krijgen dat de browser-client niet krijgt. Met een BFF is de BFF-client zelf confidential en kan hij die scope direct krijgen. Twee varianten:

- **Weglaten:** de BFF-client krijgt de `aanvrager`-scope als default scope. Eén client en één token minder. Nadeel: het sessietoken draagt dan altijd de BSN; dataminimalisatie moet uit de tokenlevensduur en de Redis-TTL komen.
- **Behouden voor downscoping per downstream-API:** Keycloak staat toe dat een client een token uitwisselt dat aan zichzelf is uitgegeven (dan is `aud` = requester niet nodig) ([Keycloak token exchange](https://www.keycloak.org/securing-apps/token-exchange)). Spring Security 7.1.2 heeft daarvoor `TokenExchangeReactiveOAuth2AuthorizedClientProvider`. Zinvol als ZGW/BRP-API's tokens met een eigen `aud` moeten krijgen (ASVS 10.3.1 aan hun kant).

### 2.4 Spring Security-bouwstenen (reactive, 7.1.2)

| Bouwsteen | Rol | Bron |
|---|---|---|
| `ServerHttpSecurity.oauth2Login()` | Code-flow + PKCE, sessie | [reactive OAuth2 Login](https://docs.spring.io/spring-security/reference/7.1/reactive/oauth2/login/core.html) |
| `ReactiveClientRegistrationRepository` (eigen implementatie) | Clientregistratie per tenant | bron 7.1.2 |
| `ServerOAuth2AuthorizationRequestResolver` / `DefaultServerOAuth2AuthorizationRequestResolver` | Bepaalt welke registratie bij `/oauth2/authorization/{registrationId}` hoort | bron 7.1.2 |
| `WebSessionServerOAuth2AuthorizedClientRepository` | Houdt tokens in de `WebSession` (dus in Redis) | bron 7.1.2 |
| Spring Session `@EnableRedisWebSession` | `WebSession` in Redis | [Spring Session WebSession](https://docs.spring.io/spring-session/reference/web-session.html) |
| `ServerOAuth2AuthorizedClientExchangeFilterFunction` | `WebClient` zet het token van de gebruiker op uitgaande calls (en ververst het) | bron 7.1.2 |
| `TokenExchangeReactiveOAuth2AuthorizedClientProvider` | RFC 8693 vanuit de client | bron 7.1.2; [authorization grants](https://docs.spring.io/spring-security/reference/7.1/reactive/oauth2/client/authorization-grants.html) |
| `NimbusJwtClientAuthenticationParametersConverter` | `private_key_jwt` op de token-endpoint | [reactive client authentication](https://docs.spring.io/spring-security/reference/7.1/reactive/oauth2/client/client-authentication.html) |
| `OidcClientInitiatedServerLogoutSuccessHandler`, `OidcBackChannelServerLogoutHandler` | RP-initiated en backchannel-logout | bron 7.1.2 |
| `CookieServerCsrfTokenRepository`, `XorServerCsrfTokenRequestAttributeHandler` | CSRF | [reactive CSRF](https://docs.spring.io/spring-security/reference/7.1/reactive/exploits/csrf.html) |
| Spring Cloud Gateway `TokenRelay` | Alleen bij een aparte gateway | [TokenRelay](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/tokenrelay-factory.html) |

De reactive client-authentication-pagina noemt `tls_client_auth` (mTLS) niet; voor mTLS naar Keycloak moet de `WebClient` zelf een clientcertificaat krijgen *(niet uitgezocht)*.

### 2.5 BFF en multi-tenancy (realm per tenant)

- **Een registratie per tenant.** Per realm is er een eigen issuer, client en sleutel. Een eigen `ReactiveClientRegistrationRepository` levert de `ClientRegistration` van de tenant die uit de host volgt (ADR 0021). Gebruik als `registrationId` de tenant-id. Laat de `ServerOAuth2AuthorizationRequestResolver` het pad `/oauth2/authorization/{registrationId}` alleen accepteren voor de tenant van de host, zodat `gemeente-a.nl/oauth2/authorization/gemeente-b` niet werkt *(eigen ontwerp, niet uit de docs)*.
- **Redirect-URI** per host via de placeholder `{baseUrl}/login/oauth2/code/{registrationId}` *(placeholder-ondersteuning niet opnieuw geverifieerd voor 7.1)*.
- **Mix-up:** met meerdere authorization servers in één runtime geldt ASVS 10.2.2: valideer `iss` in de autorisatie- en tokenresponse. Spring valideert de `iss` van het ID-token tegen de registratie; of 7.1.2 de RFC 9207-`iss`-parameter in de autorisatieresponse controleert, is *niet geverifieerd*.
- **Sessie en tenant:** bind de sessie aan de tenant. Het cookie is per host (`__Host-`, geen `Domain`), dus een sessie lekt niet naar een ander gemeentedomein. In Redis hoort de tenant in de sleutel of in een sessieattribuut dat bij elk verzoek met de host wordt vergeleken.
- **Token exchange** blijft binnen één realm ([multitenancy-keycloak-realms.md](./multitenancy-keycloak-realms.md)). Gebruik de token-endpoint-URL uit de registratie van de tenant, niet uit `jwt.issuer` (toetsing 13.2.4).

**Conclusie voor NL Portal:** RFC 10017 en ASVS 10.1.1 (L2) wijzen beide naar een BFF; de huidige public SPA-client is het patroon dat RFC 10017 afraadt voor toepassingen met persoonsgegevens. Een BFF past in ADR 0017 (Redis) en ADR 0021 (registratie per host). De bouwstenen zitten in Spring Security 7.1.2, behalve DPoP (§5). De grootste wijzigingen zitten in de SPA (geen oidc-client-ts meer, cookies en CSRF-header) en in de keuze tussen BFF-in-de-app of een aparte gateway.

## 3. NL GOV Assurance-profielen (OAuth 2.0 en OIDC)

### 3.1 Status

| Profiel | Versie | Status Forum Standaardisatie | Toepassingsgebied |
|---|---|---|---|
| NL GOV Assurance profile for OAuth 2.0 | Logius-standaard v1.1.0, definitief 2024-12-03 ([Logius](https://gitdocumentatie.logius.nl/publicatie/api/oauth/)) | Pas-toe-of-leg-uit. De pagina meldt een OBDO-besluit van 29-09-2026, ingangsdatum 1-1-2027, en noemt versie 1.1 (de tabel bevat ook een tegenstrijdige datum "1 januari 2017") ([Forum](https://www.forumstandaardisatie.nl/open-standaarden/nl-gov-assurance-profile-oauth-20)) | Applicaties waar gebruikers of *resource owners* "impliciet of expliciet toestemming geven" voor toegang tot gegevens via een REST-API |
| OpenID.NLGov | 1.0.1, definitief 2023-09-18 ([Logius](https://gitdocumentatie.logius.nl/publicatie/api/oidc/)); Forum noemt 1.0 | Pas-toe-of-leg-uit sinds 21-09-2023, samen met SAML onder "Authenticatie-standaarden (OpenID.NLGov en SAML)" ([Forum](https://www.forumstandaardisatie.nl/open-standaarden/nl-gov-assurance-profile-oidc)) | "het beschikbaar stellen en het gebruik van federatieve authenticatiediensten", inclusief vertegenwoordiging en attribuutverstrekking |
| OAuth-profiel concept (api-oauth-1.2.0) | Concept, 2026-08-27 ([Logius GitHub Pages](https://logius-standaarden.github.io/OAuth-NL-profiel/)) | Niet op de lijst | Idem |

Het werkingsgebied is in beide gevallen Nederlandse overheden (Rijk, provincies, gemeenten, waterschappen) en de (semi-)publieke sector. Gemeenten moeten deze profielen dus toepassen of in hun jaarverslag uitleggen waarom niet.

### 3.2 Eisen per onderwerp

| Onderwerp | OAuth-profiel v1.1.0 | OpenID.NLGov 1.0.1 |
|---|---|---|
| Cliënttypes | §2.1.1 *full client with user delegation* (code-flow); §2.1.2 *native client* (public met PKCE of per-instantie-registratie; "Client credentials MUST NOT be shared among instances"); §2.1.3 *direct access client* (client credentials) | §4.1.1 web-apps confidential; §4.1.2 browser-apps public, "SHOULD follow the best practices" van OAuth for Browser-Based Apps (nu RFC 10017); §4.1.3 native public tenzij per-instantie-secrets |
| Clientauthenticatie | §2.3.3: "only the private_key_jwt method" (RFC 7523), `aud` = token-endpoint, `jti` ≥128 bit; elders (§2.1.1, §2.3.3) ook `tls_client_auth`; tussen organisaties PKIoverheid-certificaten met OIN (§2.3.4). `client_secret` wordt niet genoemd | §4.3.1: `private_key_jwt` of mTLS; één methode per verzoek. Geen `client_secret` |
| PKCE | §2.3.1: public clients *MUST* PKCE met S256; §3.1.7: AS *MUST NOT* `plain` toestaan | §4.1/§4.2.1: PKCE voor alle clients, S256 |
| Flows | Code-flow; `redirect_uri` exact; `state` ≥128 bit | §3: "Implicit Flow and Hybrid flow MUST NOT be used" |
| Token-formaat | §3.2.1: access tokens zijn getekende JWT's met `kid`; verplicht `iss`, `azp`, `exp`, `jti`; `aud` en `sub` optioneel; §3.5.1 verwijst naar RFC 9068. `at+jwt` niet expliciet | §5.2.7: JWT-access-tokens. ID-token ≤5 min (§5.2.2) |
| Levensduur | §3.4: access tokens code-flow ≤1 uur, public clients ≤15 min, client credentials ≤6 uur; refresh ≤24 uur | §5.2.8/§7.2: refresh-tokens one-time-use als niet sender-constrained, max 6 uur inactiviteit |
| Client credentials | §2.1.3: *direct access client*; "MUST NOT request or be issued a refresh token"; AS *MAY* ondersteunen (§3.1.1) | n.v.t. |
| Token exchange | Buiten scope (§2.1: aanvullende granttypes) | Niet behandeld; vertegenwoordiging via de `represents`-claim (§5.2.4), niet via RFC 8693 `act` |
| Sender-constrained | §5.1 (geavanceerd): proof-of-possession met mTLS/PKIoverheid of `cnf`; DPoP niet genoemd | Refresh-tokens zie boven |
| Vertegenwoordiging | n.v.t. | §5.2.4: `sub` = de eindgebruiker, `represents` = de vertegenwoordigde, met `sub` en `iss`; clients *MUST* `represents` verwerken als vertegenwoordiging is afgesproken (§4.3.4) |
| Identifiers | n.v.t. | §5.2.3: pairwise identifiers *MUST*; BSN, RSIN en KvK-nummer *MUST NOT* als `sub` waar correlatie ongewenst is |
| LoA | n.v.t. | §5.2.5: `acr` met eIDAS-LoA aanbevolen; OP's *MUST NOT* `amr` leveren |

Het concept 1.2.0 voegt toe: token exchange *SHOULD* door de AS ondersteund worden, met SAML-bearer (RFC 7522) als alternatief (§2.1.1); DPoP als geavanceerde optie (§4.2); access tokens volgens RFC 9068 (§2.2.1); refresh-tokens van public clients sender-constrained of one-time-use (§2.1.9).

### 3.3 Bestaat er een "NL GOV OAuth client credentials flow"?

Niet als apart profiel. Binnen het OAuth-profiel is het cliënttype **direct access client** (§2.1.3) de client credentials-flow: geen gebruiker, authenticatie met `private_key_jwt` (of mTLS), geen refresh-token, access tokens ≤6 uur (§3.4), geen dynamische registratie (§3.1.3). Het Forum-toepassingsgebied gaat over toegang *met* toestemming van een resource owner; of puur machine-to-machine onder de pas-toe-of-leg-uit-plicht valt, zegt de Forum-pagina niet *(niet geverifieerd)*.

### 3.4 Betekenis voor NL Portal

- `nl-portal-m2m` gebruikt nu `client_secret_post` (de Keycloak-templates zetten `clientAuthenticatorType: client-secret`). Dat wijkt af van beide profielen en van ASVS 10.4.16.
- De public SPA-client met PKCE S256 voldoet aan de letter van OpenID.NLGov §4.1.2, die naar RFC 10017 verwijst, maar RFC 10017 raadt dit patroon af voor toepassingen met persoonsgegevens (§2.1). Een BFF is in het profiel een *web application* = confidential client met `private_key_jwt`.
- Keycloak is hier de OP richting de SPA/BFF. Pairwise `sub` (§5.2.3), `acr` in plaats van `amr` (§5.2.5) en `represents` (§5.2.4) zijn OP-eisen. Of de huidige claims (`aanvrager`, `gemachtigde`) een gedocumenteerde afwijking van `represents` moeten worden, is een besluit.

**Conclusie voor NL Portal:** beide profielen zijn pas-toe-of-leg-uit voor gemeenten. De harde punten zijn: geen `client_secret` maar `private_key_jwt` of mTLS; PKCE S256; JWT-access-tokens met `kid`; en vertegenwoordiging via `represents`. Token exchange en DPoP zijn (nog) geen eis.

## 4. Tokentype: `at+jwt` versus `aud`/`azp`

### 4.1 Wat de standaarden zeggen

- **RFC 9068 §2.1:** JWT-access-tokens *MUST* `typ` = `at+jwt` hebben (`application/` weggelaten *SHOULD*). §2.2: verplichte claims `iss`, `exp`, `aud`, `sub`, `client_id`, `iat`, `jti`. §4: de RS controleert `typ`, `iss` exact, `aud` bevat de eigen identifier, handtekening (geen `alg: none`) en `exp`. §5: expliciete typering helpt "to distinguish between JWT access tokens and OpenID Connect ID Tokens"; de AS *MUST* per resource een eigen `aud` gebruiken.
- **RFC 8725 §3.11/§3.12:** gebruik expliciete typering, en de validatieregels voor verschillende soorten JWT's van dezelfde issuer "MUST be written such that they are mutually exclusive". Middelen: eigen `typ`, andere verplichte claims, andere sleutels, andere `aud`, andere issuer.
- **ASVS 5.0.0** 9.2.2 (L2) eist een tokentypecontrole; `at+jwt` zelf is geen eis ([eerder onderzoek, §5](./asvs-5-l3-token-exchange.md)).

### 4.2 Keycloak 26.3

- Clientinstelling **"Use 'at+jwt' as access token header type"** (Clients → Advanced → Fine grain OpenID Connect configuration), nieuw in **26.2.0**, **standaard uit** ([release notes 26.2.0](https://www.keycloak.org/docs/26.2.5/release_notes/index.html)). Zit dus in 26.3.
- In de broncode van 26.3.0 krijgt een access-token `at+jwt` als de client `isUseRfc9068AccessTokenHeaderType()` heeft, anders `"JWT"`. ID-tokens krijgen ook `"JWT"` ([`DefaultTokenManager.type()`](https://github.com/keycloak/keycloak/blob/26.3.0/services/src/main/java/org/keycloak/jose/jws/DefaultTokenManager.java)). Het onderscheid zit standaard alleen in de payload-claim `typ` (`Bearer` versus `ID`, [`TokenUtil`](https://github.com/keycloak/keycloak/blob/26.3.0/core/src/main/java/org/keycloak/util/TokenUtil.java)). Dat is Keycloak-specifiek.
- De instelling hoort bij de client waaraan het token wordt uitgegeven. Bij token exchange is dat de requester (`nl-portal-m2m`); bij een BFF de BFF-client.
- Of een Keycloak-access-token voor een gebruiker standaard de RFC 9068-claim `client_id` bevat, is *niet geverifieerd* (`azp` zit er wel in).

### 4.3 Spring Security 7.1.2

- `JwtValidators.createDefault()` bevat sinds 6.5 `JwtTypeValidator.jwt()`: `typ` moet `JWT` zijn of ontbreken ([bron](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-jose/src/main/java/org/springframework/security/oauth2/jwt/JwtValidators.java)). Een Keycloak-ID-token (`typ: JWT`) komt daar dus doorheen.
- `JwtValidators.createAtJwtValidator()` (sinds 6.5) eist `typ` ∈ {`at+jwt`, `application/at+jwt`}, plus `exp`, `sub`, `iat`, `jti` en `client_id`, en dwingt af dat je `issuer(...)` en `audience(...)` instelt. `client_id` is te vervangen via `validators(...)` als Keycloak hem niet levert.

### 4.4 Standaardconforme manier om een ID-token te weigeren

1. **Expliciete typering (RFC 9068 + RFC 8725 §3.11):** zet `at+jwt` aan op de uitgevende client en valideer met `createAtJwtValidator()`. Dit is de enige maatregel die de standaard expliciet voor dit doel noemt.
2. **Unieke `aud` (RFC 9068 §5, RFC 8725 §3.12, ASVS 10.3.1):** een Keycloak-ID-token heeft `aud` = de `client_id` van de client die inlogt. Als de API alleen een eigen audience accepteert (bijvoorbeeld `nl-portal-api`) die nooit gelijk is aan een `client_id`, valt een ID-token af. Dit sluit ook tokens voor andere resources uit en is daarom hoe dan ook nodig.
3. **`azp` alleen** onderscheidt niet: het ID-token en het access-token van dezelfde client hebben dezelfde `azp`.
4. **Payload-`typ` = `Bearer`:** werkt bij Keycloak, maar is niet standaard.

### 4.5 Verdwijnt de vraag met een BFF?

Grotendeels. De browser heeft dan geen tokens, en de BFF valideert het ID-token zelf als OIDC-client (Spring doet `iss`, `aud`, `nonce`, handtekening). De API accepteert geen tokens van de browser meer. Hij blijft relevant als (a) een aparte gateway tokens doorgeeft aan een resource server (variant 2 in §2.3), of (b) downstream-API's tokens van Keycloak valideren. Dan geldt §4.4 voor die resource servers.

**Conclusie voor NL Portal:** combineer `at+jwt` (één Keycloak-clientinstelling, al beschikbaar in 26.3) met een unieke `aud`. Die combinatie voldoet aan RFC 9068 en RFC 8725 en aan ASVS 9.2.2/10.3.1. Met een BFF verschuift de check naar de interne en downstream-resource servers.

## 5. DPoP en sender-constrained tokens

### 5.1 Ondersteuning in de gebruikte versies

| Component | DPoP | Bron |
|---|---|---|
| Keycloak 26.3 | **Preview** (sinds 23); "Authorization Code Binding to a DPoP Key" in 26.1. **Supported vanaf 26.4.0**, met de opties om alleen refresh-tokens van public clients te binden en `dpop_jkt` te eisen. Clientinstelling: "Require Demonstrating Proof of Possession (DPoP) header in token requests" | [release notes 26.2.0](https://www.keycloak.org/docs/26.2.5/release_notes/index.html), [26.4.0](https://www.keycloak.org/docs/26.6.4/release_notes/), [admin docs 26.3.0](https://github.com/keycloak/keycloak/blob/26.3.0/docs/documentation/server_admin/topics/clients/oidc/con-advanced-settings.adoc) |
| Keycloak token exchange | Een sender-constrained `subject_token` werkt alleen als de requester de client is waaraan het token is uitgegeven, met bewijs (DPoP-proof of het mTLS-certificaat) | [Keycloak token exchange](https://www.keycloak.org/securing-apps/token-exchange) (actuele docs; geldt mogelijk niet voor 26.3) |
| Spring Security 7.1.2, resource server | **Alleen servlet**: `DPoPAuthenticationProvider`, `DPoPAuthenticationConverter`, `OAuth2ResourceServerConfigurer` (servlet). `ServerHttpSecurity` en de reactive docs bevatten geen DPoP | [bronboom 7.1.2](https://github.com/spring-projects/spring-security/tree/7.1.2), [servlet DPoP-docs](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/dpop-tokens.html) |
| Spring Security 7.1.2, OAuth2-client | Geen DPoP-client (geen DPoP-klassen in `oauth2-client`); de docs tonen alleen hoe je met Nimbus zelf een proof bouwt | idem |
| oidc-client-ts 3.5.0 | **Ja**: `dpop`-instelling, `IndexedDbDPoPStore`, `UserManager.dpopProof(url, user, method, nonce)`, `bind_authorization_code`; DPoP-nonce sinds 3.1.0 | [API 3.5.0](https://github.com/authts/oidc-client-ts/blob/v3.5.0/docs/oidc-client-ts.api.md), [release 3.1.0](https://github.com/authts/oidc-client-ts/releases/tag/v3.1.0) |

`private_key_jwt`: Keycloak kent voor confidential clients de authenticators "Client ID and Secret", "**Signed JWT**" (`private_key_jwt`), "Signed JWT with Client Secret" en "X509 Certificate" (`tls_client_auth`) ([admin docs 26.3.0](https://github.com/keycloak/keycloak/blob/26.3.0/docs/documentation/server_admin/topics/clients/oidc/con-confidential-client-credentials.adoc)). De token-exchange-docs zeggen dat het verzoek "must be authenticated by the appropriate client authentication method that is configured for the client"; de voorbeelden gebruiken `client_secret_post`. Dat `private_key_jwt` dus ook voor token exchange werkt, volgt daaruit, maar is niet expliciet getest *(niet geverifieerd in 26.3)*. Sinds 26.2.0 eist Keycloak één `aud` in de client-assertion ([release notes 26.2.0](https://www.keycloak.org/docs/26.2.5/release_notes/index.html)).

### 5.2 Maakt een BFF DPoP in de browser overbodig?

Ja, voor het doel van DPoP. DPoP maakt een gestolen token onbruikbaar zonder de sleutel, en in een BFF staat er geen token in de browser. RFC 10017 zegt ook dat DPoP in een browser-client het starten van een nieuwe flow niet voorkomt (§6.3.4.2.2). Daarmee is DPoP in de SPA de zwakkere variant van wat een BFF bereikt. Het cookie is geen sender-constrained token; daarvoor zorgen `HttpOnly`, `__Host-` en CSRF (§2).

### 5.3 Wat blijft voor de backend

| Verbinding | ASVS L3-eis | Opties |
|---|---|---|
| BFF/backend → Keycloak (token-endpoint, exchange) | 10.4.16 (sterke clientauthenticatie), 10.4.14 (AS geeft alleen sender-constrained tokens) | `private_key_jwt` met `NimbusJwtClientAuthenticationParametersConverter` (beschikbaar). DPoP-gebonden tokens voor de BFF vragen Keycloak ≥26.4 en zelfgebouwde proofs (geen Spring-client). mTLS-gebonden tokens kunnen met een clientcertificaat op de `WebClient`. |
| Backend → eigen API (als resource server blijft) | 10.3.5 | Reactive Spring heeft geen DPoP; mTLS-binding (`cnf.x5t#S256`) of de resource server naar servlet verplaatsen. Bij BFF-in-de-app vervalt deze verbinding. |
| Backend → ZGW/BRP/KvK-API's | 10.3.5 aan *hun* kant | Hangt af van wat die API's ondersteunen; meestal statische API-sleutels of eigen tokens, dus buiten deze vraag. |

**Conclusie voor NL Portal:** met een BFF is DPoP in de browser niet nodig. De L3-hefboom wordt `private_key_jwt` voor de BFF-client, wat Keycloak 26.3 en Spring Security 7.1.2 allebei ondersteunen. DPoP aan de backendkant vraagt een Keycloak-upgrade naar ≥26.4 en eigen code, omdat Spring Security 7.1.2 DPoP niet in WebFlux of in de OAuth2-client heeft. mTLS-binding is het alternatief dat zonder Spring-DPoP werkt.

## 6. Domeintaal eHerkenning en DigiD Machtigen

### 6.1 eToegang-afsprakenstelsel (AS1.24e)

| Term | Betekenis volgens het afsprakenstelsel | Bron |
|---|---|---|
| **Dienstafnemer** | Partij die elektronische toegangsdiensten gebruikt om een dienst af te nemen bij een dienstverlener: een natuurlijk persoon (ondernemer, privépersoon of burger) of een niet-natuurlijk persoon. "is de Gebruiker of wordt vertegenwoordigd door een gebruiker." In uitingen naar bedrijven mag "bedrijf of organisatie" | [dienstafnemer](https://afsprakenstelsel.etoegang.nl/Startpagina/as/dienstafnemer) |
| **Gebruiker** | Natuurlijk persoon die elektronische toegangsdiensten inzet om een dienst te ontvangen (de handelende persoon) | [gebruiker](https://afsprakenstelsel.etoegang.nl/Startpagina/as/gebruiker) |
| **Vertegenwoordigde** | Partij die een vertegenwoordiger de bevoegdheid heeft verleend om in haar naam te handelen (art. 3:60 lid 1 BW) | [vertegenwoordigde](https://afsprakenstelsel.etoegang.nl/Startpagina/as/vertegenwoordigde) |
| **Gemachtigde** | Partij die op grond van wet of machtiging bevoegd is om in naam van de vertegenwoordigde handelingen te verrichten; mag een buitenlands natuurlijk persoon zijn | [gemachtigde](https://afsprakenstelsel.etoegang.nl/Startpagina/as/gemachtigde) |
| **Bevoegdheid** | "Het recht van een persoon om een handeling te verrichten." | [bevoegdheid](https://afsprakenstelsel.etoegang.nl/Startpagina/as/bevoegdheid) |
| **Ketenmachtiging** | Organisatie machtigt een tussenpartij (*intermediaire partij*), die een medewerker aanwijst; de "laatste schakel" handelt namens de organisatie | [ketenmachtiging](https://afsprakenstelsel.etoegang.nl/Startpagina/as/ketenmachtiging) |
| Overig in de begrippenlijst | Vertegenwoordiging, Vertegenwoordiger, Vertegenwoordigde dienstafnemer, Wettelijke vertegenwoordiging, Machtiging, Machtigingenregister (MR), Dienstverlener (DV), Intermediaire partij, Identificerend kenmerk | [begrippenlijst](https://afsprakenstelsel.etoegang.nl/Startpagina/as/begrippenlijst) |

Niet in de begrippenlijst: *belanghebbende*, *handelende persoon/partij*, *machtigingendienst*.

SAML-attributen (eToegang): `urn:etoegang:core:ActingSubjectID` (identiteit van de gebruiker, als `EncryptedID` per ontvanger) ([ActingSubjectID](https://afsprakenstelsel.etoegang.nl/Startpagina/as/actingsubjectid)); *EntityConcernedID* = "the identifying attribute of the service consumer that is represented by the user (who might be the same)", met typen als `urn:etoegang:1.9:EntityConcernedID:KvKnr`, `…:RSIN`, `urn:etoegang:1.12:EntityConcernedID:BSN`, `…:Pseudo` ([EntityConcernedID](https://afsprakenstelsel.etoegang.nl/Startpagina/as/entityconcernedid-saml), [identificerende kenmerken](https://afsprakenstelsel.etoegang.nl/Startpagina/as/identificerende-kenmerken)); `urn:etoegang:core:Representation` (boolean: is er vertegenwoordiging) ([Representation](https://afsprakenstelsel.etoegang.nl/Startpagina/as/representation)); `urn:etoegang:core:ServiceUUID` (de dienst; NL Portal leest die al als `MACHTIGINGSDIENST_KEY`); `IntermediateEntityID` (bijvoorbeeld `urn:etoegang:1.13:IntermediateEntityID:TRR-BD`) voor de intermediair bij ketenmachtiging. eToegang kent **geen** `LegalSubjectID`.

### 6.2 DigiD Machtigen (Stelsel Toegang SAML 1.0.0, Logius, 2026-06-01)

- Rollen (§3.1/§3.2): **EindGebruiker (EU)** = de handelende natuurlijke persoon; **Vertegenwoordigde** (*Represented Party*) = de dienstafnemer namens wie de EU handelt; **Belanghebbende** (*Service Consumer*) = de EU zelf óf de vertegenwoordigde; **Wettelijke Vertegenwoordiging**; **BVD** (Bevoegdheidsverklaringsdienst).
- §6.2: de EU en de vertegenwoordigde zijn beide natuurlijke personen. Standaardvertegenwoordiging = DigiD Machtigen via BVD-DDM; de vertegenwoordigde legt de machtiging vooraf vast in het machtigingenregister (§6.2.1). Wettelijke vertegenwoordiging via andere BVD's, bijvoorbeeld BVD-OG voor ouderlijk gezag (§6.2.2).
- Attributen (§7.1.2.4.x): `urn:nl-eid-gdi:1.0:ActingSubjectID` (de EU, versleuteld, altijd aanwezig); `urn:nl-eid-gdi:1.0:LegalSubjectID` (de vertegenwoordigde, versleuteld, alleen bij vertegenwoordiging); `urn:nl-eid-gdi:1.1:RepresentationType` (soort vertegenwoordiging, bijvoorbeeld `urn:nl-eid-gdi:1.1:RT:Zorg_Volledig_Gezag_Kind`; optioneel bij DigiD Machtigen, verplicht bij wettelijke vertegenwoordiging); `urn:nl-eid-gdi:1.0:ServiceUUID`. Na ontsleuteling geeft de `NameQualifier` het type, bijvoorbeeld `urn:nl-eid-gdi:1.0:id:legacy-BSN`.
- *Gemachtigde* en *vertegenwoordigde* zijn de termen in de Logius-dienstomschrijving van DigiD Machtigen ("de dienst waarbij Logius een Vertegenwoordigde en een Gemachtigde in staat stelt om een Machtiging te laten [registreren]") *(alleen via zoekfragment van logius.nl gezien)*. *Machtiginggever* komt in de gelezen Logius-bronnen niet voor *(niet geverifieerd)*.
- **Bewindvoering, curatele, mentorschap:** Stelsel Toegang SAML 1.0.0 noemt ze niet. Volgens een Kamerstuk ontsluit de bevoegdheidsverklaringsdienst bewind en curatele vanaf eind 2023 en mentorschap vanaf begin 2024 ([zoek.officielebekendmakingen.nl](https://zoek.officielebekendmakingen.nl/ah-tk-20232024-878.pdf)) *(niet zelf gelezen; de `RepresentationType`-waarden voor bewind zijn niet geverifieerd)*.

### 6.3 Begrippentabel

| Concept | DigiD (Machtigen) | eHerkenning (eToegang) | SAML-attribuut | OIDC (OpenID.NLGov) |
|---|---|---|---|---|
| De natuurlijke persoon die inlogt en handelt | EindGebruiker; *gemachtigde* bij vertegenwoordiging | Gebruiker (*gemachtigde* bij vertegenwoordiging) | DigiD: `urn:nl-eid-gdi:1.0:ActingSubjectID`; eH: `urn:etoegang:core:ActingSubjectID` | `sub` |
| De partij namens wie gehandeld wordt | Vertegenwoordigde (*Represented Party*) | Dienstafnemer, of vertegenwoordigde dienstafnemer (organisatie of persoon) | DigiD: `urn:nl-eid-gdi:1.0:LegalSubjectID`; eH: EntityConcernedID (KvKnr, RSIN, BSN, Pseudo) | `represents` (met `sub`, `iss`) |
| De partij voor wie de dienst is (zelf of vertegenwoordigd) | Belanghebbende (*Service Consumer*) | Dienstafnemer | (afgeleid) | — |
| Is er vertegenwoordiging, en welke | RepresentationType | Representation (boolean) | `urn:nl-eid-gdi:1.1:RepresentationType`; `urn:etoegang:core:Representation` | — |
| Vastgelegde toestemming | Machtiging (in het machtigingenregister, BVD-DDM) | Machtiging, bevoegdheid (machtigingenregister) | — | — |
| Tussenpartij | — | Intermediaire partij (ketenmachtiging) | `IntermediateEntityID` | — |
| Vertegenwoordiging van rechtswege | Wettelijke vertegenwoordiging (gezag; bewind/curatele/mentorschap *niet geverifieerd*) | Wettelijke vertegenwoordiging | `RepresentationType` | — |
| De dienst | Dienst (ServiceUUID) | Dienst (ServiceID/ServiceUUID) | `urn:nl-eid-gdi:1.0:ServiceUUID`; `urn:etoegang:core:ServiceUUID` | — |

**Conclusie voor NL Portal:** het GLOSSARY noemt alleen *Machtiging*. De claims `aanvrager` en `gemachtigde` volgen geen van beide stelsels. De afsprakenstelsels gebruiken *gebruiker/EindGebruiker* (handelt) tegenover *dienstafnemer/vertegenwoordigde* (namens wie). In OIDC heet dat `sub` tegenover `represents`. "Aanvrager" is in deze stelsels geen term. Dit is invoer voor `domain-modeling`.

## 7. Aanbevelingen voor NL Portal

Opties per vraag, zonder keuze.

### 7.1 Architectuur (vraag 1)

| Optie | Voordelen | Nadelen |
|---|---|---|
| **A. Huidige public SPA-client houden**, als afwijking vastleggen | Geen migratie | Voldoet niet aan ASVS 10.1.1 (L2) en 10.4.16 (L3); RFC 10017 raadt het af voor persoonsgegevens; legt-uit nodig bij OpenID.NLGov §4.1.2 |
| **B. BFF in de bestaande WebFlux-app** (`oauth2Login`, Spring Session Redis, sessie-authenticatie op `/graphql`) | Eén component; geen tokens in de browser; `private_key_jwt` mogelijk; token exchange kan weg | Redis verplicht (ADR 0017); SPA-auth en CSRF moeten om; meerdere tenants in één `ReactiveClientRegistrationRepository`; ontwerp nodig voor de `SameSite`-keuze bij de callback |
| **C. Aparte BFF-gateway** (Spring Cloud Gateway + `TokenRelay`) voor de ongewijzigde resource server | Backend blijft resource server; scheiding van verantwoordelijkheden | Extra component per cel; tokens blijven over het interne netwerk lopen; tokenvalidatie (§4) blijft in de backend nodig |
| **D. Token-mediating backend** | Kleinere SPA-wijziging | Access-token blijft in de browser; RFC 10017 §6.2.4.4 adviseert eerst B/C te overwegen; lost 10.1.1 niet op |

Bij B of C: kies tussen token exchange weglaten (minder bewegende delen, maar de BSN in elk sessietoken) of behouden voor downscoping per downstream-`aud`.

### 7.2 NL GOV-profielen (vraag 2)

- **`private_key_jwt` voor elke confidential client** (`nl-portal-m2m` nu, de BFF-client later). Beschikbaar in Keycloak 26.3 en Spring Security 7.1.2. Kost sleutelbeheer en rotatie per tenant-realm.
- **mTLS (`tls_client_auth`) met PKIoverheid/OIN** als alternatief. Past bij §2.3.4 tussen organisaties, maar is zwaarder in de hosting, en Keycloak configureert het serverbreed (`client-keystore`, zie [multitenancy-keycloak-realms.md](./multitenancy-keycloak-realms.md)).
- **Afwijking vastleggen** voor `client_secret` als tussenstap, met einddatum.
- **`represents` versus eigen claims:** de claims omzetten naar `represents`, of de afwijking van OpenID.NLGov §5.2.4 documenteren.

### 7.3 Tokentype (vraag 3)

- **`at+jwt` aanzetten + `createAtJwtValidator()`**: standaardconform, kleine wijziging; `client_id`-validator aanpassen als Keycloak die claim niet levert.
- **Alleen een unieke `aud`** (`spring.security.oauth2.resourceserver.jwt.audiences`): al gedocumenteerd, sluit ID-tokens uit zolang geen API-audience gelijk is aan een `client_id`; leunt op configuratiediscipline per realm.
- **Payload-`typ` = `Bearer` controleren**: snel, maar Keycloak-specifiek.
- **Wachten op de BFF**: verkleint het probleem, maar laat het open voor resource servers achter een gateway.

### 7.4 DPoP en sender-constraining (vraag 4)

- **BFF zonder DPoP, met `private_key_jwt`**: dekt 10.1.1 en 10.4.16; 10.3.5 en 10.4.14 blijven open of als afwijking.
- **DPoP in de SPA** (oidc-client-ts 3.5.0 kan het): vraagt Keycloak ≥26.4 (in 26.3 preview) en een DPoP-resource server, die reactive Spring 7.1.2 niet heeft. Lost "nieuwe flow" niet op (RFC 10017 §6.3.4.2.2).
- **DPoP voor de BFF-client richting Keycloak**: Keycloak ≥26.4 en zelfgebouwde proofs in de `WebClient`; alleen zinvol als er een resource server is die DPoP valideert.
- **mTLS-gebonden tokens**: werkt zonder Spring-DPoP, maar vraagt certificaatbeheer en TLS-terminatie tot in de pod.

### 7.5 Domeintaal (vraag 5)

- **De taal van de stelsels overnemen** in GLOSSARY en claims: *handelende persoon/gebruiker*, *vertegenwoordigde/dienstafnemer*, *gemachtigde*, *machtiging*, *bevoegdheid*, *wettelijke vertegenwoordiging*, *ketenmachtiging*.
- **"Aanvrager" behouden als NL Portal-term**, met een expliciete mapping naar `ActingSubjectID`/`LegalSubjectID`/EntityConcernedID en `represents`.
- Laat dit via `domain-modeling` lopen; het raakt GLOSSARY.md en mogelijk een ADR over het claimmodel.

## 8. Niet geverifieerd

- De Keycloak-versie in **productie**; alleen de demo-image (26.3) staat in de repo. Alle Keycloak-uitspraken gelden voor 26.3 tenzij anders vermeld.
- De Keycloak-token-exchange-docs zijn de actuele versie, niet die van 26.3. De regel over sender-constrained subject tokens kan nieuwer zijn.
- Of `private_key_jwt` in 26.3 aantoonbaar werkt voor een token-exchange-verzoek (afgeleid, niet getest).
- Of Keycloak-access-tokens voor gebruikers de claim `client_id` bevatten (RFC 9068 §2.2).
- Of Spring Security 7.1.2 de RFC 9207-`iss`-parameter in de autorisatieresponse controleert, en hoe de `{baseUrl}`-placeholder in 7.1 werkt.
- Het gedrag van `SameSite=Strict` op de sessiecookie tijdens de OIDC-callback in Spring WebFlux.
- Of puur machine-to-machine onder het Forum-toepassingsgebied van het OAuth-profiel valt; en de tegenstrijdige datum op de Forum-pagina.
- DigiD-termen *machtiginggever* en de `RepresentationType`-waarden voor bewind, curatele en mentorschap; de Kamerstukvermelding is niet zelf gelezen.
- RFC's, ASVS en de Logius-profielen zijn gelezen via een samenvattende fetch-tool; sectienummers komen daaruit. ASVS V7 "federatieve logout" is zo samengevat en niet letterlijk gecontroleerd.
