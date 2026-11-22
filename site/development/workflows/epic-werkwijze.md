# Workflow: een epic bouwen met agents

Een **epic** is een spec die `to-tickets` in sub-tickets met blokkades heeft opgesplitst (label `epic`, zie
`docs/agents/triage-labels.md`). Dit document is de route die `implement-spec` volgt: hoe de
tickets via een integratiebranch naar `main` komen, met kwaliteit en security gecontroleerd vóór de merge naar `main`
en risico's zo vroeg mogelijk zichtbaar.

Ontstaan uit de retro van epic #499 (multi-tenant runtime en BFF). Daar kostten herhaalde reviewbevindingen, een late
CI-poort en serieel mergen de meeste tijd en tokens; de regels hieronder pakken die drie aan.

## 1. Tickets: gedeelde basis, patroonticket, risicoklasse

Bij `to-tickets` legt de grill-sessie drie dingen vast:

- **Gedeelde basis eerst.** Wat meerdere tickets in core nodig hebben (een SPI, een configuratiehaakpunt, een gedeelde
  client) is één eigen ticket dat de rest blokkeert. Daarna wijzigt geen ander ticket diezelfde core-code; een ticket
  dat core-uitbreiding nodig blijkt te hebben, meldt dat aan de orchestrator in plaats van het zelf toe te voegen.
- **Patroonticket.** Bij een reeks gelijksoortige tickets (migraties, modules met dezelfde vorm) is het eerste ticket
  het patroon: volledig gebouwd, gereviewd en gemerged vóór de rest start. De regels die de review oplevert gaan naar
  een **regelbestand** van de epic (zie §5); de volgende tickets lezen dat bestand in plaats van dezelfde bevindingen
  opnieuw te krijgen.
- **Risicoklasse** per ticket, als label:
  - `risico:hoog`: authenticatie, sessies, betalingen, webhooks, core, tenant-isolatie;
  - `risico:laag`: de rest.

## 2. Branches en de CI-poort

- Eén integratiebranch `feat/<kort>-<epic>` vanaf `main`, met een draft-PR naar `main` die de epic en tickets sluit.
- Per ticket een branch en worktree vanaf de integratiebranch.
- **Per ticket een draft-PR naar de integratiebranch**, vanaf de eerste push. De volledige CI (tests, integratietests,
  pitest, Sonar-dekking) draait daar, niet op de laptop. Zet de integratiebranch bij het aanmaken in
  `on.pull_request.branches` van `.github/workflows/ci.yml`, en haal die regel weg vóór de merge naar `main`.
- De bouwer en fixer draaien lokaal alleen de tests van hun modules plus één keer `mise run build` en `mise run lint`.
- De **merger** mergt `--no-ff` zodra review én CI van de ticket-PR groen zijn, lost conflicten op en draait
  `mise run build` en `mise run lint`. De merge-commit volgt commitlint (type van het ticket, kop ≤ 100 tekens).
- Eén merger tegelijk op de integratiebranch. Goedgekeurde tickets bundel je in één mergerun.
- Een ticketbranch merget de tip van de integratiebranch pas vlak vóór zijn eigen merge: na elke merge ontstaan bij de
  andere tickets nieuwe conflicten, dus één keer oplossen is genoeg.

## 3. Route per ticket

| Stap | `risico:laag` | `risico:hoog` |
|---|---|---|
| Bouwen | bouwer (Sonnet, Skill `tdd`) | bouwer (Sonnet, Skill `tdd`) |
| Ronde 1 | één gecombineerde review en security (Opus) | code-review (Sonnet) en security (Opus) parallel |
| Fixes | verse fixer (Sonnet), nooit de bouwer | verse fixer (Sonnet), nooit de bouwer |
| Ronde 2 | korte controle door de orchestrator in de diff | review (Opus als er een securitybevinding te verifiëren is, anders Sonnet) |
| Ronde 3 | escaleren naar de gebruiker | korte controle; daarna escaleren |

Bevindingen:

- **Hoog en midden** los je in het ticket zelf op.
- **Laag** gaat naar één verzamelissue "rework na livegang" van de epic, niet naar een fixer.
- **Bestaande securityfouten** (al vóór de epic in de code): eigen issue. Zolang NL Portal niet in productie draait
  (versie 0.x) als sub-issue van de epic; vanaf 1.0 bij hoog of midden een fix-branch vanaf `main` met eigen PR, en
  de epic neemt de fix mee bij de volgende merge van de tip.

Documentatie (arc42, likec4, gebruikersdocs) bundel je in één docs-ticket aan het eind van de epic, uitgevoerd door
een technical writer. Een ticket werkt alleen een ADR bij als het een besluit wijzigt.

## 4. Agents

- **Taakgrootte:** een agenttaak duurt hooguit ~20 minuten. Een bouwer werkt in stappen, elk met een verse agent:
  stap 1 de kern met de eerste test, stap 2 opstartcontroles, configuratie, schema en de rest.
- **Model:** Sonnet voor uitvoeren (bouwen, fixen, mergen, code-review op criteria); Opus voor security en
  ontwerpkeuzes.
- **Briefing:** wijzers naar issue, regelbestand en eerdere commits, geen kopie van hun inhoud.
- **Voortgang:** elke agent schrijft elke ~20 minuten een regel in `t<N>/progress.md`; de orchestrator vervangt een
  agent zonder voortgang na ~45 minuten.
- **Eindrapport:** branch, laatste commit, groen ja/nee, pad naar het notitiebestand. Details staan in het bestand.

## 5. Orchestratie

- De orchestrator is een **zelfstandige sessie**, niet een kind van een andere sessie: meldingen gaan dan één keer
  naar de gebruiker.
- Meld alleen merges, blokkades en besluiten.
- Zet "address comments" uit op de draft-PR's: Sonar-reacties maken anders de orchestrator wakker zonder actie.
- Draag na elke ~3 merges over aan een verse orchestratorsessie, via een statusbestand.
- Notitiemap buiten de repo, `../nl-portal-<epic>-notes/`:

| Bestand | Inhoud |
|---|---|
| `ORCHESTRATIE.md` | opdracht, afspraken met de gebruiker, stand, genomen besluiten, open besluiten, logboek, tokens per ticket en rol |
| `PROTOCOL.md` | werkafspraken voor subagents: wijzer naar dit document plus wat specifiek is voor de epic |
| regelbestand (bijv. `migraties.md`) | regels uit de review van het patroonticket |
| `t<N>/` | `build.md`, `review-r<k>.md`, `security-r<k>.md`, `fix-r<k>.md`, `progress.md` |

## 6. Parallel werken op één machine

- Hooguit drie zware Gradle-runs tegelijk, nooit twee pitest-runs tegelijk. Lange controles via CI.
- De Gradle-home is gedeeld: `./gradlew --stop` stopt de daemons van alle worktrees. Alleen gebruiken als je eigen
  run aantoonbaar hangt; herhaal daarna een run die door een gestopte daemon faalde.
- Onder parallelle belasting zijn tests met een flagd-provider, vaste poorten of `waitForElementToBeRemoved` flaky.
  Faalt zo'n test zonder dat je ticket het gebied raakt, draai hem los opnieuw.
- Voeg je een Gradle-plugin of -configuratie toe (bijv. `java-test-fixtures`), controleer dan de dependencyverificatie
  met een verse Gradle-home (`./gradlew -g <lege map> --write-verification-metadata sha256 ...`): CI resolvet pom's die
  de lokale cache verbergt.
- Ruim containers van Testcontainers en compose op (`docker compose -p <eigen projectnaam> down -v`).

## 7. Afronden

1. Alle tickets gemerged, docs-ticket klaar, CI-regel voor de integratiebranch weg uit `ci.yml`.
2. `code-review` op de hele integratiebranch; één fixer voor de bevindingen.
3. Draft-PR naar `main` ready for review; worktrees opruimen met `mise run worktree:opruimen ../<map>`.
4. Retro: tokens per ticket en rol, rondes per ticket, wat in het regelbestand terechtkwam en een automatische check
   had kunnen zijn.
