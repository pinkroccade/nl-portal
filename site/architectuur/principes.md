# Architectuurprincipes

Register van de architectuurprincipes waaraan we de code, de structuur, de architectuur en de documentatie van
NL Portal toetsen. Het besluit en de werkwijze staan in
[ADR 0023](../development/adr/0023-architectuurprincipes-en-toetsing.md); de status is *Voorgesteld* tot de
architect het register heeft beoordeeld.

Elk principe volgt het TOGAF-format **Statement**, **Rationale** en **Implications**, aangevuld met:

- **Bron:** minstens één ID uit de [normatieve bronnen](../compliance/normatieve-bronnen.md).
- **Toetsing:** hoe we het principe bewaken, als `;`-gescheiden lijst `<soort>: <verwijzing>`. De soort is
  *ArchUnit-regel*, *Fitness test*, *CI-controle*, *Lintregel* of *Reviewpunt*. Een principe zonder
  automatische toetsing krijgt een reviewpunt in
  [`CODING_STANDARDS.md`](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#principes-zonder-automatische-toetsing),
  nooit niets.

`mise run compliance:check` bewaakt dit register en draait in CI. Het faalt op een principe zonder bron of
toetsing, op een bron zonder register-ID, op een toetsing zonder bekende soort, op een reviewpunt dat niet naar
`CODING_STANDARDS.md` verwijst en op een principe-ID dat elders in `site/`, `backend/` of de workflows staat maar
hier niet bestaat. Verwijs met het ID (`P-07`) naar een principe, bijvoorbeeld in een ADR of een
`.because()`-tekst.

Een toetsing bewijst niet dat het principe volledig wordt nageleefd; ze bewaakt het deel dat een controle kan
zien. Wat erbuiten valt, is de reden voor het reviewpunt.

## Overzicht

| ID | Principe | Thema |
|---|---|---|
| P-01 | Beveiliging is een ontwerpeis | Security by design |
| P-02 | Tokens en geheimen verlaten de server niet | Security by design |
| P-03 | Geen geheimen in code of configuratie | BIO2 |
| P-04 | Kwetsbaarheden in afhankelijkheden worden bewaakt | BIO2 |
| P-05 | Alleen de gegevens die nodig zijn | Privacy by design |
| P-06 | Gegevens blijven bij de bron | Privacy by design, Common Ground |
| P-07 | Onbereikbaar wie geen recht heeft, inert waar het niet nodig is | Least functionality |
| P-08 | De kern hangt niet af van een module | Least functionality, modulaire kern |
| P-09 | Open standaarden: pas toe of leg uit | Open standaarden |
| P-10 | REST-API's volgen de NL API Design Rules | Open standaarden |
| P-11 | Toegankelijk volgens WCAG 2.2 AA | Open standaarden |
| P-12 | Herkenbaar in Common Ground en GEMMA | Common Ground en GEMMA |
| P-13 | Elke bewering in de documentatie is getest of gegenereerd | Docs as code |
| P-14 | Groei mag, vaagheid niet | Structuur |
| P-15 | Elke controle bewaakt een principe | Proportionele controles |

## Register

### P-01 Beveiliging is een ontwerpeis

**Statement:** NL Portal voldoet aan OWASP ASVS 5.0 level 2 als basis; een eis wordt bij het ontwerp
meegenomen, niet achteraf getest.

**Rationale:** NL Portal verwerkt overheidsidentiteit (DigiD, eHerkenning) en BRP-gegevens. Een beveiligingsfout
achteraf repareren is duurder en laat een periode open waarin burgers geraakt worden.

**Implications:** Een ontwerp of ADR die authenticatie, autorisatie of gegevensverwerking raakt, noemt de
ASVS-eisen die erbij horen. Een eis die we uitvoeren, staat in de traceerbaarheidsmatrix van de normatieve
bronnen. Een externe pentest blijft nodig; dit principe vervangt hem niet.

**Bron:** `N-ASVS-5.0`, `N-BIO2-1.3` (ISO/IEC 27002:2022 8.25 en 8.28)

**Toetsing:** CI-controle: `mise run compliance:check` (traceerbaarheidsmatrix van ASVS-eisen naar guardrail);
Reviewpunt: [security by design](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#security-by-design)

### P-02 Tokens en geheimen verlaten de server niet

**Statement:** Toegangstokens, verversingstokens en clientgeheimen blijven op de server; de browser krijgt ze
niet (backend-for-frontend, ADR 0022). De OAuth-inrichting volgt RFC 9700; zolang token exchange bestaat, volgt
hij RFC 8693 zonder impersonation.

**Rationale:** Een token in de browser is via XSS of een gecompromitteerde extensie uit te lezen. RFC 9700
ontraadt de flows en het tokengebruik die dat mogelijk maken.

**Implications:** Nieuwe authenticatiecode gaat uit van een vertrouwelijke client op de server. Een afwijking
vraagt een ADR.

**Bron:** `N-RFC-9700`, `N-RFC-8693`, `N-ASVS-5.0`

**Toetsing:** Fitness test: `CommonGroundAuthenticationConverterTokenExchangeTest`;
Reviewpunt: [security by design](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#security-by-design)

### P-03 Geen geheimen in code of configuratie

**Statement:** Een geheim (wachtwoord, clientgeheim, sleutel, token) staat nooit in de repository, een image of
een flag-systeem; het komt uit de omgeving waarin NL Portal draait.

**Rationale:** Een geheim in de repository is openbaar zodra de repository dat is, en blijft in de
git-geschiedenis staan nadat het is verwijderd.

**Implications:** Voorbeeldconfiguratie gebruikt placeholders. Een gelekt geheim wordt ingetrokken, niet alleen
verwijderd.

**Bron:** `N-BIO2-1.3` (ISO/IEC 27002:2022 5.17 en 8.4), `N-ASVS-5.0`

**Toetsing:** CI-controle: `secret-scan.yml` (`mise run security:secrets-scan`, gitleaks)

### P-04 Kwetsbaarheden in afhankelijkheden worden bewaakt

**Statement:** Elke afhankelijkheid is herleidbaar (SBOM, geverifieerde checksums) en wordt gescand op bekende
kwetsbaarheden; een Critical of High bevinding wordt geëscaleerd.

**Rationale:** Het grootste deel van de code die NL Portal draait, is van derden. Een kwetsbaarheid daarin is
evengoed een kwetsbaarheid van NL Portal.

**Implications:** Een backend-dependencybump werkt `verification-metadata.xml` bij. Een onbereikbare bevinding
wordt onderbouwd afgewezen (VEX), niet genegeerd.

**Bron:** `N-BIO2-1.3` (ISO/IEC 27002:2022 8.8)

**Toetsing:** CI-controle: `dependency-check.yml` en `frontend-vulnerability-scan.yml`
(`mise run security:backend-vuln-scan`, `mise run security:frontend-vuln-scan`);
CI-controle: Gradle-dependencyverificatie (`mise run security:verify-dependencies`)

### P-05 Alleen de gegevens die nodig zijn

**Statement:** NL Portal verwerkt, logt en geeft door alleen de persoonsgegevens die voor het doel nodig zijn.
BSN, KVK-nummer en naam komen niet in logs, flagcontext of telemetrie.

**Rationale:** De AVG eist minimale gegevensverwerking en gegevensbescherming door ontwerp. Wat niet verwerkt
wordt, kan niet lekken.

**Implications:** Een nieuw veld in een response, log of evaluatiecontext heeft een doel. ADR 0020 is het
precedent: de evaluatiecontext van feature flags bevat alleen een opaque gebruikers-id.

**Bron:** `N-AVG-2016`, `N-BIO2-1.3` (ISO/IEC 27002:2022 5.34)

**Toetsing:** Fitness test: `FeatureContextPrivacyTest`;
Reviewpunt: [privacy en dataminimalisatie](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#privacy-en-dataminimalisatie)

### P-06 Gegevens blijven bij de bron

**Statement:** NL Portal haalt gegevens op bij de bronregistratie en slaat ze niet persistent op; vluchtige state
(sessie, cache) mag.

**Rationale:** Common Ground scheidt gegevens van applicaties. Een kopie raakt uit sync met de bron en vergroot
wat er bij een incident kan lekken.

**Implications:** Een module zonder database is de norm (ADR 0013, verfijnd door ADR 0017). Een cache heeft een
eindige levensduur.

**Bron:** `N-CG-5LAGEN`, `N-AVG-2016`

**Toetsing:** Reviewpunt: [privacy en dataminimalisatie](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#privacy-en-dataminimalisatie)

### P-07 Onbereikbaar wie geen recht heeft, inert waar het niet nodig is

**Statement:** Een functie die een tenant niet afneemt, is voor die tenant onbereikbaar en doet niets: de
permission-flag staat fail-closed uit, er zijn geen clients of geheimen voor geconfigureerd en een module die
geen klant afneemt, verdwijnt uit de codebase.

**Rationale:** Wat uitstaat, kan niet aangevallen worden. NIST SP 800-53 noemt dit *least functionality* (CM-7);
in BIO2 valt het onder configuratiebeheer en hardening. Eén image voor alle klanten maakt het niet mogelijk om
code per klant weg te laten.

**Implications:** Least functionality werkt in vier lagen: product, tenant, koppelingen en geheimen,
afhankelijkheden. De uitwerking staat in het ADR-ticket over least functionality
([#532](https://github.com/pinkroccade/nl-portal/issues/532)). Tot de removability fitness function er is, is de
toetsing een reviewpunt.

**Bron:** `N-BIO2-1.3` (ISO/IEC 27002:2022 8.9), `N-ASVS-5.0`

**Toetsing:** Reviewpunt: [least functionality](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#least-functionality)

### P-08 De kern hangt niet af van een module

**Statement:** De kern en de gedeelde infrastructuur kennen geen functionele module; alleen `app` en `app-dev`
stellen de modules samen, en modules hangen niet cyclisch van elkaar af.

**Rationale:** Een module die uit de build kan zonder de kern te breken, is een module die je kunt uitzetten,
vervangen of verwijderen. Dat is de voorwaarde voor P-07 en voor hergebruik door andere organisaties.

**Implications:** Een nieuwe afhankelijkheid van de kern naar een module is een ontwerpfout, ook als hij
tijdelijk lijkt.

**Bron:** `N-CG-5LAGEN`, `N-BIO2-1.3` (ISO/IEC 27002:2022 8.27)

**Toetsing:** ArchUnit-regel: `ArchitectureRules.coreDoesNotDependOnFeature`;
ArchUnit-regel: `ArchitectureRules.sharedInfraDoesNotDependOnFeature`;
ArchUnit-regel: `ArchitectureRules.nothingDependsOnApp`;
ArchUnit-regel: `ArchitectureRules.cycleFree`

### P-09 Open standaarden: pas toe of leg uit

**Statement:** Waar een standaard van de pas-toe-of-leg-uitlijst van Forum Standaardisatie van toepassing is,
passen we hem toe; waar we dat niet doen, leggen we het uit in een ADR.

**Rationale:** Overheden zijn verplicht deze standaarden te vragen bij aanschaf. Een afwijking zonder uitleg
maakt NL Portal moeilijker af te nemen en te koppelen.

**Implications:** Een keuze voor een protocol, formaat of interface begint bij de lijst. De uitleg bij een
afwijking noemt de standaard en de reden.

**Bron:** `N-FS-PTOLU`, `N-NORA-3.0`

**Toetsing:** Reviewpunt: [open standaarden](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#open-standaarden)

### P-10 REST-API's volgen de NL API Design Rules

**Statement:** Een REST-API die NL Portal aanbiedt, volgt de NLGov REST API Design Rules. Het GraphQL-contract
tussen frontend en backend valt er niet onder; die afwijking wordt uitgelegd volgens P-09.

**Rationale:** De Design Rules staan verplicht op de pas-toe-of-leg-uitlijst en maken API's van verschillende
overheden voorspelbaar voor afnemers.

**Implications:** Een nieuw REST-endpoint wordt aan de regels getoetst. De uitleg voor het GraphQL-contract is
een open punt voor de analyse van epic [#530](https://github.com/pinkroccade/nl-portal/issues/530).

**Bron:** `N-NLAPI-2.2`, `N-FS-PTOLU`

**Toetsing:** Reviewpunt: [open standaarden](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#open-standaarden)

### P-11 Toegankelijk volgens WCAG 2.2 AA

**Statement:** De frontend voldoet aan WCAG 2.2 niveau AA.

**Rationale:** Toegankelijkheid is wettelijk verplicht voor overheidswebsites en staat op de
pas-toe-of-leg-uitlijst. Een burger die de portal niet kan gebruiken, wordt uitgesloten van zijn eigen zaken.

**Implications:** Een nieuw component gebruikt semantische HTML en is met het toetsenbord te bedienen. Lint vangt
een deel; wat lint niet ziet (contrast, focusvolgorde, betekenis) is een reviewpunt.

**Bron:** `N-WCAG-2.2`, `N-FS-PTOLU`

**Toetsing:** Lintregel: `eslint-plugin-jsx-a11y` in `frontend/eslint.config.mjs`;
Reviewpunt: [toegankelijkheid](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#toegankelijkheid)

### P-12 Herkenbaar in Common Ground en GEMMA

**Statement:** Elke bouwsteen van NL Portal is te plaatsen in een laag van het Common Ground 5-lagenmodel en
herkenbaar als bouwblok uit GEMMA. In TOGAF-termen: GEMMA en de vijf lagen leveren de architecture building
blocks, de modules zijn de solution building blocks.

**Rationale:** Een gemeente vergelijkt leveranciers in GEMMA-termen. Een bouwsteen die niet te plaatsen is, is
moeilijk af te nemen en moeilijk te vervangen.

**Implications:** Een nieuwe module krijgt een plek in het architectuurmodel (LikeC4) en in het 5-lagenmodel.

**Bron:** `N-CG-5LAGEN`, `N-GEMMA-2`, `N-NORA-3.0`

**Toetsing:** CI-controle: `mise run likec4:validate`;
Reviewpunt: [Common Ground en GEMMA](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#common-ground-en-gemma)

### P-13 Elke bewering in de documentatie is getest of gegenereerd

**Statement:** Een bewering in de documentatie over de code (een module, een capability, een link, een bron-ID,
een principe) is gegenereerd uit de code of wordt door een controle bewaakt.

**Rationale:** Documentatie die niemand controleert, loopt uit sync met de code; een architect, CISO of afnemer
die erop vertrouwt, beslist dan op verkeerde informatie. NORA vraagt dat de overheid transparant en
verantwoordbaar is.

**Implications:** Een nieuwe soort bewering krijgt een generator of een controle. Een bewering die dat niet kan
krijgen, staat in proza zonder het gezag van een register.

**Bron:** `N-NORA-3.0`

**Toetsing:** CI-controle: `mise run docs:build` (dode links en ankers);
CI-controle: `mise run docs:capabilities-check`;
CI-controle: `mise run compliance:check`

### P-14 Groei mag, vaagheid niet

**Statement:** De structuur van de repository is duidelijk, logisch en herkenbaar: elke map en module heeft één
doel dat uit zijn naam of README blijkt. Groei is geen bezwaar zolang hij volgt uit de principes.

**Rationale:** Een map met een onduidelijke functie vertraagt elke bijdrager en verbergt code die niet meer nodig
is. Omvang is geen doel op zich.

**Implications:** Een nieuwe map of module noemt zijn doel. Een map waarvan niemand het doel kan noemen, is een
kandidaat om op te ruimen.

**Bron:** `N-CG-5LAGEN`, `N-GEMMA-2`

**Toetsing:** ArchUnit-regel: `ArchitectureRules.cycleFree`;
Reviewpunt: [structuur](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#structuur)

### P-15 Elke controle bewaakt een principe

**Statement:** Een controle (test, lintregel, CI-stap, scan) bewaakt een principe uit dit register en staat in
verhouding tot de omvang en complexiteit van wat hij bewaakt.

**Rationale:** Een controle zonder doel kost bij elke build tijd en aandacht, en wordt op den duur genegeerd of
omzeild. BIO2 vraagt maatregelen op basis van risico, niet op volledigheid.

**Implications:** Een nieuwe controle noemt het principe dat hij bewaakt. Een controle waarbij geen principe
past, is een kandidaat om te schrappen, of het principe ontbreekt.

**Bron:** `N-BIO2-1.3`

**Toetsing:** CI-controle: `mise run compliance:check` (elk principe heeft een toetsing);
Reviewpunt: [proportionele controles](https://github.com/pinkroccade/nl-portal/blob/main/CODING_STANDARDS.md#proportionele-controles)
