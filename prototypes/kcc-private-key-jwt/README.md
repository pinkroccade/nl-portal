# Prototype #501: keycloak-config-cli, Keycloak 26.8 en private_key_jwt

Wegwerpprototype (niet mergen). Draaien, met Docker: `cd prototypes/kcc-private-key-jwt && node --test test/`
(`CLIENT_AUTH_ALG=RS256` voor RS256 in plaats van PS256; `KEYCLOAK_IMAGE` en `KCC_IMAGE` overschrijven de versies;
`KC_PORT` de poort, standaard 18501; `KCC_LOG=<bestand>` bewaart de kcc-uitvoer).

Elke test importeert zelf het realm dat hij nodig heeft, dus de tests zijn ook los te draaien
(`node --test --test-name-pattern=rotatie test/`). Een volledige run duurt ruim 3 minuten.
Alleen op Windows met Docker Desktop gedraaid; Linux is niet geverifieerd.

Het template (`realm-template.json`) is wat een tenant krijgt: `sslRequired: all`, geen service-account. De test
voegt via `metTestOverlay` (lib.mjs) alleen toe wat de proef nodig heeft: `sslRequired: none` (de container praat
HTTP) en een testgebruiker met een willekeurig wachtwoord voor de code-flow.

Uitkomst en conclusies: zie de onderzoeksnotitie `site/development/research/kcc-keycloak-26-8-private-key-jwt.md`.
