# Een release maken

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
