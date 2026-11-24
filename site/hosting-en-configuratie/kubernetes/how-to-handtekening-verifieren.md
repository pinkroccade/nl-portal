# How-to: de cosign-handtekening verifiëren

*Diátaxis: how-to.* Controleer dat een chart van PinkRoccade komt voor je hem uitrolt.

De chart wordt door de workflow `publish-helm-chart.yml` gesigneerd met cosign, keyless via GitHub OIDC. Er is dus geen sleutel om te beheren: je
controleert dat de handtekening is gezet door die workflow, op `main`, een `release/*.x`-branch of een chart-tag.

```shell
cosign verify ghcr.io/pinkroccade/charts/nl-portal:0.1.0 \
  --certificate-identity-regexp '^https://github.com/pinkroccade/nl-portal/.github/workflows/publish-helm-chart.yml@refs/(heads/(main|release/.+.x)|tags/chart-nl-portal-.+)
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Slaagt het commando, dan is de chart gesigneerd door een run van die workflow op een tag `chart-nl-portal-*`. Faalt het, rol de chart dan niet uit.

- Elke GitHub Release van de chart bevat dit commando al, met de digest en de exacte identity van die run.
- Verifieer op de digest in plaats van op de tag als je de chart vastzet: `ghcr.io/pinkroccade/charts/nl-portal@sha256:...`.
- Kopieer je de chart naar je eigen registry, dan neemt `cosign copy` de handtekening mee; zie [via Harbor](./how-to-harbor.md).
 \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Slaagt het commando, dan is de chart gesigneerd door een run van die workflow op een tag `chart-nl-portal-*`. Faalt het, rol de chart dan niet uit.

- Verifieer op de digest in plaats van op de tag als je de chart vastzet: `ghcr.io/pinkroccade/charts/nl-portal@sha256:...`.
- Kopieer je de chart naar je eigen registry, dan neemt `cosign copy` de handtekening mee; zie [via Harbor](./how-to-harbor.md).
