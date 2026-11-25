# How-to: een release maken

*Diátaxis: how-to.* Promoveer een geteste build tot release: tags `x.y.z`, `x.y`, `x` en `latest`, handtekening, SBOM-attestatie en GitHub Release.
Hoe het in elkaar zit staat in de [uitleg](./release-uitleg.md); alle namen, invoer en tags in de [referentie](./release-referentie.md).

Je bouwt niets: elke merge op `main` heeft al een geteste build opgeleverd. Een release kiest een van die builds.

## Voor je begint

- Je mag `promote.yml` starten en de `release`-environment goedkeuren (of een andere goedkeurder is bereikbaar).
- `gh` is ingelogd op de repository `pinkroccade/nl-portal`.

## Stappen

1. **Controleer de build die je wilt promoveren.** De nieuwste groene build op `main`:

   ```shell
   gh run list --workflow build-once.yml --branch main --event push --status success --limit 5
   ```

   De bovenste regel is de standaardkeuze. Open die run: de job summary toont de build-tag en de digest per image. Staat er geen
   groene run voor de commit die je zoekt, dan is die commit niet te promoveren.
2. **Kies de versie.** Lees de vorige release (`gh release list --limit 3`) en bepaal de volgende `x.y.z`. Zolang de versie nog uit
   `version.txt` komt, geef je haar altijd expliciet mee.
3. **Start de promotie** vanaf `main`:

   ```shell
   gh workflow run promote.yml --ref main -f version=0.1.0
   ```

   Een bepaalde build in plaats van de nieuwste: voeg `-f build=<commit-sha of build-tag>` toe.
4. **Keur de `release`-environment goed.** Open de run (`gh run list --workflow promote.yml --limit 1`), kies Review deployments en keur goed.
   Pas daarna retagt, ondertekent en publiceert de workflow.
5. **Controleer het resultaat.** Wacht tot de run groen is (`gh run watch`) en controleer:

   ```shell
   gh release view 0.1.0
   docker buildx imagetools inspect ghcr.io/pinkroccade/nl-portal-backend:0.1.0 --format '{{.Manifest.Digest}}'
   cosign verify ghcr.io/pinkroccade/nl-portal-backend:0.1.0 \
     --certificate-identity https://github.com/pinkroccade/nl-portal/.github/workflows/promote.yml@refs/heads/main \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com
   ```

   De digest moet gelijk zijn aan die in de job summary van de build. Herhaal dit voor `nl-portal-frontend`.

## Een oudere build promoveren

Gebruik stap 3 met `-f build=<commit-sha of build-tag>` en de versie die de release moet krijgen. `x.y`, `x` en `latest` schuiven alleen
vooruit: promoveer je een oudere patch, dan blijven die tags staan en krijgt alleen `x.y.z` (en eventueel `x.y`) de nieuwe digest.
De GitHub Release krijgt dan ook niet de vlag Latest.

## Als het misgaat

- **De run faalt vóór het retaggen** (geen groene build gevonden, handtekening klopt niet): er is niets veranderd. Los de oorzaak op en start opnieuw.
- **De run faalt halverwege:** start dezelfde promotie opnieuw. Dat is veilig: dezelfde digest krijgt dezelfde tags, en een bestaande Release wordt overgeslagen.
- **"x.y.z is onveranderlijk":** die versie staat al op een andere digest. Kies een nieuwe versie; een gepubliceerde `x.y.z` is niet te verplaatsen.
- **Een slechte release terugdraaien** is geen pijplijnstap: zet de uitrol (ArgoCD) terug naar de vorige `x.y.z`. `latest` schuift nooit terug.
