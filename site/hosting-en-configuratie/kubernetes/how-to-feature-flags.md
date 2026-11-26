# How-to: feature flags per tenant in Kubernetes

*Diátaxis: how-to.* Het flagbestand van een tenant (of cel) staat als ConfigMap in de release, en een wijziging komt bij de pods via een rolling update.
Er is geen hot reload en geen aparte flag-server ([ADR 0014](../../development/adr/0014-feature-flags-via-openfeature.md), [ADR 0020](../../development/adr/0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md)).
Welke flags er zijn staat in de [feature flags-referentie](../feature-flags.md); het formaat en de evaluatiecontext in de
[deployment guide](../deployment-guide.md#feature-flags).

## Het flagbestand instellen

Zet de flags als YAML onder `backend.featureFlags` (flagd-formaat) en geef de release zijn tenant en omgeving mee:

```yaml
backend:
  featureFlagContext:
    tenant: gemeente-a
    environment: productie
  featureFlags:
    flags:
      ops.berichten.enabled:
        state: ENABLED
        variants: { "on": true, "off": false }
        defaultVariant: "on"
      ops.frontend.message-count:
        state: ENABLED
        variants: { "on": true, "off": false }
        defaultVariant: "off"
        targeting:
          if:
            - "==": [{ var: tenant }, gemeente-a]
            - "on"
            - "off"
```

Wat de chart ervan maakt:

| Onderdeel | Inhoud |
|---|---|
| ConfigMap `<release>-nl-portal-flags` | Het bestand `nl-portal.flagd.json`, als JSON uit je values. |
| Volume `flags` | De ConfigMap, als map gemount op `/etc/nl-portal/flags` (alleen lezen, **geen `subPath`**). |
| `NLPORTAL_FEATUREFLAGS_FILE` | Wijst naar `/etc/nl-portal/flags/nl-portal.flagd.json`. |
| `NLPORTAL_FEATUREFLAGS_CONTEXT_TENANT` en `_ENVIRONMENT` | Uit `backend.featureFlagContext`; de attributen `tenant` en `environment` voor targetingregels. |
| Annotatie `checksum/flags` | Hash van `backend.featureFlags` op de pod-template: een wijziging herstart de pods één voor één. |

Zonder `backend.featureFlags` maakt de chart geen ConfigMap en geldt het meegeleverde bestand: alles uit. Een flag die in je bestand ontbreekt, is uit; een
permission-flag valt bij twijfel altijd terug op uit.

Eén release is één tenant (of cel). Voor meerdere tenants maak je per tenant een release met een eigen `backend.featureFlags` en `featureFlagContext.tenant`.

## Een wijziging uitrollen

1. Pas `backend.featureFlags` aan in de values van de tenant (in jullie GitOps-repo, via een pull request).
2. Laat Argo CD of `helm upgrade` de release bijwerken. De ConfigMap verandert, `checksum/flags` verandert, en de Deployment start nieuwe pods.
3. De readiness-probe bewaakt dat de nieuwe pods gezond zijn voordat de oude verdwijnen.

Reken niet op herladen zonder herstart. De backend controleert het bestand wel periodiek (`NLPORTAL_FEATUREFLAGS_POLLINTERVALMS`), maar Kubernetes zet een gewijzigde
ConfigMap pas na enige tijd in de pod en NL Portal belooft dit gedrag niet.

::: warning Mount nooit met `subPath`
Een ConfigMap die met `subPath` is gemount, wordt nooit bijgewerkt in een draaiende pod. De chart mount de map zelf; doe je het bestand via `extraVolumeMounts`
anders, gebruik dan ook geen `subPath`. Een flagwijziging blijft daarmee via de rolling update werken.
:::

## Testplan voor devops: tonen dat het flagbestand in k8s wordt ingelezen

Doel: aantonen dat (1) de flags uit de ConfigMap daadwerkelijk in de pod aankomen, (2) een wijziging een rolling update geeft en (3) je ziet wat er
gebeurt bij een fout in het bestand. Je hebt `kubectl`, `helm` en toegang tot een testnamespace nodig.
De namen hieronder gaan uit van release `nl-portal`; pas ze aan.

**Voorbereiding.** Installeer de release met `ops.frontend.message-count` uit (`defaultVariant: "off"`) en **zonder** targetingregel; die voeg je pas in stap 8 toe.
Een targetingregel die past op de tenant wint van `defaultVariant`: met de regel uit het voorbeeld hierboven staat de flag voor `gemeente-a` meteen aan.
Bepaal daarna de backendpod en de naam van de Deployment:

```shell
NS=nl-portal-test
kubectl -n $NS get deploy,cm | grep -E 'backend|flags'
kubectl -n $NS port-forward deploy/nl-portal-backend 8080:8080
```

| # | Stap | Verwacht resultaat |
|---|---|---|
| 1 | `kubectl -n $NS get cm nl-portal-flags -o jsonpath='{.data.nl-portal\.flagd\.json}'` | Het flagbestand uit je values, met `ops.frontend.message-count` op `off`. |
| 2 | `kubectl -n $NS exec deploy/nl-portal-backend -- cat /etc/nl-portal/flags/nl-portal.flagd.json` | Dezelfde inhoud: het bestand ligt in de pod. |
| 3 | `kubectl -n $NS get deploy nl-portal-backend -o jsonpath='{.spec.template.metadata.annotations.checksum/flags}'` | Een hash; noteer hem. |
| 4 | `curl -s localhost:8080/api/public/frontend` | In `toggles` staat `messageCountEnabled` op `false`. |
| 5 | Zet in je values `ops.frontend.message-count` op `defaultVariant: "on"` en voer `helm upgrade` uit. Volg met `kubectl -n $NS rollout status deploy/nl-portal-backend`. | Een rolling update: nieuwe pods starten één voor één, de rollout slaagt. |
| 6 | Herhaal stap 3. | De hash is veranderd. |
| 7 | Zet de port-forward opnieuw op en herhaal stap 4. | `messageCountEnabled` staat op `true`. |
| 8 | Test targeting: laat `message-count` op `defaultVariant: "off"` en voeg de targetingregel uit het voorbeeld toe (`tenant == gemeente-a`, dan `on`). Zet `featureFlagContext.tenant` op een andere naam (bijvoorbeeld `gemeente-b`), upgrade en herhaal stap 4. | `messageCountEnabled` staat op `false`: de regel past niet op deze tenant. Met de tenant terug op `gemeente-a` staat hij op `true`. |
| 9 | Zet in de ConfigMap een kapot bestand, bijvoorbeeld een flag met `state: BAD` en zonder `variants`, upgrade en volg de rollout en de log van de nieuwe pod. | De rollout slaagt en de pod wordt ready. De log toont `Invalid flag configuration` (WARN) en de flag telt als afwezig: stap 4 geeft `false`. Zie de noot hieronder. |
| 10 | Draai terug: `helm rollback nl-portal` en herhaal stap 4. | `messageCountEnabled` staat op de waarde van vóór stap 9. |


**Terugkoppeling.** Wijkt iets af van de verwachting, dan is dat een fout in deze pagina of in de chart: meld het als issue, zodat de tekst wordt gecorrigeerd. Dit
plan toont het inlezen en de rolling update; metrics en de flagcatalogus vallen erbuiten (zie [#174](https://github.com/pinkroccade/nl-portal/issues/174)).

::: warning Een kapot flagbestand stopt de rollout niet
Gemeten op kind (stap 9): de pod start en wordt ready, de provider logt een waarschuwing en de kapotte flag valt terug op de standaard in de code. Een
permission-flag staat dan uit (fail-closed), een ops-flag op zijn code-default. De readiness-probe merkt niets. Controleer een flagwijziging daarom vóór de merge
en zoek na de uitrol in de log van de nieuwe pod naar `Invalid flag configuration`. Een geautomatiseerde controle volgt in
[#630](https://github.com/pinkroccade/nl-portal/issues/630).
:::
