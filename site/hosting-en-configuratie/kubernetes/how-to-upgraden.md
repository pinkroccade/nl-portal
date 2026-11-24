# How-to: upgraden naar een nieuwe chartversie

*Diátaxis: how-to.*

1. Lees de wijzigingen van de nieuwe chartversie in de [CHANGELOG](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/CHANGELOG.md) en de appversie in de release-notes.
2. Verifieer de handtekening van de nieuwe versie ([de handtekening verifiëren](./how-to-handtekening-verifieren.md)).
3. Werk `appVersion`/`image.tag` bij (de chart gebruikt standaard de `appVersion` als tag; zet voor een vaste uitrol een `image.digest`).
4. Valideer je values tegen het schema van de nieuwe versie:

   ```shell
   helm pull oci://ghcr.io/pinkroccade/charts/nl-portal --version <nieuw> --untar
   helm lint --strict ./nl-portal -f values.yaml
   ```

5. Draai `helm upgrade` (of laat Argo CD syncen). De Deployments gebruiken een rolling update; de readiness-probe op de management-poort bepaalt wanneer een pod
   verkeer krijgt.
6. Controleer `GET /actuator/modulestatus` op de beheerpoort als je `backend.beheer` hebt aangezet.

Een breaking wijziging in de values is een major-bump van de chart, met een upgrade-notitie in de changelog. Een wijziging van de tenantbestanden, flags of
omgevingsconfiguratie herstart de pods automatisch (checksum-annotaties). Na een rotatie van een Secret verhoog je `backend.secretVersion`
([Secrets uit Vault via ESO](./how-to-secrets-eso.md#na-een-rotatie)).
