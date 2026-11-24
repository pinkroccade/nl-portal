# Changelog

All notable changes to the `nl-portal` chart. The chart has its own SemVer, independent of the app version; a breaking
change in the values is a major bump and comes with an upgrade note. A pull request that changes the chart adds a line
here (or updates the docs under `site/hosting-en-configuratie/kubernetes/`), see `mise run helm:docs-touched`.

## 0.1.0

First release: backend and frontend Deployments and Services, tenant files and client keys, an optional Ingress,
Pod Security "restricted" by default, the actuator on a separate management port. Published as a signed OCI artifact
on `oci://ghcr.io/pinkroccade/charts/nl-portal`.
