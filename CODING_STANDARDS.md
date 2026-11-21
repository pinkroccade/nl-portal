# Coding standards

Oordeelregels die de reviewer toepast (`code-review`). Wat een tool kan afdwingen staat niet hier maar in de
build en CI: formattering, copyright-headers, links, `mise run workflows:lint` (actionlint),
`mise run mise:check-shell` en de architectuurtests. Een regel hier is een oordeel dat geen controle kan
overnemen; ontstaat er een controle voor, dan verhuist de regel daarheen.

## Een workflow of een taak die in CI draait

- **Een workflow wijzigen of toevoegen:** de PR toont een groene handmatige run op de branch
  (`gh workflow run <naam> --ref <branch>`), met de link in de PR-body. actionlint vangt syntaxis- en
  expressiefouten, geen runtimefouten. De nachtelijke perf-workflow faalde twee keer op de runner terwijl hij
  lokaal slaagde: rechten op een gemounte map en een backend die voor Keycloak opstartte.
- **Verschil tussen Windows en Linux:** een taak of script dat lokaal op Windows en in CI op Linux draait, is op
  beide gedraaid. Is dat niet gelukt, dan staat in de PR wat niet geverifieerd is ("Niet geverifieerd: ...").
- **Oorzaak niet bewezen:** een oorzaak die alleen uit symptomen is afgeleid, staat in de PR als aanname, met
  wat er is gedaan om hem bij herhaling zichtbaar te maken (bijvoorbeeld een log in de foutmelding).

## Een gedeelde omgeving

- Een taak die een compose-stack, poort of containernaam bezet, draait onder een eigen projectnaam (`-p`), zodat
  hij de stack van een andere taak of sessie niet verwijdert of vervangt. Een andere sessie haalde zo eerder een
  lopende meting de stack onder de voeten weg.

## Metingen en baselines

- Een meting noemt de machine of omgeving, het aantal runs en de datum. Cijfers van een laptop en van een
  CI-runner vergelijk je niet zonder voorbehoud: een gehoste runner was ongeveer 25 tot 27% trager.
- Een run met veel fouten is mislukt en geen meting: hij telt niet mee in een mediaan of baseline.
- Een baseline wijzigt alleen via een PR die de reden noemt.
- Een belastingtest seedt minstens zoveel gebruikers als gelijktijdige gebruikers. Gedeelde accounts vertekenen
  de uitkomst: `getZaak` kostte bij 40 gebruikers 4,5 s op 10 accounts tegen 2,6 s op 50.

## Pull requests

- `Closes #n` alleen als de acceptatiecriteria gehaald zijn. Is er iets open gebleven, dan staat `Refs #n` in de
  body en zegt de PR wat nog moet.

## Principes zonder automatische toetsing

Reviewpunten bij de [architectuurprincipes](site/architectuur/principes.md). Elk punt toetst het deel van een
principe dat geen controle kan zien; noem in de review het principe-ID als een wijziging het raakt.

### Security by design

- (P-01, P-02) Een wijziging aan authenticatie, autorisatie of gegevensverwerking noemt de ASVS-eisen die ze
  raakt, en een nieuwe eis die we uitvoeren krijgt een rij in de traceerbaarheidsmatrix. Een token of geheim dat
  naar de browser gaat, vraagt een ADR. OAuth-code volgt het NL GOV Assurance profile for OAuth 2.0; RFC 9700
  geldt alleen waar het profiel niets specifiekers zegt.

### Privacy en dataminimalisatie

- (P-05, P-06) Elk nieuw persoonsgegeven in een response, log, trace, flagcontext of cache heeft een doel dat de
  PR noemt. BSN, KVK-nummer en naam staan nooit in logs of telemetrie, en niets wordt persistent opgeslagen dat
  bij een bronregistratie hoort.

### Least functionality

- (P-07) Een nieuwe module of koppeling staat voor een tenant pas aan via een fail-closed permission-flag,
  heeft geen client of geheim zonder dat recht en haalt geen afhankelijkheid binnen die de kern meesleept.

### Open standaarden

- (P-09, P-10) Een keuze voor een protocol, formaat of interface noemt de standaarden van de
  pas-toe-of-leg-uitlijst die erop van toepassing zijn (niet de hele lijst), of legt in een ADR uit waarom een
  standaard niet wordt toegepast. Een nieuwere IETF- of W3C-standaard die nog niet op de lijst staat, mag met
  uitleg die beide noemt. Een nieuw REST-endpoint volgt de NLGov REST API Design Rules.

### Toegankelijkheid

- (P-11) Een nieuw of gewijzigd component is met het toetsenbord te bedienen, heeft voldoende contrast en een
  logische focusvolgorde, en gebruikt semantische HTML; de PR zegt hoe dat is nagegaan.

### Common Ground en GEMMA

- (P-12) Een nieuwe module of container heeft een plek in het LikeC4-model en in het 5-lagenmodel, en de PR
  noemt het GEMMA-bouwblok waar hij bij hoort.

### Structuur

- (P-14) Een nieuwe map of module noemt zijn doel in zijn naam of een README, en overlapt niet met een bestaande.

### Proportionele controles

- (P-15) Een nieuwe controle (test, lintregel, CI-stap, scan) noemt het principe dat hij bewaakt en staat in
  verhouding tot wat hij bewaakt; past er geen principe bij, dan hoort hij er niet of ontbreekt het principe.
