# NL Portal Keycloak-image

Een eigen Keycloak-image met de NL Portal-provider: drie identity-provider-mappers die gegevens van de inlogmiddelen
(DigiD via SAML, wallet via OIDC) in een **sessienotitie** van de gebruikerssessie zetten. De client-mapper "User Session
Note" van `nl-portal-backend` zet de notitie als claim in het ID-token. Besluit en achtergrond: epic #499, ticket #527,
prototype #500 (`site/development/research/prototype-digid-wallet-claims.md`).

Besluit (#527, eerste acceptatiecriterium): **eigen provider in een eigen image**, zoals het prototype bewees. Keycloak
26.8 heeft geen ingebouwde mapper voor de `AuthnContextClassRef` van de assertion, en de ingebouwde BSN- en wallet-mappers
zetten bij een latere login in dezelfde Keycloak-sessie niet altijd opnieuw een notitie. De tussenbroker-variant blijft
het terugvalplan (zie "Terugvalplan").

## Mappers

| Mapper-id | Bron | Configuratie | Gedrag |
|---|---|---|---|
| `nlportal-saml-authncontext-note-mapper` | `AuthnContextClassRef` van de SAML-assertion | `note` (bijv. `acr`) | Leeg bij ontbrekende `AuthnStatement` of `AuthnContextClassRef` |
| `nlportal-saml-attribute-note-mapper` | SAML-attribuut | `attribute.name`, `note` | `String` of `NameID`; leeg bij ontbrekend attribuut; faalt bij meer dan een waarde (ook dubbel attribuut), lege waarde, ander type (zoals `EncryptedID`) of een versleuteld attribuut |
| `nlportal-oidc-claim-note-mapper` | claim van het ID-token van de wallet | `claim.name`, `note` | Leeg bij ontbrekende claim; faalt bij een claim die geen enkele tekstwaarde is |

Gemeenschappelijk: **elke login zet de notitie expliciet** (de waarde of een lege tekst), zodat een waarde van een eerdere
login in een hergebruikte Keycloak-sessie niet blijft staan. De waarde wordt als tekst in de contextdata onthouden omdat
Keycloak bij de eerste login de context serialiseert en de assertion dan verloren gaat. Een lege of ontbrekende `acr` is een
weigering bij de callback (#506); de mapper verzint nooit een niveau.

Client-mapper op `nl-portal-backend`: `oidc-usersessionmodel-note-mapper`, notitie `acr`, claim `acr`, alleen in het
ID-token; de standaard client scope `acr` gaat van de client af (anders schrijven twee mappers dezelfde claim).

## Bouwen en testen

Alles via mise, vanuit de repo-root (`mise tasks` toont ze):

```shell
mise run keycloak:test               # unit-tests van de mappers (JUnit, tegen de echte Keycloak-klassen)
mise run keycloak:build              # provider-jar: keycloak/provider/build/libs/nlportal-keycloak-provider.jar
mise run keycloak:check-version      # Dockerfile en provider-build gebruiken dezelfde Keycloak-versie, basisimage op digest
mise run keycloak:image              # image nl-portal-keycloak:dev (vereist Docker)
mise run keycloak:integration-test   # eigen image tegen een echte Keycloak met gesimuleerde DigiD en wallet (vereist Docker)
mise run keycloak:verify-dependencies  # verification-metadata.xml opnieuw genereren na een dependencywijziging
mise run sbom:keycloak-image         # CycloneDX-SBOM van het image
mise run security:keycloak-vuln-scan # grype op de SBOM (faalt op high/critical met een beschikbare fix)
```

`keycloak:build` en `keycloak:test` zitten in `mise run build` en `mise run test`; `keycloak:check-version` in
`mise run lint`. De integratietest draait niet in `mise run test` (Docker).

### Versie-gelijkheid en basisimage op digest

`keycloakVersion` in `provider/gradle.properties` is de versie waartegen de provider compileert (de Keycloak-jars staan
`compileOnly`: ze komen uit het image). `Dockerfile` heeft `ARG KEYCLOAK_VERSION` en `ARG KEYCLOAK_DIGEST`; het basisimage
staat op digest, niet op tag. De build faalt bij verschil, op twee plekken: `keycloak:check-version` (lint) vergelijkt de
bestanden, en de Dockerfile controleert dat het basisimage de jar van die versie bevat. Bij een Keycloak-bump wijzig je
versie, digest en `gradle.properties` samen en draai je `keycloak:verify-dependencies`.

**De mappertests zijn verplicht in elke Renovate-PR die Keycloak bumpt**: `keycloak:test` en
`keycloak:integration-test`. De mapper-SPI is bij Keycloak intern gemarkeerd (waarschuwing `KC-SERVICES0047`), dus een
patchrelease kan hem breken.

### Image

Multi-stage: provider erin, `kc.sh build` met `KC_DB=postgres` en `KC_HEALTH_ENABLED=true` (geoptimaliseerde start, geen
`start-dev`), start met `start --optimized`, niet-root (uid 1000) uit het basisimage. De bouwopties zijn vastgelegd in het
image; wie andere opties wil moet het image opnieuw bouwen. Runtime-instellingen (database, hostname, admin) blijven
omgevingsvariabelen.

## Supply chain

- SBOM en scan van het eigen image: `sbom:keycloak-image` en `security:keycloak-vuln-scan`. De provider-jar heeft geen
  runtime-dependencies; het image-SBOM omvat Keycloak, het besturingssysteem en de provider.
- Bekende treffers bij 26.8.2 (basisimage, niet van de provider): `guava 33.6.0-jre` (GHSA-xxph-c9ww-hj94, fix 33.7.2: moet
  Keycloak upstream bumpen) en `sqlite-libs`/`java-21-openjdk-headless` van het besturingssysteem zonder beschikbare fix.
  De scan gebruikt `--only-fixed`; de guava-treffer laat hem dus falen tot Keycloak bijwerkt.
- Dependencies van de provider-build (alleen Keycloak-jars en testbibliotheken) staan in `provider/gradle/verification-metadata.xml`.
- **Ondertekening en provenance van het image (cosign, attestatie) zijn nog niet gebouwd**: er is nog geen CI-workflow die dit
  image bouwt en publiceert. Zie de open punten in de notitie bij #527 voor ticket #524 (CI).

## Terugvalplan

Breekt een Keycloak-patch de mapper (de SPI is intern): de tussenbroker-variant (een Keycloak die de assertion ontvangt en
het niveau als attribuut doorgeeft aan de realm van de gemeente), of logins weigeren tot de fix. **Nooit het niveau
overslaan.**

## Gebruik in het realm-template (#505)

- Draai het image `nl-portal-keycloak` in plaats van `quay.io/keycloak/keycloak` en start met `start --optimized`.
- Zet per SAML-koppeling (DigiD) de mappers `nlportal-saml-authncontext-note-mapper` (`note`: `acr`) en
  `nlportal-saml-attribute-note-mapper` (`acting_subject_id`, `legal_subject_id`), en per OIDC-koppeling (wallet)
  `nlportal-oidc-claim-note-mapper`, elk met `syncMode: FORCE`. keycloak-config-cli kent het mappertype pas als het in
  het image zit: draai hem tegen dit image.
- Zet op `nl-portal-backend` de client-mappers `oidc-usersessionmodel-note-mapper` en haal de scope `acr` eraf.
- `integratietest/realms/portal-realm.json` en `idp-sim-realm.json` zijn een werkend voorbeeld; `integratietest/claims.test.mjs`
  is het uitgangspunt voor de realmtest.
