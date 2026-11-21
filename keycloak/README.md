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
| `nlportal-saml-authncontext-note-mapper` | `AuthnContextClassRef` van de gevalideerde SAML-assertion | `note` (bijv. `acr`) | Leeg bij een `AuthnStatement` zonder `AuthnContextClassRef`; faalt bij nul of meer dan een `AuthnStatement`, of als het statement dat Keycloak los in de context zet niet dat van de assertion is |
| `nlportal-saml-attribute-note-mapper` | SAML-attribuut | `attribute.name`, `note` | `String` of `NameID`; leeg bij ontbrekend attribuut; faalt bij meer dan een waarde (ook dubbel attribuut), een attribuut zonder waarde, lege waarde, ander type (zoals `EncryptedID`), een waarde die zelf markup is (Keycloak levert een onbekend kindelement als tekst) of een versleuteld attribuut |
| `nlportal-oidc-claim-note-mapper` | claim van het ID-token van de wallet | `claim.name`, `note` | Leeg bij ontbrekende claim in het gevalideerde ID-token (nooit uit access token of userinfo); faalt bij een claim die geen enkele tekstwaarde is |

Gemeenschappelijk (basisklasse `SessionNoteMapper`): **elke login zet de notitie expliciet** (de waarde of een lege tekst), zodat een waarde van een eerdere
login in een hergebruikte Keycloak-sessie niet blijft staan. De waarde wordt als tekst in de contextdata onthouden omdat
Keycloak bij de eerste login de context serialiseert en de assertion dan verloren gaat. Een lege of ontbrekende `acr` is een
weigering bij de callback (#506); de mapper verzint nooit een niveau.

### Wat leeg betekent en wanneer de login faalt

| Situatie | AuthnContextClassRef | SAML-attribuut | OIDC-claim |
|---|---|---|---|
| Bron ontbreekt (geen attribuut, geen claim, geen `AuthnContextClassRef`) | leeg | leeg | leeg |
| Lege of niet-tekstwaarde, meer dan een waarde | n.v.t. | **faalt** | **faalt** |
| Geen assertion of ID-token in de context en geen eerder onthouden waarde | **faalt** | **faalt** | **faalt** |
| Ontbrekende `note`, `attribute.name` of `claim.name` in de mapperconfiguratie | **faalt** | **faalt** | **faalt** |

Leeg is bewust een geldige uitkomst voor een ontbrekend attribuut (een gewone burger heeft geen `LegalSubjectID`); de
callback van #506 weigert een lege of ontbrekende `acr`. Een attribuut dat er wel is maar geen geldige waarde heeft, is
een fout. Er is geen validatiehaakje in de mapper-SPI, dus een fout in de configuratie geeft bij de eerste login een
duidelijke melding in plaats van bij het opslaan.

### Vertrouwensgrens: de SAML-mappers eisen een gecontroleerde handtekening

De waarde (niveau, BSN) is alleen te vertrouwen als Keycloak de handtekening op de assertion heeft gecontroleerd. De twee
SAML-mappers (`SamlSessionNoteMapper`) laten de login daarom falen als de koppeling niet zo staat: `validateSignature`
aan, `wantAssertionsSigned` aan en een vastgepind `signingCertificate`. Anders kon iedereen die een SAML-response naar het
endpoint van de broker post een willekeurig niveau en BSN in het token zetten. De realm-template (#505) moet die drie
instellingen per koppeling zetten; de mapper is het vangnet als iemand ze in de beheerconsole uitzet.

De integratietest (`handmatig.test.mjs`, met een eigen IdP die assertions ondertekent) bewijst met Keycloak 26.8.2: een
ongetekende assertion, een assertion met een andere sleutel, een response met een ondertekende en een ongetekende
assertion, een ondertekende assertion verstopt in `Extensions` of `Advice` (signature wrapping) en een opnieuw geposte
assertion worden door Keycloak zelf geweigerd; twee `AuthnStatement`s met verschillend niveau en een koppeling met
verkeerde instellingen worden door de mapper geweigerd. Deze set is verplicht bij elke Keycloak-bump.

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
`mise run lint`. De integratietest draait niet in `mise run test` (Docker). Hij gebruikt vaste hostpoorten (18080 Keycloak, 18081 BFF-callback,
18082 eigen IdP): de realms noemen die poorten in hun URL's, dus twee runs tegelijk botsen; draai er een per machine.

### Versie-gelijkheid en basisimage op digest

`keycloakVersion` in `provider/gradle.properties` is de versie waartegen de provider compileert (de Keycloak-jars staan
`compileOnly`: ze komen uit het image). `Dockerfile` heeft `ARG KEYCLOAK_VERSION` en `ARG KEYCLOAK_DIGEST`; het basisimage
staat op digest, niet op tag. De build faalt bij verschil, op twee plekken: `keycloak:check-version` (lint) vergelijkt de
bestanden, en de Dockerfile controleert dat het basisimage de jar van die versie bevat. Bij een Keycloak-bump wijzig je
versie, digest en `gradle.properties` samen en draai je `keycloak:verify-dependencies`.

**De mappertests zijn verplicht bij elke Keycloak-bump**: `keycloak:test` en `keycloak:integration-test`. De mapper-SPI is
bij Keycloak intern gemarkeerd (waarschuwing `KC-SERVICES0047`), dus een patchrelease kan hem breken.

**Nog niet geautomatiseerd:** Renovate herkent de losse `ARG`-regels in de Dockerfile en `gradle.properties` niet als
Keycloak-update, en geen CI-job draait deze taken. Dat is het eerste punt van #524 (regex-manager, CI-job). Tot dan:
controleer wekelijks de Keycloak-releases en bump versie, digest en `gradle.properties` met de hand, zeker bij een
beveiligingspatch.

### Build van de provider

`keycloak/provider` is een eigen Gradle-build buiten `backend/` (geen Spring-dependency-management of ArchUnit-lagen in de
SPI), maar gebruikt de wrapper van `backend/` (`../../backend/gradlew`): de Gradle-versie komt dus van de backend en
`backend/gradle.properties` geldt hier niet. Bumpt de backend Gradle, draai dan ook `keycloak:build`, `keycloak:test` en
`keycloak:verify-dependencies`. De Java-bron compileert met `-Xlint:all -Werror`; een aparte formatteringscontrole (Spotless)
is er niet, omdat die extra build-dependencies in `verification-metadata.xml` zou brengen voor een handvol klassen.

### Image

Multi-stage: provider erin, `kc.sh build` met `KC_DB=postgres` en `KC_HEALTH_ENABLED=true` (geoptimaliseerde start, geen
`start-dev`), start met `start --optimized`, expliciet `USER 1000` (`keycloak:check-version` controleert dat). Code (`bin`, `conf`, `lib`, `providers`, `themes`) is van root en alleen leesbaar voor het proces; alleen `data/` is schrijfbaar, zodat een gecompromitteerd proces zijn eigen
providers niet kan overschrijven (`integratietest/image-rechten.test.mjs` probeert dat in het gebouwde image als uid 1000 en moet falen; `start --optimized` start met deze rechten). Het label `nl.nlportal.keycloak.provider.sha256` bevat de sha256 van de provider-jar in het image. De bouwopties zijn vastgelegd in het
image; wie andere opties wil moet het image opnieuw bouwen. Runtime-instellingen (database, hostname, admin) blijven
omgevingsvariabelen.

**Alleen `start --optimized`.** Een gewone `start`, of een bouwoptie die afwijkt van het image (bijvoorbeeld `KC_HTTP_RELATIVE_PATH` of een andere
`KC_DB`), laat Keycloak zichzelf opnieuw bouwen. Dat faalt bewust: `lib/quarkus` is niet schrijfbaar voor het proces, zodat een
gecompromitteerd proces geen code kan wijzigen. Zet zo'n optie in de Dockerfile en bouw het image opnieuw. Keycloak draait dus op
het pad `/` (geen `/auth`): de compose-stack en de backend- en frontendconfiguratie gebruiken `http://localhost:8082`.

## Supply chain

- SBOM en scan van het eigen image: `sbom:keycloak-image` en `security:keycloak-vuln-scan`. De provider-jar heeft geen
  runtime-dependencies; het image-SBOM omvat Keycloak, het besturingssysteem en de provider.
- Bekende treffers bij 26.8.2 (basisimage, niet van de provider): `guava 33.6.0-jre` (GHSA-xxph-c9ww-hj94, fix 33.7.2: moet
  Keycloak upstream bumpen) en `sqlite-libs`/`java-21-openjdk-headless` van het besturingssysteem zonder beschikbare fix.
  De scan gebruikt `--only-fixed`. De guava-treffer staat als uitzondering in `keycloak/.grype.yaml`, met reden (geen
  native deserialisatie van onvertrouwde bytes via de publieke poorten) en herzieningsdatum 2027-01-31; verwijder de regel zodra
  een Keycloak-patch guava 33.7.2 of nieuwer meelevert. Een nieuwe treffer laat de scan dus falen.
- Dependencies van de provider-build (alleen Keycloak-jars en testbibliotheken) staan in `provider/gradle/verification-metadata.xml`.
- **Ondertekening en provenance van het image (cosign, attestatie) zijn nog niet gebouwd**: er is nog geen CI-workflow die dit
  image bouwt en publiceert. Zie "Uitgesteld" hieronder (#524).

## Uitgesteld

| Punt | Ticket |
|---|---|
| Realm-template en keycloak-config-cli met de mappers; realmtest (beide handtekeningvlaggen en vastgepind certificaat per SAML-koppeling, wissende mapper per notitie, geen identiteitskoppeling, accountconsole uit, scope `acr` van de client af, OIDC-koppeling van de wallet) | #505 |
| Renovate-regex-manager (eigen groep) voor versie, digest en `gradle.properties`; CI-job voor `keycloak:test`, `check-version` en de integratietest; SBOM en grype in CI; cosign en SLSA-provenance; controlepunt voor de herzieningsdatum van `.grype.yaml` (2027-01-31); SonarCloud-dekking voor `keycloak/provider` | #524 |
| `acr`-volgordetabel (Basis < Midden < Substantieel < Hoog), onbekende en lege `acr` weigeren, niveau-afdwinging | #506 |
| BSN uit `NameID`/`ActingSubjectID`/`LegalSubjectID`: `NameQualifier`, format en sectorprefix `s00000000:` controleren, elfproef; blokkeert livegang | #506 |
| Echte DigiD (artifact-binding, `EncryptedID`) en echte wallet zijn niet getest; de simulator en de eigen IdP van de test zijn dat niet | #505, #506 |

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
- Een SAML-koppeling met `useMetadataDescriptorUrl` (certificaat uit de metadata-URL, niet vastgepind) faalt gesloten: de mapper eist
  een vastgepind `signingCertificate` en geeft anders een loginfout. Pin het certificaat in de koppeling; de realm-generator weigert
  een metadata-URL al bij het genereren.
- De OIDC-koppeling (wallet): `nlportal-oidc-claim-note-mapper` vertrouwt het `VALIDATED_ID_TOKEN` en kijkt niet naar de
  handtekeninginstelling van de koppeling. Dat is bewust: bij een authorization-code-flow over TLS met clientauthenticatie
  mag de ID-tokenhandtekening volgens OIDC Core worden overgeslagen. De realmtest (#505) controleert dat de wallet-koppeling
  zo is ingericht (code-flow, clientauthenticatie, TLS-endpoints) en geen implicit of hybrid flow gebruikt.
- `integratietest/realms/portal-realm.json` en `idp-sim-realm.json` zijn een werkend voorbeeld voor de mappertests; het
  echte template staat in `realm-template/`.

### Realm-template (#505)

Eén template voor alle tenants: `realm-template/realm.json` (de basis) plus een fragment per inlogmiddel
(`realm-template/inlogmiddel/<naam>.json`: `digid`, `wallet`; het testinlogmiddel heeft geen fragment). Alleen in ontwikkeling komen
`realm-template/demo-gebruikers.json` (demo-gebruikers met vaste wachtwoordhash, bij het testinlogmiddel) en `realm-template/tussenfase/spa.json`
(de SPA-client `nl-portal`, tot #506) erbij. `scripts/lib/realm.mjs` vult het met de waarden uit het
tenantbestand (`docker-compose/imports/tenants`) en voegt alleen de fragmenten van de ingeschakelde inlogmiddelen toe;
`mise run dev:setup` genereert zo per tenant `docker-compose/.generated/keycloak/<oin>.json` plus een sleutelpaar voor
`private_key_jwt` (de private JWK komt in `docker-compose/.generated/tenants/<oin>/clientsleutel.jwk.json`, de publieke in het realm).
keycloak-config-cli (service `nl-portal-keycloak-config` in de compose) laadt het resultaat tegen dit image. Geheimen staan niet
in het realm: de m2m-client en de wallet-koppeling verwijzen naar kcc-substitutie (`$(env:NAAM)`).

Wat de generator afdwingt en de realmtest (`scripts/lib/realm.test.mjs`, in `mise run test`) bewijst, per gegenereerd realm:
alleen `nl-portal-backend` (private_key_jwt, PS256, PKCE S256, `at+jwt`, exacte redirect- en post-logout-URI's, back-channel
logout-URL, geen service-account, geen directe grants), tot #506 ook de huidige SPA-client en `nl-portal-m2m`; accountconsole uit;
scope `acr` van `nl-portal-backend` af; per koppeling een mapper voor elke notitie die een client-mapper leest;
SAML met `validateSignature`, `wantAssertionsSigned` en een vastgepind `signingCertificate`, minimumniveau als
`authnContextClassRef`; wallet met code-flow, PKCE S256, clientauthenticatie en TLS; geen identiteitskoppeling; SSO-tijden niet
langer dan het portaal; buiten ontwikkeling alleen `https`-koppelingen. Het testinlogmiddel (de standaard
browserflow; demo-gebruikers alleen in ontwikkeling) bestaat alleen als de tenant het kent; de generator weigert het in productie, en een realm
zonder testinlogmiddel heeft de flow `alleen-koppelingen` en geen gebruikers. Buiten ontwikkeling staat er geen SPA-client, geen
directe grant (ROPC), geen wildcard-redirect en geen `offline_access`; offline sessies zijn begrensd op 8 uur en refresh-tokens roteren.
`nl-portal-backend` krijgt alleen de scope `basic`, geen `profile` of `email`: de gebruikersnaam van een gebrokerde gebruiker is `<alias>.<UUID>`
(nooit de NameID of een ander nummer) en staat niet in een token; wat Keycloak per koppeling onthoudt (de federated identity) is de NameID.
Verder weigert de generator: een metadata-URL bij een koppeling (alleen een vastgepind certificaat), `$(` in tenant- of koppelingsgegevens
(kcc-substitutie), een domein dat geen hostnaam is, een niet-https-broker buiten ontwikkeling, een onbekend minimumniveau of onder
substantieel in productie, en sessietijden van nul of met een inactiviteit boven de absolute duur.
`integratietest/handmatig.test.mjs` bewijst tegen het echte image dat een oude, geldige SAML-response in een nieuwe authenticatiesessie wordt geweigerd (ASVS 6.8.3).
`integratietest/realm-kcc.test.mjs` laadt dezelfde realms met keycloak-config-cli in een echte Keycloak en doorloopt een code-flow
met een `private_key_jwt`-assertion.
