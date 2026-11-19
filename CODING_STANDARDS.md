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
