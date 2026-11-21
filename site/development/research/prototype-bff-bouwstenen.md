# Prototype: reactieve BFF-bouwstenen in Spring Security 7.1

Uitgevoerd op: 2026-11-21 (ticket #502, epic #499). Kader: [ADR 0021](../adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md),
[ADR 0022](../adr/0022-backend-for-frontend-als-authenticatiepatroon.md), de [onderzoeksnotitie over BFF en NL GOV](./auth-bff-nlgov-oauth.md)
en het [besluitenlog fase 0](./fase0-besluiten-multitenancy-bff.md).

De code staat op de branch `prototype/bff-bouwstenen-502` (map `prototype/bff-bouwstenen/`, laatste commit `174f5b471`). Die branch wordt niet
gemerged. Het is een losse Gradle-app met dezelfde versies als de backend: Spring Boot 4.1.2, Spring Security 7.1.2, Spring Session 4.1.1,
Kotlin 2.4.21, Java 25.

## Samenvatting

- **Alle onderdelen uit het ticket zijn aangetoond met een test via HTTP tegen de draaiende app**, met `mock-oauth2-server` 7.0.1 (twee issuers, een per
  tenant) en een echte Redis 7.4 via Testcontainers. Er zijn 19 tests, allemaal groen.
- **Spring Security 7.1.2 dekt het grootste deel, maar vier dingen moesten we zelf bouwen**: de RFC 9207-issuercontrole (Spring kent de `iss`-parameter
  niet), replaybescherming op `jti` van het logout-token, het beëindigen van de sessie bij back-channel logout (de standaardhandler werkt niet met een eigen
  cookienaam en host-tenant) en een sessie-ID-generator van 256 bit (Spring Session gebruikt een UUID).
- **Twee punten zijn niet in een echte browser gemeten**: het gedrag van het `Strict`-sessiecookie na de cross-site callback (opgelost met een tussenpagina,
  zie onder) en de browserondersteuning van het `__Host-Http-`-voorvoegsel. Beide staan onder "Open punten".
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
| `private_key_jwt` met een sleutel per registratie | Aangetoond | `NimbusJwtClientAuthenticationParametersConverter` met een sleutel-resolver per registratie, ingehaakt in `WebClientReactiveAuthorizationCodeTokenResponseClient`. De test controleert op het token-endpoint van elke tenant: handtekening met de publieke sleutel van die tenant, `kid`, `alg` ES256, `iss` en `sub` gelijk aan de client-id, `aud` gelijk aan het token-endpoint, `jti` en `exp` aanwezig, geen `client_secret`, geen `Authorization`-header |
| Eigen `ServerAuthorizationRequestRepository`, pre-auth-sessie in Redis | Aangetoond | `RedisPreAuthRepository`: state, PKCE-verifier, nonce en return-pad staan als JSON in Redis onder een willekeurige sleutel van 256 bit met TTL; opgehaald met `GETDEL`, dus eenmalig. Het request wordt bij het ophalen gebonden aan de host-tenant |
| `__Host-`-flowcookie (`Lax`) | Aangetoond | `__Host-nlp-flow`: `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Lax`, `Max-Age` 300 s. Na de callback verlopen (`Max-Age=0`), ook bij een weigering. Het cookie van tenant A op tenant B wordt geweigerd (en is daarna verbruikt), een replay faalt, geen cookie of een andere `state` faalt (`FlowSecurityTest`) |
| `__Host-Http-`-sessiecookie (`Strict`) | Aangetoond op HTTP-niveau; browsergedrag zie "Open punten" | `__Host-Http-NLP`: `HttpOnly`, `Secure`, `Path=/`, geen `Domain`, `SameSite=Strict`. Bij het uitloggen verloopt het met dezelfde attributen |
| Werkende eerste paginalading na de callback | Aangetoond met een alternatief voor de redirect | Een redirect vanaf de callback naar de eerste pagina behoort tot een cross-site navigatieketen, waarop een `Strict`-cookie niet meegaat. Het prototype beantwoordt de callback daarom met een 200-pagina met `<meta http-equiv="refresh">` naar het return-pad (`Cache-Control: no-store`, `Referrer-Policy: no-referrer`); de volgende navigatie start dan vanaf een eigen pagina. De test laadt daarna de eerste pagina met het sessiecookie |
| Geen vrije return-URL (open redirect) | Aangetoond | Alleen een relatief pad dat met één `/` begint en uit een vaste tekenset bestaat, gaat in de pre-auth-sessie; `https://…`, `//host`, `/\host` en `javascript:` vallen terug op `/` |
| RFC 9207-issuercontrole | Aangetoond, eigen bouwsteen | Spring Security 7.1.2 kent de `iss`-parameter niet (geen verwijzing in `oauth2-client` en `oauth2-core`). Een filter vergelijkt `iss` exact met de issuer van de registratie van de host-tenant; een afwijkende, ontbrekende of bijna-gelijke (`…/`) issuer geeft 400, ruimt de pre-auth-sessie op en laat het flowcookie verlopen. Een ontbrekende `iss` weigeren we (fail-closed) omdat de broker hem ondersteunt; dat moet bij het Keycloak-prototype bevestigd worden |
| Back-channel logout met OIDC-sessieregister in Redis | Aangetoond, met eigen register en eigen handler | `RedisOidcSessionRegistry` implementeert `ReactiveOidcSessionRegistry` met de issuer in de sleutel, per `sid` en per `sub`. Een logout-token met `sid` beëindigt precies die sessie; zonder `sid` alle sessies van die `sub` bij die issuer; dezelfde `sid` bij een andere realm blijft staan (`LogoutTest`) |
| Logout-URI via de host-tenant | Aangetoond | `/logout/connect/back-channel/{registrationId}`: op de host van een andere tenant of een onbekende host geeft het 404. Een logout-token van een andere issuer, met een verkeerde `aud`, zonder `events`, met `nonce`, geen JWT of ongetekend: 400 en de sessie blijft staan |
| `jti`-replaybescherming van het logout-token | Aangetoond, eigen bouwsteen | Spring doet dit niet. Eigen handler: `SET NX` met TTL op `issuer\|jti`; een tweede aanbieding van hetzelfde token geeft 400 |
| Uitloggen: alleen `POST`, RP-initiated logout | Aangetoond | `GET /logout` beëindigt niets; `POST /logout` geeft een redirect naar het `end_session_endpoint` met `id_token_hint` en `post_logout_redirect_uri`, laat het `__Host-Http-`-cookie verlopen en leegt sessiestore en register |
| Sessie-ID-generator van minstens 128 bit | Aangetoond | `SecureSessionIdGenerator`: 32 bytes uit `SecureRandom`, base64url (43 tekens, 256 bit), via een `ReactiveSessionRepositoryCustomizer`. Spring Session geeft standaard een UUID. De test eist 43 tekens, geen UUID-vorm en 20 verschillende waarden |
| Nieuwe sessie-ID bij authenticatie | Aangetoond | Een door de aanvaller gekozen sessie-ID in het cookie wordt niet overgenomen en is daarna waardeloos. Voor het inloggen bestaat er geen sessie: login-start zet alleen het flowcookie |
| Absolute timeout met injecteerbare `Clock` | Aangetoond | Een `SessionGuardFilter` vergelijkt het aanmaakmoment in de sessie met een `Clock`-bean: 7 uur 59 min 59 s werkt, 8 uur 0 min 1 s niet (401, cookie verlopen, sessie uit Redis). De test vervangt de klok door een bestuurbare klok |
| Sessiebinding aan de tenant | Aangetoond | Een sessiecookie van tenant A op de host van tenant B geeft 401 en maakt de sessie ongeldig |
| Geen access- of refreshtoken in de sessie | Aangetoond | Een eigen `ServerOAuth2AuthorizedClientRepository` bewaart niets; in Redis staat precies één JWT (het ID-token) |

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
   bij "alleen afgeleide identiteit": die versmalling is werk voor het sessieticket.
9. In Kotlin met de JSpecify-annotaties van Spring 7 moet de converter van `private_key_jwt` in een eigen `Converter` gewikkeld worden
   (`NimbusJwtClientAuthenticationParametersConverter` is `@Nullable` in zijn uitkomst); het is een compilatiekwestie, geen gedragsverschil.

## Open punten en aanbevelingen voor de vervolgtickets

- **Strict-cookie in een echte browser.** Het prototype meet niet of de browser het `__Host-Http-`-cookie meestuurt op de navigatie na de tussenpagina. De
  redenering volgt RFC 6265bis (een navigatie die een same-site pagina start, is same-site; een redirect in een cross-site keten niet). Aanbeveling:
  een browsertest in het bouwticket van de sessie, op twee echte sites (app en broker op verschillende registreerbare domeinen), in Chrome, Firefox en
  Safari. Valt de tussenpagina negatief uit, dan is het alternatief `SameSite=Lax` voor het sessiecookie, wat ADR 0022 en ASVS 3.3.2 expliciet moeten
  vastleggen.
- **Voorvoegsel `__Host-Http-`.** Niet gemeten welke browserversies het kennen. Een browser die het voorvoegsel niet kent, behandelt de naam gewoon als
  een cookienaam; `Secure`, `Path=/` en het ontbreken van `Domain` blijven dan van ons. Controleer bij de browsertest.
- **Algoritme en `aud` van de client-assertion.** Het prototype gebruikt ES256 met een `kid` en `aud` gelijk aan het token-endpoint (de Spring-standaard).
  Of Keycloak 26.8 dat zo accepteert, en of de issuer als `aud` de voorkeur heeft, hoort bij #501.
- **`iss` in de authorization response van Keycloak.** Het prototype eist hem; bevestig bij #501 dat Keycloak hem altijd meestuurt (ook bij een fout).
- **Meerdere replica's.** Sessies, flow en register staan in Redis en kennen geen lokale state, maar het prototype draait één instantie; een test met twee
  instanties staat niet in het prototype.
- **Inactiviteit** (15 minuten) rust op de Redis-TTL van Spring Session en is niet getest met de klok; de absolute timeout is dat wel. Een bestuurbare
  inactiviteitstimeout vraagt een eigen controle zoals die voor de absolute timeout.
- **CSRF** staat in het prototype uit (eigen ticket); wel is uitloggen alleen via `POST`.
- **Securityevents** (weigering, mismatch) zijn in het prototype logregels of statuscodes; het ticket voor het eventmodel bepaalt de vorm.

## Wat de bouwtickets uit dit prototype kunnen overnemen

Overnemen als ontwerp, niet als code: de volgorde van de filters (tenant, issuer, sessiebewaking, daarna Spring Security), de sleutelopbouw in Redis
(issuer in elke sleutel van het register), de eenmalige pre-auth-sessie met `GETDEL`, de eigen back-channel-handler, de tussenpagina na de callback en de
testopzet met een bestuurbare klok en een provider met meerdere issuers.
