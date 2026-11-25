# Uitleg: hoe releasen werkt

*Diátaxis: uitleg.* Waarom een release hier een retag is en geen build, en wat er tussen een merge en een release gebeurt. De stappen staan in de
[how-to](./release.md), de namen en invoer in de [referentie](./release-referentie.md). De beslissing zelf staat in
[ADR 0019](../adr/0019-trunk-based-continuous-delivery.md).

## Het idee: één build, daarna alleen nog wijzen

`main` is de enige lijn. Elke merge bouwt backend en frontend precies één keer, draait de poorten en pusht een onveranderlijk image met de
tag `<versie>-<sha>`. Wat de poorten heeft gehaald, is een artefact met een vaste digest.

Een release bouwt niets opnieuw. Ze kiest zo'n geteste digest en zet er de releasetags op. Daardoor draait in productie precies wat is getest.
Bouwde de release opnieuw, dan zou de uitkomst kunnen verschillen van wat de tests zagen.

```
merge op main -> build-once -> poorten groen -> image <versie>-<sha> (gesigneerd, SBOM)
                                                   |
                             promote (handmatig, goedkeuring) -> x.y.z, x.y, x, latest + GitHub Release
```

## De twee workflows

**`build-once.yml`** loopt automatisch. Tests, smoke-test op de quickstart-compose en SBOM zijn poorten: faalt er één, dan wordt er niets gepusht
en ontstaat er geen tag. Alleen een geslaagde run levert dus digests op. Een commit die alleen documentatie raakt, start hem niet.

**`promote.yml`** is handmatig en staat achter de `release`-environment. Een mens keurt goed voor wat naar afnemers gaat. De workflow
controleert de handtekening en SBOM-attestatie van de build, zet de tags, ondertekent en attesteert opnieuw en maakt de GitHub Release.

## Wat de tags betekenen

- `<versie>-<sha>` is een build. Elke groene merge heeft er een; ze zeggen niets over een release. De referentieomgeving rolt de nieuwste groene build uit.
- `x.y.z` is een release en onveranderlijk. Dezelfde versie op een andere digest wordt geweigerd.
- `x.y` en `x` volgen de nieuwste release binnen die minor of major, en `latest` de nieuwste release overall: de nieuwste goedgekeurde release, niet de nieuwste build.
  Ze schuiven alleen vooruit: een oudere patch promoveren zet ze niet terug.
- Afnemers verwijzen naar `x.y.z` of een digest, nooit naar `latest`.

## Wat een release niet doet

- Ze deployt niets: afnemers rollen zelf uit, in het geval van Pink via ArgoCD.
- Ze is geen rollback: terugzetten is ArgoCD naar de vorige `x.y.z`.
- Ze bevat nog geen poort op metrics van de referentieomgeving; die volgt zodra de metrics beschikbaar zijn.
- De Helm-chart heeft een eigen versie en publicatie (`publish-helm-chart.yml`).
