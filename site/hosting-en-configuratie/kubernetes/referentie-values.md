# Referentie: values

*Diátaxis: referentie.* Alle values van de chart `nl-portal`.

Deze tabel is niet met de hand geschreven: ze wordt door [helm-docs](https://github.com/norwoodj/helm-docs) gegenereerd uit de commentaren in
[`values.yaml`](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/values.yaml) en hieronder uit de
[chart-README](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/README.md) ingevoegd. Er is dus één bron. De volledige `values.yaml` staat
ook bij elke chartversie (`helm show values oci://ghcr.io/pinkroccade/charts/nl-portal --version <versie>`).

`values.schema.json` valideert de values: onbekende sleutels en verkeerde types laten het renderen falen. Van de globals worden alleen `global.imageRegistry` en
`global.imagePullSecrets` ondersteund. Gebruik `backend.extraEnv` en `frontend.extraEnv` (EnvVar-objecten) voor wat nog niet gemodelleerd is.

<!--@include: ../../../helm/nl-portal/README.md#values-->
