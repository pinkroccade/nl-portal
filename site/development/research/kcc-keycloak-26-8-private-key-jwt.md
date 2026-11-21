# keycloak-config-cli op Keycloak 26.8 met private_key_jwt en sleutelrotatie

Geraadpleegd op: 2026-11-21. Prototype voor #501 (epic #499), uitgangspunt ADR 0021 besluit 6 en ADR 0022 besluit 9 en 13. Bouwt voort op [multitenancy-realm-as-code.md](./multitenancy-realm-as-code.md) en [auth-bff-nlgov-oauth.md](./auth-bff-nlgov-oauth.md).

Prototypecode (wegwerp, niet gemerged): branch [`prototype/kcc-private-key-jwt-501`](https://github.com/pinkroccade/nl-portal/tree/prototype/kcc-private-key-jwt-501), map `prototypes/kcc-private-key-jwt/`.

Bronnen voor de profielverwijzingen (de paragraafnummers volgen de bestaande notitie [auth-bff-nlgov-oauth.md](./auth-bff-nlgov-oauth.md)):

- **NL GOV OAuth** = NL GOV Assurance profile for OAuth 2.0 v1.1.0, [gitdocumentatie.logius.nl/publicatie/api/oauth](https://gitdocumentatie.logius.nl/publicatie/api/oauth/).
- **NL GOV OpenID** = OpenID NLGov 1.0.1, [gitdocumentatie.logius.nl/publicatie/api/oidc](https://gitdocumentatie.logius.nl/publicatie/api/oidc/).
- **RFC 10070** (november 2026, Updates to JWT Client Authentication; werkt RFC 7521, 7522 en 7523 bij), [rfc-editor.org/info/rfc10070](https://www.rfc-editor.org/info/rfc10070).
- kcc-releases: [github.com/adorsys/keycloak-config-cli/releases](https://github.com/adorsys/keycloak-config-cli/releases) en de tags op [Docker Hub](https://hub.docker.com/r/adorsys/keycloak-config-cli/tags). Dat 26.8 niet in de kcc-CI-matrix staat, komt uit het onderzoek bij ADR 0021 besluit 6 en is hier niet opnieuw nagelezen.

## Samenvatting

- **keycloak-config-cli (kcc) 6.5.1 werkt in de proef tegen Keycloak 26.8.2, maar ondersteunt hem niet formeel.** Image `adorsys/keycloak-config-cli:6.5.1-26.5.5` (de nieuwste tag; de tags eindigen op Keycloak 26.5.5) laadt het realm-template op 26.8.2. De test eist `Using version: 26.8.` in de kcc-uitvoer en geen enkele `WARN` of `ERROR`. 26.8 staat niet in de kcc-CI-matrix.
- **Wat dat betekent voor ADR 0022 besluit 13** ("keycloak-config-cli moet die versie ondersteunen"): de formele lezing (de kcc-CI test die Keycloak-versie) is **niet** gehaald, dus er is een gat. Voor dat gat volgen we de route van besluit 13: de upgradetest uit deze notitie is de poort, en 26.8.2 met kcc `6.5.1-26.5.5` is de gepinde combinatie voor dev. Slaagt de test niet bij een volgende bump, dan geldt de rest van besluit 13 (patch gaat voor de tool; realm-wijzigingen bevriezen of handmatig onder change-procedure, of de tool vervangen). **Open besluit voor de epic:** besluit 13 zegt niet wat "ondersteunen" is. Voorstel: "de upgradetest van deze proef is groen tegen die versie, aangevuld met de kcc-CI zodra die volgt". Dat is een wijziging van ADR 0022 en gaat via `domain-modeling`; deze notitie voert haar niet door.
- **`private_key_jwt` werkt voor de echte flow**: de client `nl-portal-backend` wisselt een autorisatiecode in (PKCE S256, testgebruiker, `code_verifier`) met een client-assertion. `client_credentials` is voor deze client **uit**, ook met een geldige assertion (`unauthorized_client`).
- **Keycloak 26.8.2 weigert** (alle getest): `client_secret`, een vreemde sleutel, een gemanipuleerde handtekening, `alg: none`, een `kid` van een andere sleutel dan waarmee getekend is, een verkeerde `aud`, een verlopen `exp`, een verkeerde `iss` of `sub`, een tweede gebruik van dezelfde assertion (replay), en een assertion waarvan het algoritme niet het geconfigureerde is (beide richtingen, PS256 en RS256).
- **Algoritme: PS256, RS256 alleen als gedocumenteerde uitzondering.** Zie "Algoritme, sleutel en `kid`".
- **`aud` = issuer.** Keycloak 26.8.2 accepteert zowel de issuer als het token-endpoint als `aud`; NL Portal kiest de issuer, in lijn met RFC 10070. Dat wijkt af van de letterlijke tekst van NL GOV OAuth §2.3.3. Zie "Keuze: `aud` = issuer".
- **PKCE S256, `at+jwt` en exacte redirect-URI's staan in het template en werken**: een autorisatieverzoek zonder `code_challenge`, met `plain`, of met een redirect-URI die alleen een prefix-match is, wordt geweigerd. Het access token heeft `typ: at+jwt`, een `kid` en `azp` = de client.

## Wat de proef doet

Seam: het autorisatie- en token-endpoint van een echte Keycloak 26.8.2 (container), ingericht door kcc (container). Geen mocks. Negen tests in `test/rotatie.test.mjs` (± 2 minuten); elke test importeert zelf wat hij nodig heeft en is dus los te draaien:

1. kcc laadt het template (versie en geen waarschuwingen), de code-flow met assertion slaagt; het access token is `at+jwt` met `kid`, `azp`, `jti`, juiste `iss`.
2. `aud` = issuer, `aud` = token-endpoint en `typ: client-authentication+jwt` worden alle drie geaccepteerd.
3. Weigeringen: `client_secret`, vreemde sleutel, gemanipuleerde handtekening, `alg: none`, `kid` van sleutel B bij een assertion getekend met A, verkeerde `aud`, verlopen `exp`, `exp` ver in de toekomst zonder `iat`, `iss` ≠ client, `sub` ≠ client. Dezelfde sleutel zonder fout slaagt in dezelfde test, zodat de weigeringen aan de assertion liggen.
4. Replay: dezelfde assertion twee keer, de tweede wordt geweigerd.
5. Alg-pinning: met `PS256` geconfigureerd wordt een `RS256`-assertion geweigerd, en omgekeerd.
6. Rotatie: [A, B] beide geldig; daarna [B] alleen B, A wordt geweigerd.
7. `client_credentials` wordt geweigerd met een geldige assertion.
8. Clientconfiguratie via de admin-API (`client-jwt`, niet-publiek, exacte redirect-URI's, S256, alg-pin, `token.endpoint.auth.signing.max.exp=60`, `at+jwt`, impliciete flow, ROPC en service-account uit) en het template (`sslRequired: all`, `kid` = JWK-thumbprint).
9. Autorisatieverzoeken (PKCE en redirect-URI).

Het template (`realm-template.json`) bevat per tenant alleen `realm`, `clientId`, `redirectUris`, `jwks` en het algoritme. In de proef rendert Node de waarden; in productie doet Helm dat en komen geheimen via kcc-substitutie (`$(file:...)`). De publieke sleutel is geen geheim en mag in het template (ADR 0022 besluit 9). De test voegt alleen toe wat de proef nodig heeft en wat niet in het template hoort: `sslRequired: none` (de container praat HTTP) en een testgebruiker (`metTestOverlay` in `lib.mjs`).

Gebruikte kcc-instellingen: `IMPORT_CACHE_ENABLED=false` (drift herstellen), `IMPORT_VAR_SUBSTITUTION_ENABLED=true` met `UNDEFINED_THROWS_EXCEPTIONS=true`.

## Algoritme, sleutel en `kid`

| Onderwerp | Keuze | Bron |
|---|---|---|
| Clientauthenticatie | `private_key_jwt` met **PS256**. De NL GOV-profielen eisen van de autorisatieserver dat die RS256 ondersteunt (MUST); PS256 is een aanbeveling (SHOULD) voor de autorisatieserver, geen eis aan de client. | NL GOV OpenID §7.2.1 ("PS256 is RECOMMENDED over RS256"; andere algoritmen mogen als ze minstens even sterk zijn); NL GOV OAuth §2.3.3 en §3.2.1 |
| Alternatief per tenant | **RS256 alleen als gedocumenteerde uitzondering** als de client van een tenant PS256 niet kan. Dat is geen terugval tijdens het draaien: Keycloak pint per client één algoritme (`token.endpoint.auth.signing.alg`) en weigert elk ander (getest). Overschakelen is een configuratiewijziging plus een nieuwe JWK met `alg: RS256`. | zelfde; test "alg-pinning" |
| Sleutel | RSA 3072 bit (128 bit sterkte), `use: sig`, `alg` in de JWK | ASVS 5.0 11.2.3 |
| `kid` | De **JWK-thumbprint** (RFC 7638, SHA-256, base64url) van de publieke sleutel: deterministisch, uniek over realms en logs heen, en zonder beheer van een conventie. Geen profieleis: NL GOV OAuth §3.1.5 eist een `kid` alleen voor de sleutels van de server; voor clientsleutels (§2.3.4) eist het profiel een JWK Set. Wij kiezen `kid`, omdat Keycloak de sleutel kiest op de `kid` in de kop (RFC 7515 §4.1.4; getest: met de `kid` van B getekend met A wordt geweigerd) en omdat dat overlap tijdens rotatie mogelijk maakt. | eigen keuze |
| Assertion | `iss` = `sub` = client-id, `aud` = issuer, `jti` van 256 bit (32 bytes uit `randomBytes`, base64url), `iat` en korte `exp` (60 s) | RFC 7523, RFC 10070, NL GOV OAuth §2.3.3 (`jti` ≥ 128 bit) |
| Access token | RS256 met de realmsleutel, `typ: at+jwt`, `kid` in de kop | NL GOV OAuth §3.2.1 (MUST RS256 voor de AS), RFC 9068 |

ES256 is niet verworpen omdat het "niet genoemd" is: NL GOV OpenID §7.2.1 staat andere, minstens even sterke algoritmen toe. PS256 is gekozen omdat het expliciet aanbevolen wordt en het in Keycloak en Spring een gebaand pad is; ES256 is niet beproefd.

Het algoritme van de clientauthenticatie (clientsleutel, per tenant) staat los van het algoritme van het access token en het ID-token (realmsleutel van Keycloak, standaard RS256). **RS256 voor de realmsleutel is een bewuste keuze**: de eis is RS256, en de aanbeveling PS256 voor alle tokens uit NL GOV OpenID §7.2.1 laten we nu liggen. Een PS256-realmsleutel is een apart, niet beproefd werkstuk. De ID-tokenvalidatie in de backend (`id_token_signed_response_alg`) hoort bij het BFF-ticket.

## Keuze: `aud` = issuer

NL GOV OAuth v1.1.0 §2.3.3 (ouder) zegt `aud` = de URL van het token-endpoint. RFC 10070 §4 (november 2026; werkt RFC 7523 bij, na de audience-injection-bevinding) eist dat `aud` de issuer-identifier van de autorisatieserver is, als enige waarde, en dat de server andere waarden weigert, het token-endpoint nadrukkelijk ook. Keycloak accepteert tot en met 26.8.2 beide (getest, ook `typ: client-authentication+jwt` uit RFC 10070). Het risico is klein (de backend praat per tenant met één autorisatieserver), maar zodra Keycloak RFC 10070 strikt afdwingt, breekt een client die het token-endpoint stuurt, en volgens RFC 10070 §6 moeten clients eerst om. **NL Portal stuurt daarom `aud` = `https://<host>/realms/<realm>`** en legt vast dat dit afwijkt van de letterlijke NL GOV-tekst, met RFC 10070 als reden.

## Keuze: TLS en grants

- **`sslRequired: all`** staat in het template. Met `external` accepteert Keycloak HTTP van privé-adressen, en juist het back-channel van de backend (tokenverzoek met assertion, respons met tokens en BSN-claims) komt van een privé-adres (NL GOV OAuth §6, ASVS 5.0 12.3.1). De BFF-spec moet vastleggen hoe TLS tot aan Keycloak loopt (publiek issuer-adres, of mesh-mTLS met juiste `X-Forwarded-Proto`-afhandeling) en dat de backend alleen `https`-endpoints gebruikt. De proef draait HTTP via een testoverlay.
- **`serviceAccountsEnabled: false`** staat in het template (ASVS 5.0 10.4.4: alleen de grants die de client nodig heeft). De bewijsroute is de echte code-uitwisseling, niet `client_credentials`; dat die grant geweigerd wordt is getest. De upgradetest leunt dus niet op een grant die de echte client niet mag hebben.

## Rotatieprocedure

Getest is het mechanisme (stappen 2 tot en met 4, op één node). De rest is beleid dat de beheerdocumentatie bij de realm-chart overneemt.

1. **Genereer** sleutelpaar B (`kid` = thumbprint) in of via de secret store, zodat het paar daar ontstaat en vernietigd wordt (ASVS 5.0 13.3.1); de publieke sleutel gaat naar de tenantwaarden. De ondertekening in een geïsoleerde module (ASVS 13.3.3, L3) is niet haalbaar zolang Spring de private sleutel in het geheugen laadt; dat is het open HSM-punt uit ADR 0021 en blijft daar.
2. Voeg B toe aan de JWKS van de tenant; kcc-run. Nu zijn A en B geldig (overlap).
3. Zet **alle pods** van de backend over op B. Een secret-store-sync leidt volgens ADR 0021 besluit 4 tot een rolling restart, geen hot reload. De overlap moet duren tot de **laatste** pod op B draait: controleer de rollout-status en dat er na stap 4 geen mislukte clientlogins zijn.
4. Haal A uit de JWKS; kcc-run. A wordt direct geweigerd (getest op één node, zie "Wat niet is getest").
5. Vernietig de private sleutel A (ook in de secret store).

**Cryptoperiode (voorstel):** maximaal 1 jaar per sleutel (NIST SP 800-57, ASVS 5.0 11.1.1 en 13.3.4); beheer bevestigt of past aan. Een assertion leeft 60 seconden, dus de uitfaseertermijn van A is alleen het omschakelen van de pods.

**Noodrotatie bij een gelekte sleutel A:** rol B eerst uit (stappen 1 tot en met 3, zo kort mogelijk), haal A dan direct uit het realm (stap 4) en trek de sessies en refresh tokens van de client in (Keycloak not-before of sessies afmelden). Zonder dat kan een aanvaller met een gestolen refresh token blijven verversen. Niet getest.

**Inventaris:** neem elke clientsleutel (algoritme, lengte, `kid`, eigenaar, cryptoperiode) op in de crypto-inventaris (ASVS 5.0 11.1.2).

## PKIoverheid en OIN (NL GOV OAuth §2.3.4)

Het profiel eist PKIoverheid-certificaten met OIN (in `x5c`, SHOULD, of `x5u`) als autorisatieserver, resource server en client niet onder verantwoordelijkheid van dezelfde organisatie draaien. In het celmodel (ADR 0021) beheert dezelfde partij Keycloak, de backend en de API's van een tenant, dus de uitzondering geldt en het template heeft geen `x5c`-keten. **Dat is een aanname die het hostingbesluit moet bevestigen.** Een tenant met een eigen IdP of autorisatieserver buiten die verantwoordelijkheid valt wél onder de MUST; dan moet de JWK een `x5c`-keten bevatten en de `kid`-keuze daarop aansluiten (de thumbprint van de sleutel blijft dan bruikbaar).

## Eisen aan de bouw (Spring Security als client)

Niet beproefd, maar vastgelegd voor het bouwticket. Volgens de securitytoets doet `NimbusJwtClientAuthenticationParametersConverter` (Spring Security `main`) standaard: `jti` = `UUID.randomUUID()` (122 bit, onder de 128 bit van NL GOV OAuth §2.3.3), `aud` = token-URI, het algoritme uit `alg` van de private JWK met een stille terugval op RS256 als `alg` ontbreekt, `exp` = 60 s. Het bouwticket levert daarom:

- een customizer die `jti` van minimaal 128 bit zet (bijvoorbeeld 32 bytes uit `SecureRandom`, base64url) en `aud` op de issuer;
- een private JWK met verplicht `alg: PS256` en `kid` (de thumbprint);
- optioneel `typ: client-authentication+jwt` (RFC 10070, SHOULD; Keycloak 26.8.2 accepteert het);
- een test die een echte Keycloak de Spring-assertion laat accepteren.

## Wat niet is getest (open)

- **Het volledige tenant-template**: identity providers (DigiD-plugin, Signicat), authenticatieflows, keystore-components. Alleen realm en client zijn geladen.
- **Inloggen van kcc zelf met een tenant-service-account** zonder master-`admin`, `skip-server-info`, en Signed JWT - Federated (staat alleen op kcc `main`). De proef gebruikt het bootstrap-admin-account van Keycloak (`admin/admin`, `start-dev`, HTTP: alleen proefinstellingen, niet voor een gedeelde omgeving).
- **kcc-voorwaarden uit ADR 0021 besluit 6**: versleuteling van de remote state (`import.remote-state.encryption-key`) en admin events per realm staan niet in de proef.
- **Meerdere Keycloak-nodes**: of "A wordt direct geweigerd" ook geldt met een Infinispan-cache over meerdere nodes. Telt vooral bij noodrotatie; neem het mee in de dev-upgradetest met twee nodes.
- **`jwks_uri` in plaats van inline `jwks.string`**: NL GOV OAuth §2.3.4 en NL GOV OpenID (registratieparameters) bevelen `jwks_uri` aan voor eenvoudiger rotatie. Inline past bij ADR 0022 besluit 9 en is hier bewezen; voor `jwks_uri` moet de backend een JWKS-endpoint publiceren, wat een eigen beslissing vraagt.
- **Spring Security als client**: zie "Eisen aan de bouw"; de proef tekent met Node.
- **PS256-access tokens**, en kcc-gedrag bij 26.8-specifieke admin-API-wijzigingen buiten client en realm (bijvoorbeeld de scope `kc-admin-api`).
- **Linux**: alleen op Windows met Docker Desktop gedraaid, geen CI.

## Waarneming: levensduur van de assertion

Keycloak weigert een `exp` ver in de toekomst alleen als de assertion geen `iat` heeft ("Token expiration is too far in the future and iat claim not present"). Met `iat` klampt hij de levensduur af op `token.endpoint.auth.signing.max.exp` (60 s in het template) en accepteert hij de assertion; de replaybescherming (de `jti`-cache) duurt dan hoogstens zo lang. Dat is geen beveiligingsgat, maar de grens is dus geen harde weigering. Bron: gedrag in 26.8.2 getest; de code in 26.5.0 (`AbstractBaseJWTValidator.validateTokenActive`) doet hetzelfde.

## Aanbeveling

1. Houd kcc als gekozen tool (ADR 0021 besluit 6). Pin het image op `6.5.1-26.5.5` en Keycloak op 26.8.2, en neem deze proeftest op als upgradetest, als `mise`-taak: draai hem bij elke Keycloak- of kcc-bump en maak hem de poort van besluit 13. Een tweede node in die test dekt de cache-vraag.
2. Leg PS256 vast als clientauthenticatie-algoritme (RS256 alleen als gedocumenteerde uitzondering), `kid` = JWK-thumbprint, `aud` = issuer, TLS verplicht en geen service-account, zoals hierboven.
3. Neem de rotatieprocedure (met cryptoperiode, noodrotatie en meerdere pods) en de crypto-inventaris op in de beheerdocumentatie bij de realm-chart. Laat een CI-check op het tenantbestand uniciteit van `kid` en de aanwezigheid van `alg`, `use` en `kid` controleren.
4. Verduidelijk besluit 13 over wat "ondersteunen" is (zie Samenvatting). Volgt een kcc-release die 26.8 formeel ondersteunt, vervang dan het 26.5.5-image; breekt de upgradetest tegen een nieuwe Keycloak-minor, volg dan ADR 0022 besluit 13.

## Signalen voor andere tickets

- ADR 0022 besluit 2 noemt het cookieprefix `__Host-`; RFC 10017 §6.1.3.2 beveelt `__Host-Http-` aan. Voor het BFF-ticket.
