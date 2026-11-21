# keycloak-config-cli op Keycloak 26.8 met private_key_jwt en sleutelrotatie

Geraadpleegd op: 2026-11-21. Prototype voor #501 (epic #499), uitgangspunt ADR 0021 besluit 6 en ADR 0022 besluit 9 en 13. Bouwt voort op [multitenancy-realm-as-code.md](./multitenancy-realm-as-code.md) en [auth-bff-nlgov-oauth.md](./auth-bff-nlgov-oauth.md).

Prototypecode (wegwerp, niet gemerged): branch [`prototype/kcc-private-key-jwt-501`](https://github.com/pinkroccade/nl-portal/tree/prototype/kcc-private-key-jwt-501), map `prototypes/kcc-private-key-jwt/`.

## Samenvatting

- **keycloak-config-cli (kcc) 6.5.1 werkt in de proef tegen Keycloak 26.8.2.** Image `adorsys/keycloak-config-cli:6.5.1-26.5.5` (de nieuwste tag; de Docker Hub-tags eindigen op Keycloak 26.5.5) laadt een realm uit één template op Keycloak 26.8.2, herkent de serverversie (`Using version: 26.8.2`) en geeft geen waarschuwingen of fouten. Dat is een **empirische uitkomst, geen ondersteuning**: 26.8 staat nog niet in de kcc-CI-matrix. De route uit ADR 0022 besluit 13 (patch gaat voor de tool) is daarom niet nodig, maar de upgradetest in dev blijft verplicht.
- **`private_key_jwt` werkt** met de client `nl-portal-backend` (`clientAuthenticatorType: client-jwt`, publieke sleutel inline in het realm als `jwks.string`). Een `client_credentials`-verzoek met een client-assertion geeft een token; een `client_secret` of een assertion met een onbekende sleutel wordt geweigerd (`unauthorized_client` of `invalid_client`).
- **Rotatie met twee sleutels tegelijk is aangetoond:** met sleutel A en B in het realm werken beide assertions; na een kcc-run met alleen B wordt A geweigerd en werkt B nog. Keycloak kiest de sleutel op de `kid` in de kop van de assertion.
- **Algoritme: PS256, met RS256 als verplichte basis.** NL GOV OAuth-profiel §1.3.3: de autorisatieserver MOET RS256 ondersteunen; de NL-aanvulling beveelt PS256 aan; ES256 wordt niet genoemd. Access tokens: RS256 verplicht (§2.2.1). De proef slaagt met beide (RSA 3072 bit).
- **PKCE S256, `at+jwt` en exacte redirect-URI's staan in het template en werken:** een autorisatieverzoek zonder `code_challenge`, met `plain`, of met een redirect-URI die alleen een prefix-match is, wordt geweigerd; mét S256 en de exacte URI krijgt de gebruiker het inlogscherm. Het access token heeft `typ: at+jwt`, een `kid` en `azp` = de client.

## Wat de proef doet

Seam: het token-endpoint en het autorisatie-endpoint van een echte Keycloak 26.8.2 (container), ingericht door kcc (container). Geen mocks. Vijf tests in `test/rotatie.test.mjs` (draaien ± 1 minuut):

1. kcc laadt het template; assertion-verzoek slaagt; token is `at+jwt` met `kid`, `azp`, `jti`, juiste `iss`.
2. `client_secret` en een vreemde sleutel worden geweigerd.
3. Rotatie: [A, B] beide geldig; daarna [B] alleen B.
4. Clientconfiguratie via de admin-API: `client-jwt`, niet-publiek, exacte redirect-URI's, `pkce.code.challenge.method=S256`, `token.endpoint.auth.signing.alg`, `access.token.header.type.rfc9068=true`, impliciete flow uit.
5. Autorisatieverzoeken (PKCE en redirect-URI) zoals hierboven.

Het template (`realm-template.json`) bevat per tenant alleen `realm`, `clientId`, `redirectUris`, `jwks` en het algoritme. In de proef rendert Node de waarden; in productie doet Helm dat en komen geheimen via kcc-substitutie (`$(file:...)`). De publieke sleutel is geen geheim en mag in het template (ADR 0022 besluit 9).

Gebruikte kcc-instellingen: `IMPORT_CACHE_ENABLED=false` (drift herstellen), `IMPORT_VAR_SUBSTITUTION_ENABLED=true` met `UNDEFINED_THROWS_EXCEPTIONS=true`.

## Algoritme en `kid` (NL GOV)

| Onderwerp | Keuze | Bron |
|---|---|---|
| Clientauthenticatie | `private_key_jwt`, algoritme **PS256** (RS256 toegestaan als terugval) | NL GOV OAuth §1.3.3 |
| Sleutel | RSA 3072 bit, `use: sig`, `alg` in de JWK, `kid` verplicht | profiel §2.1.5; eigen keuze voor 3072 bit |
| `kid`-conventie | `<tenant>-<jaar>-<volgnr>`, bijvoorbeeld `tenant1-2026-a`; uniek binnen het realm, nooit hergebruikt | eigen voorstel; Keycloak selecteert op `kid` |
| Assertion | `iss` = `sub` = client-id, `aud` = token-endpoint, `jti` van minimaal 128 bit (twee UUID's), korte `exp` (60 s) | RFC 7523, NL GOV §2.3.3 |
| Access token | RS256 met de realm-sleutel, `typ: at+jwt`, `kid` in de kop | profiel §2.2.1, RFC 9068 |

Het algoritme van de clientauthenticatie (clientsleutel, per tenant) staat los van het algoritme van het access token (realmsleutel van Keycloak, standaard RS256). Voor PS256-tokens is een aparte realmsleutel nodig; dat is hier niet beproefd en niet vereist.

## Rotatieprocedure (zoals getest)

1. Genereer sleutelpaar B met een nieuwe `kid`; private sleutel naar de secret store, publieke sleutel in de tenantwaarden.
2. Voeg B toe aan de JWKS van de tenant; kcc-run. Nu zijn A en B geldig (overlap).
3. Zet de backend over op B (secret store, herstart of herlaad) en controleer een login.
4. Haal A uit de JWKS; kcc-run. A wordt direct geweigerd.
5. Vernietig de private sleutel A.

De overlap hoeft alleen zo lang te duren als het omschakelen van de backend. Een assertion leeft 60 seconden, dus er is geen langere uitfaseertermijn nodig.

## Wat niet is getest (open)

- **Het volledige tenant-template**: identity providers (DigiD-plugin, Signicat), authenticatieflows, keystore-components. Alleen realm en client zijn geladen.
- **Inloggen van kcc zelf met een tenant-service-account** zonder master-`admin`, `skip-server-info`, en Signed JWT - Federated (staat alleen op kcc `main`). De proef gebruikt het bootstrap-admin-account van Keycloak.
- **De code-flow met een echte gebruiker**: de proef bewijst de clientauthenticatie op het token-endpoint met `client_credentials` (zelfde authenticator als bij de code-flow) en de weigeringen op het autorisatie-endpoint, niet de volledige code-uitwisseling met `code_verifier`. `serviceAccountsEnabled: true` staat alleen in de proef; de echte BFF-client zet dat uit.
- **`jwks_uri` in plaats van inline `jwks.string`**: het NL GOV-profiel (§1.3.4) beveelt `jwks_uri` aan voor eenvoudiger rotatie. Inline past bij ADR 0022 besluit 9 (publieke sleutel in het template) en is hier bewezen; voor `jwks_uri` moet de backend een JWKS-endpoint publiceren, wat een eigen beslissing vraagt.
- **Spring Security als client**: dat `NimbusJwtClientAuthenticationParametersConverter` PS256 met `kid` produceert die Keycloak accepteert, is niet beproefd (de proef tekent met Node).
- **PS256-access tokens**, en kcc-gedrag bij 26.8-specifieke admin-API-wijzigingen buiten client en realm (bijvoorbeeld de scope `kc-admin-api` voor eigen admin-clients).

## Aanbeveling

1. Houd kcc als gekozen tool (ADR 0021 besluit 6). Pin het image op `6.5.1-26.5.5` en Keycloak op 26.8.2, en neem deze proeftest op als upgradetest: draai hem bij elke Keycloak- of kcc-bump.
2. Leg PS256 vast als clientauthenticatie-algoritme, RS256 als terugval, met de `kid`-conventie hierboven.
3. Neem de rotatieprocedure op in de beheerdocumentatie bij de realm-chart.
4. Als een volgende kcc-release 26.8 formeel ondersteunt, vervang dan het 26.5.5-image; breekt het tegen een nieuwe Keycloak-minor, volg dan ADR 0022 besluit 13.
