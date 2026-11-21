# Prototype: reactieve BFF-bouwstenen in Spring Security 7.1

Uitgevoerd op: 2026-11-21 (ticket #502, epic #499). Kader: [ADR 0021](../adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md),
[ADR 0022](../adr/0022-backend-for-frontend-als-authenticatiepatroon.md), de [onderzoeksnotitie over BFF en NL GOV](./auth-bff-nlgov-oauth.md)
en het [besluitenlog fase 0](./fase0-besluiten-multitenancy-bff.md).

De code staat op de branch `prototype/bff-bouwstenen-502` (map `prototype/bff-bouwstenen/`, laatste commit `971aeadd2`; de commit is met de branchnaam te vinden, want de branch wordt
niet gemerged en een sha alleen is dan broos). Die branch wordt niet gemerged. Het is een losse Gradle-app met dezelfde versies als de backend: Spring Boot 4.1.2, Spring Security 7.1.2, Spring Session 4.1.1,
Kotlin 2.4.21, Java 25.

## Samenvatting

- **Alle onderdelen uit het ticket zijn aangetoond met een test via HTTP tegen de draaiende app, op één na: de werkende eerste paginalading na de callback met
  een `Strict`-sessiecookie.** Daarvoor is een alternatief vastgelegd (tussenpagina) dat niet in een browser is gemeten. De tests gebruiken
  `mock-oauth2-server` 7.0.1 (twee issuers, een per tenant) en een echte Redis 7.4 via Testcontainers. Er zijn 28 tests, allemaal groen, opnieuw gedraaid met
  `cleanTest test --rerun-tasks`.
- **Spring Security 7.1.2 dekt het grootste deel, maar vijf dingen moesten we zelf bouwen**: de RFC 9207-issuercontrole (Spring kent de `iss`-parameter
  niet), replaybescherming op `jti` van het logout-token met een beleid voor `iat`, `exp` en `jti`, het beëindigen van de sessie bij back-channel logout (de standaardhandler werkt niet met een eigen
  cookienaam en host-tenant) en een sessie-ID-generator van 256 bit (Spring Session gebruikt een UUID). Een customizer voor de client-assertion (`aud`, `jti`, `typ`) is
  het vijfde.
- **Twee punten zijn niet in een echte browser gemeten**: het gedrag van het `Strict`-sessiecookie na de cross-site callback (alternatief: een tussenpagina,
  zie onder; de testclient negeert `SameSite`, dus de HTTP-test bewijst dit niet) en de browserondersteuning van het `__Host-Http-`-voorvoegsel. Beide staan onder "Open punten".
- **Story 54 uit de spec ("alleen de afgeleide identiteit in de sessie") is niet aangetoond**: de sessie bewaart het volledige `OidcUser` via Java-serialisatie, zie
  bevinding 8 en "Eisen voor het sessieticket".
- Er is niet getest tegen een echte Keycloak; dat is het werk van het Keycloak-prototype (#501) en van de integratietest in de bouwtickets.

## Opzet van het prototype

Een app met een host-tenant-filter, een eigen `ReactiveClientRegistrationRepository`, een eigen `ServerAuthorizationRequestRepository` op Redis, Spring Session
op Redis, `oauth2Login` en `oidcLogout` uit Spring Security, en een paar eigen filters. Per tenant is er één registratie met `private_key_jwt`; de private
sleutel komt uit een JWK-bestand (bestandsmount), het pad staat in de configuratie.

Tests op de hoogste seam: de volledige app start met twee tenants (`bosrand.test`, `wijdeveld.test`), een testclient speelt de browser na (eigen cookiejar,
geen redirects volgen, `Host`-header per tenant) en praat HTTP met de app. De test-OIDC-provider bedient twee issuers; per test is te bepalen wie er inlogt
(`sub`, `sid`), en de tokenverzoeken zijn zichtbaar, dus de client-assertion is te controleren. Logout-tokens worden door de provider getekend.

## Per onderdeel: aangetoond of alternatief

| Onderdeel | Uitkomst | Hoe aangetoond (test) |
|---|---|---|
| Client-registratie per host-tenant | Aangetoond | Een `ReactiveClientRegistrationRepository` met een registratie per tenant; een filter bepaalt de tenant uit `Host` (onbekend domein: 404 zonder inhoud) en geeft 404 bij een registratie-id van een andere tenant (`LoginHappyPathTest`, `FlowSecurityTest`) |
| `private_key_jwt` met een sleutel per registratie | Aangetoond (mechanisme en algoritme PS256) | `NimbusJwtClientAuthenticationParametersConverter` met een sleutel-resolver per registratie, ingehaakt in `WebClientReactiveAuthorizationCodeTokenResponseClient`, met een customizer. Sleutel: RSA 3072 met `alg: PS256` en `kid`. De test controleert op het token-endpoint van elke tenant: handtekening met de publieke sleutel van die tenant, `kid`, `alg` PS256, `typ` `client-authentication+jwt`, `iss` en `sub` gelijk aan de client-id, `aud` de issuer van de registratie als enige waarde (RFC 10070 par. 4), `jti` van minstens 256 bit uit `SecureRandom`, `exp` aanwezig, geen `client_secret`, geen `Authorization`-header |
| Eigen `ServerAuthorizationRequestRepository`, pre-auth-sessie in Redis | Aangetoond | `RedisPreAuthRepository`: state, PKCE-verifier, nonce en return-pad staan als JSON in Redis onder een willekeurige sleutel van 256 bit met TTL; opgehaald met `GETDEL`, dus eenmalig. Het request wordt bij het ophalen gebonden aan de host-tenant |
| `__Host-`-flowcookie (`Lax`) | Aangetoond | `__Host-nlp-flow`: `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Lax`, `Max-Age` 300 s. Na de callback verlopen (`Max-Age=0`), ook bij een weigering. Het cookie van tenant A op tenant B wordt geweigerd (en is daarna verbruikt), een replay faalt, geen cookie of een andere `state` faalt (`FlowSecurityTest`) |
| `__Host-Http-`-sessiecookie (`Strict`) | Niet aangetoond; alleen de cookie-attributen op HTTP-niveau | `__Host-Http-NLP`: `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Strict`. Bij het uitloggen verloopt het met dezelfde attributen. Of een browser het cookie meestuurt, staat onder "Open punten" |
| Werkende eerste paginalading na de callback | Alternatief vastgelegd (tussenpagina), niet in een browser gemeten | Een redirect vanaf de callback naar de eerste pagina behoort volgens RFC 6265bis tot een cross-site navigatieketen, waarop een `Strict`-cookie niet meegaat (redenering, niet gemeten). Het prototype beantwoordt de callback daarom met een 200-pagina met `<meta http-equiv="refresh">` en een gewone link naar het return-pad (`Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, het pad HTML-geëscaped). De HTTP-test toont alleen de 200-pagina en dat de volgende lading met het cookie werkt; de cookiejar van de testclient kent `SameSite` niet |
| Geen vrije return-URL (open redirect) | Aangetoond | Alleen een relatief pad dat met één `/` begint en uit een vaste tekenset bestaat, gaat in de pre-auth-sessie; `https://…`, `//host`, `/\host` en `javascript:` vallen terug op `/` |
| RFC 9207-issuercontrole | Aangetoond, eigen bouwsteen | Spring Security 7.1.2 kent de `iss`-parameter niet (geen verwijzing in `oauth2-client` en `oauth2-core`). Een filter vergelijkt `iss` exact met de issuer van de registratie van de host-tenant; een afwijkende, ontbrekende of bijna-gelijke (`…/`) issuer geeft 400, ruimt de pre-auth-sessie op en laat het flowcookie verlopen. Een ontbrekende `iss` weigeren we (fail-closed) omdat de broker hem ondersteunt; dat moet bij het Keycloak-prototype bevestigd worden |
| Back-channel logout met OIDC-sessieregister in Redis | Aangetoond, met eigen register en eigen handler | `RedisOidcSessionRegistry` implementeert `ReactiveOidcSessionRegistry` met de issuer in de sleutel, per `sid` en per `sub`. Een logout-token met `sid` beëindigt precies die sessie; zonder `sid` alle sessies van die `sub` bij die issuer; dezelfde `sid` bij een andere realm blijft staan (`LogoutTest`) |
| Logout-URI via de host-tenant | Aangetoond | `/logout/connect/back-channel/{registrationId}`: op de host van een andere tenant of een onbekende host geeft het 404. Een logout-token van een andere issuer, met een verkeerde `aud`, zonder `events`, met `nonce`, geen JWT of ongetekend: 400 en de sessie blijft staan |
| `jti`-replaybescherming van het logout-token | Aangetoond, eigen bouwsteen | Spring doet dit niet. Eigen handler: `SET NX` op `issuer\|jti` met een TTL van `exp - nu + 60 s klokmarge`. `iat`, `exp` en `jti` zijn verplicht (Spring controleert `exp` alleen als die er is), `iat` mag niet ouder zijn dan 2 minuten (plus marge) en `exp - iat` hoogstens 10 minuten; zonder `sid` en zonder `sub` volgt een weigering. Een tweede aanbieding geeft 400. Faalt het beëindigen van de sessie, dan wordt de `jti`-sleutel vrijgegeven zodat de OP het opnieuw kan proberen (par. 2.8). Dit beleid (TTL gekoppeld aan `exp` plus een maximale ouderdom) is wat de bouwtickets moeten overnemen. Tests: `LogoutTokenPolicyTest` (het beleid zelf) en `LogoutTest` (te oud, te lang geldig, TTL van de sleutel, falende sessiestore, replay) |
| Uitloggen: alleen `POST`, RP-initiated logout | Aangetoond | `GET /logout` beëindigt niets; `POST /logout` geeft een redirect naar het `end_session_endpoint` met `id_token_hint` en `post_logout_redirect_uri`, laat het `__Host-Http-`-cookie verlopen en leegt sessiestore en register |
| Sessie-ID-generator van minstens 128 bit | Aangetoond | `SecureSessionIdGenerator`: 32 bytes uit `SecureRandom`, base64url (43 tekens, 256 bit), via een `ReactiveSessionRepositoryCustomizer`. Spring Session geeft standaard een UUID. De 256 bit volgt uit de code; de test controleert de vorm (43 tekens, geen UUID-vorm) en de uniciteit (20 verschillende waarden), niet de entropie |
| Nieuwe sessie-ID bij authenticatie | Aangetoond | Een door de aanvaller gekozen sessie-ID in het cookie wordt niet overgenomen en is daarna waardeloos. Opnieuw inloggen met een bestaande geldige sessie geeft een nieuw ID en maakt het oude waardeloos. Voor het eerste inloggen bestaat er geen sessie: login-start zet alleen het flowcookie |
| Absolute timeout met injecteerbare `Clock` | Aangetoond | Een `SessionGuardFilter` vergelijkt het aanmaakmoment in de sessie met een `Clock`-bean: 7 uur 59 min 59 s werkt, 8 uur 0 min 1 s niet (401, cookie verlopen, sessie uit Redis). De test vervangt de klok door een bestuurbare klok |
| Sessiebinding aan de tenant | Aangetoond | Een sessiecookie van tenant A op de host van tenant B geeft 401 en maakt de sessie ongeldig |
| Geen access- of refreshtoken in de sessie | Aangetoond voor het access-token; het ontbreken van een refreshtoken volgt uit het ontwerp | Een eigen `ServerOAuth2AuthorizedClientRepository` bewaart niets (dat er geen refreshtoken is, volgt uit dit ontwerp en niet uit de test). In heel Redis staat precies een JWT: het ID-token in de sessiehash. Het OIDC-register bewaart geen token meer, alleen `sessionId`, `iss`, `sub` en `sid`; het ID-token staat dus niet dubbel in Redis |
| Alleen de afgeleide identiteit in de sessie (spec story 54) | **Niet aangetoond** | De sessie bewaart het volledige `OidcUser` met alle ID-tokenclaims via Java-serialisatie; zie bevinding 8 en "Eisen voor het sessieticket" |
| Tenant in de Redis-sleutel (ADR 0022 besluit 3) | Niet aangetoond; alternatief | De sleutel is `spring:session:sessions:<id>`; de tenant is een gecontroleerd sessieattribuut (`SessionGuardFilter` bij elk request). Dat is verdedigbaar met een ID van 256 bit. Een tenant in de namespace past slecht bij een enkele `ReactiveRedisSessionRepository` (de namespace is per request). Kies in het sessieticket: de ADR-tekst aanpassen naar "tenant als gecontroleerd attribuut" (via `domain-modeling`) of de sleutel aanpassen |

## Bevindingen over Spring Security 7.1.2 en Spring Session 4.1.1

1. **Geen `iss`-controle (RFC 9207).** Zelf bouwen, zie boven.
2. **Back-channel logout: geen `jti`-replaybescherming.** De validator controleert handtekening, `iss`, `aud`, `events`, `nonce` en `sub`/`sid`, maar niet of
   het token al gebruikt is.
3. **De standaard back-channel-handler doet een HTTP-aanroep naar de eigen `/logout`** met het sessiecookie. Dat werkt niet met een andere cookienaam
   (`__Host-Http-NLP`) en niet als de host-tenant intern niet resolvet. Onze handler verwijdert de sessie rechtstreeks uit de sessiestore. De handler en de
   decoder-factory van `oidcLogout` zijn niet te configureren; alleen de handler is te vervangen.
4. **Een logout-token zonder `events`-claim geeft een 500**, geen 400: de `IllegalArgumentException` uit de tokenbouwer komt als
   `AuthenticationServiceException` niet door de `onErrorResume` van de filter. Het prototype zet dat voor het back-channel-pad om met een
   `WebExceptionHandler`.
5. **Het sessie-ID is een UUID** (122 bit). Een `SessionIdGenerator` is te zetten via een `ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository>`.
6. **`spring.session.redis.namespace` werkt niet voor de reactieve Redis-sessie**: de sleutels blijven onder `spring:session:sessions:`. Een eigen namespace
   vraagt `setRedisKeyNamespace` in de customizer.
7. **Een logoutketen met `SecurityContextServerLogoutHandler` en `WebSessionServerLogoutHandler` naast een register-handler gaf `Session was invalidated`
   (500)** uit `ReactiveRedisSessionRepository.save`. De oorzaak is niet uitgezocht (vermoedelijk een schrijfactie op de sessie na het ongeldig maken). Eén
   sequentiële logouthandler (register leeg, dan `invalidate()`) werkt en is wat het prototype gebruikt.
8. **De SecurityContext in de sessie bevat het volledige `OidcUser` met ID-token** (Java-serialisatie). Dat past bij "ID-token voor `id_token_hint`", maar niet
   bij "alleen afgeleide identiteit" (story 54): dat is dus niet aangetoond. Eisen: zie "Eisen voor het sessieticket".
9. In Kotlin met de JSpecify-annotaties van Spring 7 moet de converter van `private_key_jwt` in een eigen `Converter` gewikkeld worden
   (`NimbusJwtClientAuthenticationParametersConverter` is `@Nullable` in zijn uitkomst); het is een compilatiekwestie, geen gedragsverschil.

## Open punten en aanbevelingen voor de vervolgtickets

- **Strict-cookie in een echte browser (niet aangetoond).** Het prototype meet niet of de browser het `__Host-Http-`-cookie meestuurt op de navigatie na de tussenpagina. De
  redenering volgt RFC 6265bis (een navigatie die een same-site pagina start, is same-site; een redirect in een cross-site keten niet). Aanbeveling:
  een browsertest in het bouwticket van de sessie, op twee echte sites (app en broker op verschillende registreerbare domeinen), in Chrome, Firefox en
  Safari. Valt de tussenpagina negatief uit, dan is het alternatief `SameSite=Lax` voor het sessiecookie, wat ADR 0022 en ASVS 3.3.2 expliciet moeten
  vastleggen, met de CSRF-eisen (Origin-regel en token) als voorwaarde. De browsertest (Playwright met Chromium, Firefox en WebKit, app en broker op verschillende
  registreerbare domeinen) is een acceptatiecriterium van het bouwticket van de sessie.
- **Aanname over de eerste paginalading.** In het prototype is `/` een geauthenticeerde pagina; alleen daardoor speelt het probleem. Twee varianten om te vergelijken:
  (a) een 302 naar een publieke SPA-shell, waarna same-origin `fetch`-aanroepen naar `/api` en `/graphql` het `Strict`-cookie gewoon meesturen (same-site), zodat de
  tussenpagina niet nodig is; (b) een tussenpagina voor een server-side geauthenticeerde eerste pagina. In het doelontwerp (statische SPA-shell) lijkt (a) te gelden;
  het bouwticket stelt dat vast met de browsertest.
- **Voorvoegsel `__Host-Http-`.** Niet gemeten welke browserversies het kennen. Een browser die het voorvoegsel niet kent, behandelt de naam gewoon als
  een cookienaam; `Secure`, `Path=/` en het ontbreken van `Domain` blijven dan van ons. Controleer bij de browsertest.
- **Algoritme van de client-assertion: PS256.** OpenID NLGov 1.0.1 par. 7.2.1 beveelt PS256 aan boven RS256; ES256 mag alleen als het minstens even sterk is. Het
  realm-template van T2 pint PS256 per client (Keycloak weigert een ander algoritme), en PKIoverheid-certificaten (NL GOV OAuth par. 2.3.4, OIN in `x5c`) zijn RSA.
  Dus PS256 met RSA 3072 (ASVS 11.2.3), zoals het prototype nu doet. RS256 alleen als gedocumenteerde uitzondering. De JWK moet `alg: PS256` dragen: zonder `alg` valt
  Spring's converter stil terug op RS256; de controle op `kid` en `alg` in `Tenant.init` moet blijven. De eerdere ES256-variant bewees alleen het mechanisme (een
  sleutel per registratie), niet de algoritmekeuze.
- **`aud`, `jti` en `typ` van de client-assertion.** De bouw eist de customizer uit `SecurityConfig` (`aud` is de issuer van de registratie als enige waarde, `jti` 32
  bytes uit `SecureRandom`, `typ` `client-authentication+jwt`); de Spring-standaard (`aud` is het token-endpoint, `jti` een UUID van 122 bit) volstaat niet. Of
  Keycloak 26.8 `typ` en `aud` zo accepteert, bevestigt #501.
- **`iss` in de authorization response van Keycloak.** Het prototype eist hem; bevestig bij #501 dat Keycloak hem altijd meestuurt (ook bij een fout). Voor de bouw: hang
  de controle in de `ServerAuthenticationConverter` van `oauth2Login` (zelfde matcher als Spring; `startsWith` op het pad faalt open als ze uit elkaar lopen),
  vergelijk met de issuer van de registratie uit het opgehaalde authorization request in plaats van met de tenant, en koppel "fail-closed bij ontbrekende `iss`" aan
  `authorization_response_iss_parameter_supported` of aan een vlag in het tenantbestand. Een weigering is een securityevent.
- **Meerdere replica's.** Sessies, flow en register staan in Redis en kennen geen lokale state, maar het prototype draait één instantie; een test met twee
  instanties staat niet in het prototype.
- **Inactiviteit** (15 minuten) rust op de Redis-TTL van Spring Session en (`spring.session.timeout`) is niet getest met de klok; de absolute timeout is dat wel. Een bestuurbare
  inactiviteitstimeout vraagt een eigen controle zoals die voor de absolute timeout. De SSO-tijden van Keycloak (Idle en Max) moeten met een test op de portaaltijden
  worden afgestemd (#501 of het realmticket): is de Keycloak-sessie langer, dan logt `/oauth2/authorization/...` na een verlopen portaalsessie stil opnieuw in.
- **CSRF** staat in het prototype uit (eigen ticket); wel is uitloggen alleen via `POST`. `SameSite` werkt per site, niet per origin: draaien twee tenants onder hetzelfde
  registreerbare domein, dan stuurt de browser het `Strict`-cookie van tenant A mee op een request vanaf tenant B. De Origin-regel (exact same-origin) en het CSRF-token zijn de
  enige bescherming tussen tenants; overweeg in het tenantschema een regel tegen tenantdomeinen onder een gedeeld domein van een andere partij.
- **Tenant en `{baseUrl}`.** Het prototype leest de tenant uit `Host`; Spring bouwt `{baseUrl}` uit de request-URI. Achter de ingress met forwarded-headers kunnen die
  verschillen. Leg vast dat beide dezelfde bron gebruiken (de host na vertrouwde forwarded-afhandeling) en test met een afwijkende `X-Forwarded-Host`.
- **Keycloak 26.8 en het logout-token.** Noteer welke `exp` en `typ` Keycloak op het logout-token zet; is `typ` `logout+jwt`, eis het dan (ASVS 10.5.5). Zet
  `backchannel_logout_session_required` in het realm-template; het prototype indexeert een token zonder `sid` onder `sid:<iss>|null`, wat de bouw niet moet doen.
- **`__Host-Http-` voor beide cookies.** Het flowcookie is `HttpOnly` maar heet `__Host-` (conform de spec); RFC 10017 par. 6.1.3.2 beveelt `__Host-Http-` aan voor `HttpOnly`-cookies.
- **Ongetest in het prototype:** een dubbele `iss`-parameter in de callback, een logout-token zonder `jti` (de tokenbouwer van Spring weigert dat al) en twee replica's
  tegelijk. Wel aangevuld in ronde 1: `alg: none`, een logout-token zonder `sid` en `sub`, een te oude `iat`, een te lang geldig token, de `Cache-Control`-header op de
  back-channel-respons en opnieuw inloggen met een bestaande sessie.
- **Securityevents** (weigering, mismatch) zijn in het prototype logregels of statuscodes; het ticket voor het eventmodel bepaalt de vorm.

## Eisen voor het sessieticket

- Geen Java-serialisatie van het volledige `OidcUser`: JSON met een type-allowlist (de Jackson-modules van Spring Security of een eigen klein identiteitsobject), met alleen de
  afgeleide identiteit en de ruwe ID-token-string (voor `id_token_hint`). ASVS 5.0 1.5.2 en V14.2.
- Het register bewaart alleen `sessionId`, `iss`, `sub` en `sid`, zoals het prototype nu doet.
- Laat de Keycloak-mappers BSN-claims niet in het ID-token zetten als de identiteit uit het access-token of de userinfo kan komen; dan bevat het bewaarde ID-token geen BSN.

## Wat de bouwtickets uit dit prototype kunnen overnemen

Overnemen als ontwerp, niet als code: de volgorde van de filters (tenant, issuer, sessiebewaking, daarna Spring Security), de sleutelopbouw in Redis
(issuer in elke sleutel van het register), de eenmalige pre-auth-sessie met `GETDEL`, de eigen back-channel-handler, de tussenpagina na de callback en de
testopzet met een bestuurbare klok en een provider met meerdere issuers, het beleid voor `iat`, `exp` en `jti` van het logout-token en de customizer voor de
client-assertion.

## Aandachtspunten bij overname

- Zet de constanten op een plek: de routeprefixen `/logout/connect/back-channel/` en `/login/oauth2/code/` staan in meerdere filters en in `SecurityConfig`; de
  `SecureRandom`-naar-base64url-generator staat drie keer (`RedisPreAuthRepository.newId`, `SecureSessionIdGenerator`, `AssertionIds`); `nlp.tenant` is zowel sessie- als
  exchange-attribuut met een andere betekenis; `@Order(-110)` op `SessionGuardFilter` heeft geen uitleg.
- Schrijf de failurehandler als securityevent zonder `code` of `state`. Het prototype logt alleen tenant en fouttype (de ruwe query bevatte de authorization `code`, `state`
  en een door de aanvaller bepaalde `error_description`).
- Tests: gebruik `@ParameterizedTest` voor de gevallen die het prototype in een lus afloopt (de eerste fout verbergt de rest). `ControlledCallback` en de mockserver zijn
  globaal en gedeeld; dat is alleen veilig zolang testklassen na elkaar draaien.

