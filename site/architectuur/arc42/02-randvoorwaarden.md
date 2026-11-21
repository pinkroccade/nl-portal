# 2. Randvoorwaarden

## 2.1 Technische randvoorwaarden

| Randvoorwaarde | Bron |
|---|---|
| Backend in Kotlin op Spring Boot, Gradle multi-module build | `backend/settings.gradle.kts`, `backend/build.gradle.kts` |
| Frontend in React/TypeScript, pnpm-workspace, Vite | `frontend/pnpm-workspace.yaml`, `frontend/package.json` |
| Moet integreren met de VNG ZGW API-standaard (Zaken, Catalogi, Documenten, Besluiten, Klantinteracties, Objecten) | `site/architectuur` |
| Authenticatie via DigiD (burgers) en eHerkenning (ondernemers), gefedereerd via een OIDC-broker (Keycloak in de huidige stack) | `site/architectuur/authentication-en-authorization.md` |
| Monorepo: backend, frontend, docker-compose en documentatie releasen op één gedeelde versie | `README.md`, `CONTRIBUTING.md` |
| Containerimages `nl-portal-backend`/`nl-portal-frontend`, Helm-charts in apart repository | `docker-compose/README.md`, `README.md` |

## 2.2 Organisatorische randvoorwaarden

- **Open source, EUPL 1.2.** Iedere overheidsorganisatie mag het vrij gebruiken en wijzigen; dit
  beperkt het gebruik van niet-EUPL-compatibele afhankelijkheden en sluit organisatiespecifieke
  hardcoded aannames uit (zie `LICENSE.MD`).
  - **Let op**: de aard van de licentie is hiermee geverifieerd, maar een uitputtende
    license-compatibiliteitscheck van alle transitieve dependencies is niet uitgevoerd als
    onderdeel van deze review. Deliverable 5 (SBOM) is de aangewezen plek om dat structureel te
    borgen (`dependency-license-report`-plugin is al aanwezig in `backend/settings.gradle.kts`).
- **Issue-first workflow.** Elke PR (behalve typo's/docs/dependency-bumps) heeft een gekoppeld issue
  in een apart issues-repository; grotere features gaan via een RFC (`CONTRIBUTING.md`).
- **Spring-achtig branchmodel** met `main`, `release/<major>.x` en `rc/*`; squash-merge met
  Conventional Commit-titel (`CONTRIBUTING.md`).
- **Twee onderhouden major-lijnen tegelijk.** Een fix moet mogelijk naar twee releaselijnen
  propageren — dit is relevant voor hoofdstuk 9 (architectuurbeslissingen die backports
  bemoeilijken, zijn een reëel risico, zie hoofdstuk 11).

## 2.3 Normatieve randvoorwaarden

NL Portal verwerkt overheidsidentiteit en BRP-gegevens. De normen waaraan het zich toetst, staan met versie en
raadpleegdatum in de [normatieve bronnen](../../compliance/normatieve-bronnen.md); het
[principeregister](../principes.md) vertaalt ze naar toetsbare principes.

| Randvoorwaarde | Bron-ID | Uitgewerkt in |
|---|---|---|
| **BIO2** (Baseline Informatiebeveiliging Overheid 2, op basis van ISO/IEC 27002:2022) | `N-BIO2-1.3` | P-01, P-03, P-04, P-05, P-07, P-08, P-15 |
| **OWASP ASVS 5.0** level 2 als basis | `N-ASVS-5.0` | P-01, P-02, P-03, P-07 |
| **AVG**: minimale gegevensverwerking en gegevensbescherming door ontwerp (art. 5 en 25) | `N-AVG-2016` | P-05, P-06 |
| **Open standaarden**: de pas-toe-of-leg-uitlijst van Forum Standaardisatie, waaronder de NLGov REST API Design Rules en WCAG 2.2 | `N-FS-PTOLU`, `N-NLAPI-2.2`, `N-WCAG-2.2` | P-09, P-10, P-11 |
| **Referentiearchitecturen**: NORA, GEMMA en het Common Ground 5-lagenmodel | `N-NORA-3.0`, `N-GEMMA-2`, `N-CG-5LAGEN` | P-06, P-08, P-12, P-13, P-14 |

Een BIO2-toetsing per control is daarmee nog niet gedaan: het register legt vast welke controls de principes
dragen, niet dat elke control is getoetst. Die toetsing hoort bij de analyse van epic
[#530](https://github.com/pinkroccade/nl-portal/issues/530).
