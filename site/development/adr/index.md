# Architecture Decision Records

Elke map hier is één architectuurbeslissing (ADR), genummerd oplopend, nooit hernummerd of
verwijderd — een verworpen beslissing krijgt status `Afgewezen`/`Vervangen`, niet een verwijderd
bestand. Gebruik `0001-record-architecture-decisions.md` als sjabloon voor een nieuwe ADR.

## Wanneer een ADR schrijven

- Een structurele keuze die **moeilijk terug te draaien** is (bijv. "één Gradle-module per
  ZGW-bron", "GraphQL als extern contract van de backend").
- Een keuze die **tegen een eerder voorstel ingaat** uit een architectuurreview — zie de
  `improve-codebase-architecture`-skill: als een gebruiker een verdiepingskandidaat afwijst om een
  onderbouwde reden, hoort die reden hier, zodat een latere review niet hetzelfde kandidaat opnieuw
  voorstelt.
- Een keuze waar een nieuwe bijdrager **anders aan zou twijfelen** bij het lezen van de code.

Geen ADR nodig voor: een bugfix, een naamgevingskeuze binnen één module, iets dat al in
[`site/architectuur/arc42`](../../architectuur/arc42/index.md) hoofdstuk 4 (oplossingsstrategie) staat beschreven zonder dat er een
alternatief serieus is overwogen.

## Voorgesteld

- [ADR 0023: architectuurprincipes en toetsing](./0023-architectuurprincipes-en-toetsing.md): principeregister
  in TOGAF-vorm, elk principe met een bron en een toetsing; wacht op beoordeling door de architect.
- [ADR 0026: least functionality in een gedeelde runtime](./0026-least-functionality-in-een-gedeelde-runtime.md):
  vier lagen, één centrale gate (deny by default), introspectie uit, removability fitness function en de
  eisen aan #521; wacht op beoordeling door de architect.

## Recent geaccepteerd

- [ADR 0025: Helm-chart in de app-repo als generieke child-chart](./0025-helm-chart-in-app-repo-als-generieke-child-chart.md):
  chart in de app-repo, OCI op ghcr met cosign, secret-contract met `existingSecret`, contract met de base-chart en eigen SemVer (2026-11-25, epic #191).
- [ADR 0019: trunk-based continuous delivery](./0019-trunk-based-continuous-delivery.md):
  `main` als enige lijn, één build per commit, promotie op digest; vervangt de rc-stap uit
  [ADR 0004](./0004-changelog-release-proces.md) (2026-11-25, epic #604).
- [ADR 0024: eigen inlogpagina en afdwinging van inlogmiddelen](./0024-eigen-inlogpagina-en-afdwinging-van-inlogmiddelen.md):
  eigen pagina `/inloggen`, Keycloak als ID-broker met een realm per gemeente, en afdwinging in lagen (2026-11-25, epic #485).

## Eerder geaccepteerd (2026-11-21)

- [ADR 0022: backend-for-frontend als authenticatiepatroon](./0022-backend-for-frontend-als-authenticatiepatroon.md):
  de backend is per tenant de vertrouwelijke OIDC-client; tokens blijven op de server.
- [ADR 0021: gedeelde multi-tenant runtime met cellen](./0021-gedeelde-multi-tenant-runtime-met-cellen.md):
  gedeelde runtime met logische tenantscheiding, ingedeeld in cellen; met aanvullingen bij acceptatie.
- [ADR 0016: stateless configuratie en thema via bestanden](./0016-stateless-configuratie-en-thema-via-bestanden.md):
  geaccepteerd; punt 2 vervangen door ADR 0021, punt 6 aangepast.
- ADR 0020 en ADR 0002 hebben een aanvulling gekregen (moduleflag als permission-flag; de diepe client in
  `core.koppeling` via het tenantregister).

Het besluitenlog van de grilling-sessie staat in
[`fase0-besluiten-multitenancy-bff.md`](../research/fase0-besluiten-multitenancy-bff.md).

## Status

Nog geen beslissingen vastgelegd op het moment van schrijven (2026-10-20) — dit is de eerste keer
dat dit project een ADR-log heeft. Zie [`site/architectuur/arc42/09-architectuurbeslissingen.md`](../../architectuur/arc42/09-architectuurbeslissingen.md).
