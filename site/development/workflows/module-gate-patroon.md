# Module aan- of uitzetten met een module-flag

Zo schakel je een backendmodule per request aan of uit met een feature flag. Berichten (`:zgw:berichten`) is de
eerste module die dit doet (#157, onderdeel van #143) en is het voorbeeld voor de andere modules. De keuze voor
flags staat in [ADR 0014](../adr/0014-feature-flags-via-openfeature.md), de regels voor sleutels en context in
[ADR 0020](../adr/0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md).

## Wat de gate doet

- De beans van de module (service, GraphQL-controller, REST-resource) bestaan altijd, ook als de module uit staat.
- Elke GraphQL-query of -mutatie en elke REST-resource van de module roept eerst `ModuleGate.require(...)` aan.
  Staat de flag uit, dan volgt een duidelijke "niet beschikbaar"-fout:
  - GraphQL: een fout met de melding `Module 'berichten' is niet beschikbaar` en `extensions.code = MODULE_NOT_AVAILABLE`.
  - REST: HTTP 503 met een `ProblemDetail`-antwoord (`detail`: dezelfde melding).
- Alle GraphQL-fouten van modules zetten `GraphQLExceptionHandler` om, op één plek, met codes in `extensions.code`:

  | Code | Wanneer | Melding naar de browser |
  |---|---|---|
  | `MODULE_NOT_AVAILABLE` | de module-flag staat uit (met `extensions.module`) | `Module '<module>' is niet beschikbaar` |
  | `MODULE_NIET_GECONFIGUREERD` | de tenant heeft geen `modules.<naam>` | `De module is niet beschikbaar` |
  | `TENANT_ONTBREEKT` | het verzoek heeft geen tenant | `De aanvraag kan niet worden verwerkt` |
  | `KOPPELING_FOUT` | de bron van een koppeling faalt (met `extensions.correlatieId`) | `Koppeling '<naam>' is niet beschikbaar (referentie <id>)` |

  `MODULE_NOT_AVAILABLE` is bestaand contract (clients kunnen erop toetsen) en blijft daarom Engels.

  De meldingen van `MODULE_NIET_GECONFIGUREERD` en `TENANT_ONTBREEKT` zijn vaste teksten: de OIN en de naam van een instelling gaan
  alleen naar de log. Een nieuw type fout voeg je toe aan `GraphQLExceptionHandler.afhandeling`.
- Het GraphQL-schema verandert niet: de velden blijven zichtbaar in introspectie.
- De `modules`-lijst van `GET /api/public/frontend` en de modulestatus op de beheerpoort (`/actuator/modulestatus`)
  volgen dezelfde flag. De oude `nl-portal.config.<module>.enabled` bepaalt dat voor een gegate module niet meer.
- Zonder flagdefinitie staat de module uit (standaard in de code). Zet hem aan in je flagbestand.

## De module-flag

Een `ModuleFlag("berichten")` is de permission-flag `permission.berichten.enabled`. De module maakt hem bekend als bean, zodat de
frontend-lijst en de modulestatus hem kennen:

```kotlin
val BERICHTEN_MODULE = ModuleFlag("berichten")

@Bean
fun berichtenModuleFlag(): ModuleFlag = BERICHTEN_MODULE
```

## Gate in een query of resource

Geef de authenticatie van het request expliciet mee. De gate bouwt daaruit de evaluatiecontext (tenant, omgeving,
gebruikerstype); er is geen thread-gebonden state.

```kotlin
@QueryMapping
suspend fun getBerichten(authentication: PortalAuthentication, ...): BerichtenPage {
    moduleGate.require(BERICHTEN_MODULE, authentication)
    return berichtenService.getBerichtenPage(...)
}
```

Een veld dat een onderdeel is van een gegate bovenliggend object (zoals `Bericht.documenten`) hoeft de gate niet zelf
aan te roepen.

## Afhankelijkheden tussen modules (#158)

Een module-flag declareert de flags die hij vereist, in de code bij de flag zelf:

```kotlin
val DOCUMENTENAPIS_MODULE = ModuleFlag("documentenapis")
val BERICHTEN_MODULE = ModuleFlag("berichten", requires = listOf(DOCUMENTENAPIS_MODULE))
```

`ModuleGate.isEnabled` en `require` kijken naar de eigen flag en naar alle vereiste flags, ook die van vereiste modules.
Staat een vereiste flag uit of ontbreekt hij, dan is de module fail-closed niet beschikbaar, ook als zijn eigen flag aan
staat. Cycli kunnen niet voorkomen: een `ModuleFlag` bevat zijn vereisten als waarde. `ModuleGate.dependencies()` geeft
de graaf als lijst per geregistreerde module. De bijgewerkte tabel staat in de
[feature flags-referentie](../../hosting-en-configuratie/feature-flags.md). De vereiste flag hoeft zelf niet als bean
geregistreerd te zijn: een module die nog niet via zijn flag wordt aan- of uitgezet (zoals documentenapis) is alleen
afhankelijkheid.

## Wat startup-gebonden blijft

De gate werkt per request. Dit blijft bij het opstarten vastliggen en valt buiten de gate:
HTTP-clients, de security filter chain, het GraphQL-schema en de property-binding. Een module blijft daarnaast
afhankelijk van de modules waar zijn beans om vragen: berichten heeft nog steeds `documentenapis.enabled` nodig
en een Objecten API-bean. Dat afhankelijkheidsmodel volgt in een apart ticket binnen #143.

## Testen

Test op de applicatiegrens: start de applicatie met een flagbestand en doe een echt GraphQL- of REST-request.
`BerichtenQueryIT` en `BerichtDocumentResourceIT` doen dat voor aan en uit. Let op: OpenFeature is een globale
singleton in de JVM. Laat alle testcontexts van een module daarom hetzelfde flagbestand gebruiken
(`BerichtenFlagFile`) in plaats van elk een eigen bestand.

## Een module overzetten

1. Voeg `ops.<module>.enabled` toe aan `default.flagd.json` (uit) en aan het compose-flagbestand (aan).
2. Haal `<module>.enabled` uit de `@ConditionalOnProperty` van de autoconfiguratie en uit de `@ConfigurationProperties`.
3. Registreer de `ModuleFlag` als bean en roep `moduleGate.require(...)` aan in elke query, mutatie en resource.
4. Verwijder `NLPORTAL_CONFIG_<MODULE>_ENABLED` uit voorbeeld- en compose-bestanden en zet de flag in de testbestanden.
5. Declareer de vereiste modules met `requires` en schrijf de tests voor aan, uit en een uitgeschakelde afhankelijkheid en neem de flag op in de [feature flags-referentie](../../hosting-en-configuratie/feature-flags.md).
