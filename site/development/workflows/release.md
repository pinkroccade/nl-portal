# Een release maken

> **Overgang naar ADR 0019.** Elke merge op `main` bouwt sinds #606 via `build-once.yml` (zie hieronder). De RC- en releasestappen
> verderop gelden tot het opruimen (#609) ze vervangt. Promotie op digest (`promote.yml`, #607) staat al beschikbaar.

## Build per commit (`build-once.yml`)

Elke merge op `main` bouwt backend en frontend één keer en pusht de onveranderlijke tag `<versie>-<sha>` (`<versie>` uit `version.txt`,
`<sha>` de eerste 12 tekens van de commit). Volgorde en poorten:

1. **Stage (parallel):** backend `gradlew build` (compileren, unit- en integratietests) en frontend build en test.
2. **Smoke:** `_smoke-stack.yml` bouwt de images uit de stage-bundels van deze run, start de quickstart-compose en draait de BFF-login met GraphQL.
3. **Images (parallel):** bouwen uit dezelfde stage-bundels, pushen, ondertekenen (cosign, keyless), image-SBOM genereren en attesteren.
4. **Summary:** de digest per image staat in de job summary van de run en in het artefact `build-digests` (`build-digests.json`).

Een falende poort laat alle volgende jobs vervallen: er wordt niets gepusht en er ontstaat geen tag. De tests en de smoke lopen dus
vóór de push; de smoke wacht op beide stage-jobs, dat kost de smoke-duur extra bovenop de langzaamste stage.

Een commit die alleen `site/**`, `docs/**` of `*.md` raakt, start de workflow niet (`paths-ignore`): er is niets nieuws om uit te rollen,
en latere bot-commits op `CHANGELOG.md` (#608) veroorzaken zo geen build. Promoveren op commit kiest dus de nieuwste
build, niet per se de nieuwste commit.

Proefrun op een branch: `gh workflow run build-once.yml --ref <branch>` draait alleen de poorten (`publish=false`); met
`-f publish=true` pusht en ondertekent hij ook.

## Promotie op digest (`promote.yml`)

Een handmatige `workflow_dispatch` vanaf `main`, achter de `release`-environment (goedkeuring). Er wordt niet gebouwd of getest: de
workflow retagt de digests van een geslaagde `build-once.yml`-run.

```shell
gh workflow run promote.yml                                  # nieuwste groene build op main
gh workflow run promote.yml -f build=<commit-of-build-tag>   # een oudere groene build
gh workflow run promote.yml -f version=1.2.3                 # versie expliciet (anders uit de build-tag)
```

1. **Kies de build:** de geslaagde push-run van `build-once.yml` op `main` (artefact `build-digests`). Een commit zonder zo'n run is niet te promoveren.
2. **Controleer:** handtekening en SBOM-attestatie van build-once moeten kloppen (`cosign verify`, `cosign verify-attestation`).
3. **Retag:** `x.y.z`, `x.y`, `x` en `latest` op dezelfde digest, per image. `x.y.z` is onveranderlijk: een andere digest voor een bestaande
   versie breekt de promotie. `x.y`, `x` en `latest` schuiven alleen vooruit (`scripts/promote-tags.mjs`): een oudere patch of minor laat
   ze staan, dus `latest` gaat nooit terug.
4. **Onderteken en attesteer:** nieuwe cosign-handtekening (identiteit `promote.yml`) en SBOM-attestatie op de digest.
5. **GitHub Release:** tag `x.y.z` op de gebouwde commit, notes uit `CHANGELOG.md` (of door GitHub gegenereerd zolang de changelog-bot #608 ontbreekt),
   met de image-SBOM's en, als het artefact niet verlopen is, het backend-SBOM- en licentierapport van de build.

Opnieuw draaien met dezelfde build is veilig. Het artefact `build-digests` verloopt na de bewaartermijn van GitHub-artefacten (standaard 90 dagen);
een oudere build is dan niet via deze workflow te promoveren.

## Oud model: RC en release

Een release maak je in twee stappen: een release-candidate (RC) snijden en daarna de release publiceren. De RC wordt gebouwd en
getest; de release bouwt de images opnieuw (zonder tests) en publiceert ze achter een handmatige goedkeuring. Achtergrond:
[ADR 0004](../adr/0004-changelog-release-proces.md); het branchmodel staat in `CONTRIBUTING.md`.

Er is geen tag-trigger: een tag pushen doet niets. De tag `x.y.z` wordt door de release-workflow zelf gezet.

## Stappen

1. **Snijd de RC.** Start vanaf `main` de workflow `release-cut.yml`:

   ```shell
   gh workflow run release-cut.yml --ref main -f version=0.1.0
   ```

   Laat `version` leeg om de versie af te leiden uit de Conventional Commits sinds de laatste tag (`feat` geeft minor, `fix`,
   `refactor` en `perf` patch, een breaking change major). Zet `-f start_rc=false` als je de RC-bouw zelf wilt starten.
   De workflow maakt `rc/<versie>` met `version.txt` en `CHANGELOG.md`, pusht die en start de RC-bouw.
2. **Controleer de RC.** `build-and-publish-release-candidate.yml` bouwt `<versie>-RC-1-SNAPSHOT`, draait de unit-tests en
   publiceert gesigneerde images. Lees de `CHANGELOG.md`-sectie na op de branch; pas die zo nodig aan met een commit op `rc/<versie>`
   en start de RC opnieuw (`-f rc_number=2`).
3. **Publiceer de release.**

   ```shell
   gh workflow run build-and-publish-release.yml --ref rc/<versie>
   ```

   Keur daarna in de Actions-UI de `release`-environment goed. De workflow publiceert de tags `<versie>`, `x.y`, `x` en `latest`
   (backend en frontend), zet de branch `release/<major>.x` en de tag `<versie>`, maakt de GitHub Release met SBOM's en
   verwijdert `rc/<versie>`.

## Lokaal snijden

`mise run release:cut-rc -- --version 0.1.0` (of zonder `--version`) doet stap 1 lokaal: het maakt de branch en commit, maar pusht
niet. Push zelf en start de RC-workflow met `gh workflow run build-and-publish-release-candidate.yml --ref rc/<versie> -f rc_number=1`.

## Let op

- De release slaat de tests over en vertrouwt op een groene RC van dezelfde commit. Sla de RC niet over.
- Tags `x.y.z`, `x.y`, `x`, en `latest` en de branch `release/<major>.x` kun je niet terugdraaien. Controleer de RC en de
  changelog daarom vóór stap 3.
- Een release-candidate publiceert geen `latest`. Wil je alleen deployable images beproeven, dispatch dan
  `build-and-publish-snapshot.yml` op een branch: dat geeft één tag `<versie>-<branch>-<tijd>-<sha>-SNAPSHOT` zonder goedkeuring.
- Het Helm-chart gebruikt `appVersion` (gelijk aan `version.txt`) als standaard image-tag. Die tag bestaat pas na stap 3.
- De GitHub App (`NL_PORTAL_APP_*`) moet branches mogen pushen; `release-cut.yml` en de release gebruiken dezelfde App.

## Uitbreiden

Deze handmatige stappen zijn de kleine variant. Een volgende stap is de release automatisch laten starten zodra de RC groen is, met
de goedkeuring als enige handmatige stap.
