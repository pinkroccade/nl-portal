# Changelog

All notable changes to the `nl-portal` chart. The chart has its own SemVer, independent of the app version; a breaking
change in the values is a major bump and comes with an upgrade note. A pull request that changes the chart adds a line
here (or updates the docs under `site/hosting-en-configuratie/kubernetes/`), see `mise run helm:docs-touched`.

## 0.1.0

First release: backend and frontend Deployments and Services, tenant files and client keys, an optional Ingress,
Pod Security "restricted" by default, the actuator on a separate management port. Published as a signed OCI artifact
on `oci://ghcr.io/pinkroccade/charts/nl-portal`.

## 0.1.2

`backend.featureFlagContext.tenant` and `.environment` set the evaluation context of the feature flags
(`NLPORTAL_FEATUREFLAGS_CONTEXT_TENANT` and `_ENVIRONMENT`), so a flag file can target per tenant and environment.
Tests now guard that the flag file is mounted as a directory (no `subPath`) and that a flag change restarts the pods
(`checksum/flags`).

## 0.1.3

De module-flags heten `permission.<module>.enabled` (was `ops.<module>.enabled`): hernoem `ops.berichten.enabled` en
`ops.documentenapis.enabled` in `backend.featureFlags` en target op de OIN van de tenant. `backend.featureFlagContext.tenant`
wordt genegeerd: de tenant komt uit het request (#521).

## 0.2.0

**Breaking (values):** `backend.featureFlagContext.tenant` is verwijderd; het schema weigert de sleutel nu. De tenant in de
evaluatiecontext is de OIN van de tenant van elk request, dus targetingregels schrijf je op de OIN en er is geen
`NLPORTAL_FEATUREFLAGS_CONTEXT_TENANT` meer. Haal de sleutel uit je values en pas `tenant == <naam>` in je flagbestand aan
naar de OIN (#175, #521). `backend.featureFlagContext.environment` blijft.

## 0.2.1

Documentatie: ook `haalcentraal-brp` wordt aan- en uitgezet met een feature flag (`permission.haalcentraal-brp.enabled` in
`backend.featureFlags`, standaard uit). Zet de flag aan voor tenants die BRP gebruiken, anders is de BRP-query na de upgrade niet
beschikbaar (#521).
