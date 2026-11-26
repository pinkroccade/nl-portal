# Module aan- of uitzetten met een module-flag

Zo schakel je een backendmodule per tenant aan of uit met een permission-flag. Eén centrale gate beslist; een resolver of
resource roept de gate zelf niet aan. Het ontwerp staat in
[ADR 0026](../adr/0026-least-functionality-in-een-gedeelde-runtime.md) (least functionality), de keuze voor flags in
[ADR 0014](../adr/0014-feature-flags-via-openfeature.md) en de regels voor sleutels en context in
[ADR 0020](../adr/0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md). Berichten (`:zgw:berichten`) is de eerste
module op de centrale gate en het voorbeeld voor de andere.

::: warning Overgang
Berichten (met de afhankelijkheid documentenapis), BRP (`haalcentraal-brp`), taak, zaken (ook met documentenapis) en payment-direct (inclusief de postsale-webhook) staan nu op de gate. De andere modules dragen op hun controllers
`@ModuleEndpoint("<module>", overgang = true)`: hun endpoints blijven bereikbaar zoals voorheen, en een ArchUnit-ratchet
(`ModuleGateRegels`) laat het aantal overgangsregistraties alleen kleiner worden. Elke module gaat in een eigen stap over (T22,
[#521](https://github.com/pinkroccade/nl-portal/issues/521)); daarna gaan de oude module-properties weg
([#162](https://github.com/pinkroccade/nl-portal/issues/162)).
:::

## Wat de gate doet

Een module is **bruikbaar voor een tenant** als twee dingen kloppen, en allebei voor de module zelf en voor elke module die hij
vereist:

1. **Configuratie.** Het tenantbestand van de tenant bevat de koppelingen (`koppelingen.<naam>`) en de modulegegevens
   (`modules.<naam>`) die de module nodig heeft. Dit ligt bij het opstarten vast.
2. **Flag.** De permission-flag `permission.<module>.enabled` staat voor die tenant aan. Dit wordt per request beoordeeld.

Flag zonder configuratie betekent uit; configuratie zonder flag is toegestaan (dark uitrollen). Een ontbrekende flag, een flag die
niet te evalueren is en een request zonder tenant weigeren (fail-closed). Een flagwijziging vóór de eerstvolgende restart kan dus
nooit een andere tenant bereikbaar maken en nooit een module zonder configuratie aanzetten: de tenant komt uit de `TenantContext`
en de configuratie uit het tenantregister dat bij het opstarten is gebouwd.

De gate is eenmalig geregistreerd op de rand van de applicatie:

- **GraphQL**: `ModuleGateInstrumentation` (module `:graphql`) laat elk root-veld (query, mutatie, subscription) eerst door de gate
  lopen, vóór de data fetcher draait. Een module die niet bruikbaar is geeft de fout `Module '<module>' is niet beschikbaar` met
  `extensions.code = MODULE_NOT_AVAILABLE`. Een root-veld waar geen geregistreerde controller bij hoort geeft `ENDPOINT_NIET_GEREGISTREERD`.
- **REST**: `ModuleGateWebFilter` (module `core`) zoekt de controller die het request afhandelt en vraagt de gate. Een module die
  niet bruikbaar is geeft HTTP 503 met een `ProblemDetail`-antwoord; een niet-geregistreerde resource geeft 404. Het filter draait ná
  de security-keten en de tenantresolutie, dus een anoniem request op een beveiligd pad krijgt eerst 401.

Het GraphQL-schema verandert niet per tenant: de velden blijven bestaan, ook voor een tenant die de module niet heeft. Introspectie
staat in `:app` uit (`spring.graphql.schema.introspection.enabled=false`), zodat het schema van een uitgeschakelde module niet te
ontdekken is. `:app-dev` zet introspectie aan voor de codegen van de frontend.

## Deny by default: `@ModuleEndpoint`

Elke GraphQL-controller en elke REST-resource draagt `@ModuleEndpoint`. Zonder de annotatie is het endpoint niet bereikbaar
(404 voor REST, `ENDPOINT_NIET_GEREGISTREERD` voor GraphQL) en faalt de ArchUnit-regel.

```kotlin
@ModuleEndpoint("berichten")              // de module met een ModuleFlag-bean
@Controller
class BerichtenQuery(private val berichtenService: BerichtenService) { ... }

@ModuleEndpoint(ModuleEndpoint.KERN)      // hoort bij de kern: voor elke tenant bereikbaar
@RestController
class FrontendConfigurationResource(...) { ... }

@ModuleEndpoint("besluiten", overgang = true) // nog niet op de gate (zie de waarschuwing hierboven)
@Controller
class BesluitenQuery(...) { ... }
```

Een `@ModuleEndpoint` met een modulenaam zonder `ModuleFlag`-bean laat het opstarten falen.

Een veld dat een onderdeel is van een gegate bovenliggend object (zoals `Bericht.documenten`) hoeft niets te doen: de gate beslist op
het root-veld.

## De module-flag

Een `ModuleFlag` is de permission-flag `permission.<module>.enabled` en weet wat de module van de tenant nodig heeft. De module maakt
hem bekend als bean, zodat de gate, de frontend-lijst en de modulematrix hem kennen:

```kotlin
val DOCUMENTENAPIS_MODULE = ModuleFlag("documentenapis", moduleConfiguratie = DocumentenApiTenantConfiguratie.NAAM)

val BERICHTEN_MODULE =
    ModuleFlag(
        "berichten",
        requires = listOf(DOCUMENTENAPIS_MODULE),
        koppelingen = setOf(ObjectsApiClient.MODULE),
        moduleConfiguratie = BerichtenTenantConfiguratie.NAAM,
    )

@Bean
fun berichtenModuleFlag(): ModuleFlag = BERICHTEN_MODULE
```

`ops.*`-flags blijven bestaan voor noodremmen en bedrijfsvoering; een module aan- of uitzetten per tenant is een permissie
([ADR 0020](../adr/0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md)).

## Afhankelijkheden tussen modules

Een module-flag declareert de flags die hij vereist, in de code bij de flag zelf (`requires`). De gate kijkt naar de eigen flag en
configuratie en naar die van alle vereiste modules, ook de indirecte. Staat een vereiste flag uit of ontbreekt hij, of mist de tenant
de configuratie van een vereiste module, dan is de module fail-closed niet beschikbaar, ook als zijn eigen flag aan staat. Cycli
kunnen niet voorkomen: een `ModuleFlag` bevat zijn vereisten als waarde. `ModuleGate.dependencies()` geeft de graaf als lijst per
geregistreerde module; de tabel staat in de [feature flags-referentie](../../hosting-en-configuratie/feature-flags.md).

## Frontend en beheer

- De `modules`-lijst van `GET /api/public/frontend` is per tenant en toont alleen de bruikbare modules van een module op de gate.
- Het beheerendpoint `/actuator/modulestatus` (beheerpoort) toont per module de status van de bronnen en, voor een module op de gate,
  de **modulematrix** over alle tenants: per tenant `geconfigureerd`, `flag` en `bruikbaar`.

## Wat startup-gebonden blijft

De gate werkt per request. Dit blijft bij het opstarten vastliggen en valt buiten de gate: de HTTP-clients, de security filter chain,
het GraphQL-schema, de property-binding en de configuratie in het tenantbestand. Een module blijft daarnaast afhankelijk van de modules
waar zijn beans om vragen: berichten heeft nog steeds `documentenapis.enabled` nodig en een Objecten API-bean. Dat verdwijnt met #162.

## Testen

Test op de applicatiegrens: start de applicatie met een flagbestand en doe een echt GraphQL- of REST-request. `BerichtenQueryIT` en
`BerichtDocumentResourceIT` doen dat voor aan, uit en een uitgeschakelde afhankelijkheid. Let op: OpenFeature is een globale singleton
in de JVM. Laat alle testcontexts van een module daarom hetzelfde flagbestand gebruiken (`ModuleFlagBestand` uit de testFixtures van core; berichten gebruikt nog `BerichtenFlagFile`) in plaats van elk een
eigen bestand. Een test met twee tenants zet de flag voor één OIN aan (zie `ModuleGateTest`).

## Een module overzetten

1. Declareer de `ModuleFlag` met `koppelingen` en `moduleConfiguratie` en registreer hem als bean.
2. Zet `@ModuleEndpoint("<module>")` op elke controller van de module (haal `overgang = true` weg) en verwijder de `ModuleGate`-parameter
   en elke `require`-aanroep.
3. Voeg `permission.<module>.enabled` toe aan `default.flagd.json` (uit) en aan de flagbestanden van compose en de tenants (aan).
4. Declareer de vereiste modules met `requires`, schrijf de tests voor aan, uit, een uitgeschakelde afhankelijkheid en een tweede tenant,
   en neem de flag en de afhankelijkheid op in de [feature flags-referentie](../../hosting-en-configuratie/feature-flags.md).
5. Haal de module uit `ModuleGateRegels.OVERGANG` (de ratchet) en de oude `<module>.enabled`-property uit de autoconfiguratie (#162).
