# 23. Architectuurprincipes en toetsing

## Status

Voorgesteld (#531, onderdeel van epic [#530](https://github.com/pinkroccade/nl-portal/issues/530)). De architect
zet deze ADR op *Geaccepteerd* na beoordeling, en weegt daarbij het CISO-perspectief mee: security by design,
privacy by design, dataminimalisatie, overheidsstandaarden en BIO2.

## Context

Vóór de productiegang en de externe pentest willen we de codebase, de structuur, de architectuur en de
documentatie toetsen aan harde principes. Die bestonden tot nu toe alleen in delen: Common Ground, ASVS en enkele
RFC's staan in de [normatieve bronnen](../../compliance/normatieve-bronnen.md), de kwaliteitsdoelen in arc42
hoofdstuk 1 waren een voorstel en de BIO stond in hoofdstuk 2 op "te bevestigen". NORA, Forum Standaardisatie,
de NL API Design Rules en WCAG ontbraken.

Een principe zonder toetsing is een voornemen: niemand merkt het als de code ervan wegdrijft. Een controle zonder
principe is het omgekeerde: hij kost bij elke build tijd zonder dat iemand weet waarom hij er is.

## Beslissing

1. **Eén principeregister** in [`site/architectuur/principes.md`](../../architectuur/principes.md), met per
   principe een vast ID (`P-<nn>`) en de TOGAF-velden *Statement*, *Rationale* en *Implications*, aangevuld met
   *Bron* en *Toetsing*. arc42 blijft de documentstructuur; het register vervangt de voorlopige kwaliteitsdoelen
   in hoofdstuk 1 en de open normatieve randvoorwaarde in hoofdstuk 2.
2. **Elk principe heeft een bron**: minstens één ID uit de normatieve bronnen. Een principe dat we nergens aan
   kunnen ophangen, is een voorkeur, geen principe.
3. **Elk principe heeft een toetsing** van een bekende soort: ArchUnit-regel, fitness test, CI-controle, lintregel
   of reviewpunt. Een principe zonder automatische toetsing krijgt een expliciet reviewpunt in
   `CODING_STANDARDS.md`, nooit niets. Ontstaat er later een controle, dan verhuist het punt daarheen.
4. **`compliance:check` dwingt dit af.** Het faalt op een principe zonder bron of toetsing, op een toetsing zonder
   bekende soort of zonder verwijzing, op een verwijzing naar een testklasse, mise-taak, workflow, bestand of
   `CODING_STANDARDS.md`-anker dat niet bestaat, op een reviewpunt zonder verwijzing naar `CODING_STANDARDS.md`,
   op een principekop zonder geldig ID, een dubbel principe of veld, een ontbrekend register en op een
   principe-ID dat ergens genoemd wordt maar niet in het register staat.
5. **Tweede dekkingspad voor bronnen.** Een normatieve bron zonder *Open* matrixrij telt als gedekt als een
   principe met een geldige toetsing ernaar verwijst, naast de bestaande traceerbaarheidsmatrix op eisniveau. Zo
   kunnen we een brede bron zoals GEMMA, NORA of WCAG opnemen voordat er een controle per eis bestaat, zonder een
   status *Getoetst* te claimen die niet is waargemaakt. Een bron met een *Open* rij heeft een bekend gat en wordt
   alleen door de matrix gedekt; `compliance:check` meldt welke bronnen alleen via een principe gedekt zijn.
6. **Thema's.** Het register werkt negen thema's uit: security by design (ASVS 5.0 L2), privacy by design en
   dataminimalisatie (AVG art. 25), BIO2, least functionality met een modulaire kern, open standaarden (Forum
   Standaardisatie, NL API Design Rules, IETF, W3C/WCAG 2.2), Common Ground en GEMMA, docs as code, een
   duidelijke en herkenbare structuur, en proportionele controles.
7. **TOGAF-terminologie.** GEMMA en de vijf lagen van Common Ground leveren de architecture building blocks, de
   modules zijn de solution building blocks, de informatiebouwstenen komen uit `GLOSSARY.md` en architecture
   patterns verwijzen naar de ADR's.

## Overwogen alternatieven

- **Principes zonder afgedwongen toetsing**, zoals in veel architectuurdocumenten. Afgewezen: precies dat maakte
  dat de kwaliteitsdoelen een voorstel bleven.
- **Alleen de traceerbaarheidsmatrix als dekkingspad.** Afgewezen: een bron als WCAG of GEMMA zou dan pas in het
  register kunnen als er een controle per eis is, of met een status die niet klopt.
- **Eén tabel in plaats van een sectie per principe.** Afgewezen: Rationale en Implications worden dan cellen van
  honderden tekens, onleesbaar in review.

## Consequenties

- Een ADR, ArchUnit-`.because()`-tekst of review kan naar een principe-ID verwijzen; `compliance:check` vangt een
  verkeerd ID.
- Een reviewpunt is zwakker dan een controle. Het register maakt zichtbaar welke principes alleen op review
  leunen; dat is de werkvoorraad voor fase 1 en 2 van epic #530.
- Een nieuwe controle moet een principe bewaken (P-15). Dat geeft de reviewer een grond om een controle zonder doel
  af te wijzen.
- Het register is een levend document: een principe wijzigen of toevoegen gaat via een PR die de architect
  beoordeelt. Een principe intrekken krijgt een vermelding in het register, geen stille verwijdering.
- Least functionality (P-07) wordt uitgewerkt in een eigen ADR ([#532](https://github.com/pinkroccade/nl-portal/issues/532));
  tot de removability fitness function er is, is de toetsing een reviewpunt.
