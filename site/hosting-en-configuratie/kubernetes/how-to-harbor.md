# How-to: de chart en images via Harbor gebruiken

*Diátaxis: how-to.* Voor een cluster dat alleen uit een eigen registry (Harbor) haalt.

Verifieer eerst de handtekening ([de handtekening verifiëren](./how-to-handtekening-verifieren.md)) en kopieer daarna chart en images naar een Harbor-project, met
behoud van de handtekeningen (`crane copy` en `cosign copy` nemen de digest mee):

```shell
crane copy ghcr.io/pinkroccade/charts/nl-portal:0.1.0 harbor.example.nl/nl-portal/charts/nl-portal:0.1.0
crane copy ghcr.io/pinkroccade/nl-portal-backend:0.1.0 harbor.example.nl/nl-portal/nl-portal-backend:0.1.0
crane copy ghcr.io/pinkroccade/nl-portal-frontend:0.1.0 harbor.example.nl/nl-portal/nl-portal-frontend:0.1.0
```

Wijs de chart daarna naar Harbor met `global.imageRegistry` en `global.imagePullSecrets` (zie de [values-referentie](./referentie-values.md)):

```yaml
global:
  imageRegistry: harbor.example.nl
  imagePullSecrets:
    - harbor-pull
```

`global.imageRegistry` vervangt de registry van elke image; de repository-naam blijft `pinkroccade/nl-portal-backend` en `-frontend`. Kopieer de images dus onder die
repository-namen in Harbor, of zet `backend.image.repository` en `frontend.image.repository` op het pad dat je in Harbor gebruikt.

Een Harbor-proxy-cache naar `ghcr.io` kan ook; dan hoef je niet te kopiëren.
