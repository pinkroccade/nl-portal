# Document filtering

Het is mogelijk om te bepalen welke Documenten een gebruiker te zien krijgt. Documenten worden gefilterd voor dat ze geretourneerd worden.

## Configuratie

Deze functionaliteit maakt gebruik van twee witlijsten in de moduleconfiguratie van de tenant, in het tenantbestand onder
`modules.documenten-api`. Beide hebben hun eigen [opties en standaardwaarden](#opties); de standaardwaarden gelden als een
witlijst niet is ingesteld. Elke tenant heeft zijn eigen witlijsten.

1. `modules.documenten-api.statusWitlijst`
2. `modules.documenten-api.vertrouwelijkheidsaanduidingWitlijst`

### Opties

De volgende opties zijn van toepassing bij de whitelists en zijn gebaseerd op de 
[enkelvoudiginfromatieobject](https://openzaak.ritense.opengem.nl/documenten/api/v1/schema/#tag/enkelvoudiginformatieobjecten)
Documenten API specificatie.

| Status           | Standaardwaarde |
|------------------|:---------------:|
| ter_vaststelling |                 |
| in_bewerking     |                 |
| definitief       |        X        |
| gearchiveerd     |        X        |

| Vertrouwelijkheidaanduiding | Standaardwaarde |
|-----------------------------|:---------------:|
| openbaar                    |        X        |
| beperkt_openbaar            |        X        |
| intern                      |        X        |
| zaakvertrouwelijk           |        X        |
| vertrouwelijk               |                 |
| confidentieel               |                 |
| geheim                      |                 |

### Voorbeeld

Het volgende stuk tenantbestand zorgt ervoor dat alle informatieobjecten die bij een zaak horen worden geretourneerd. De
documenten-API zelf (URL en authenticatie) staat als koppeling onder `koppelingen`; `documentApis` noemt die koppelingen bij naam
en `standaardDocumentApi` kiest de koppeling voor uploads.

```yaml
koppelingen:
    documenten-api:
        url: https://open-zaak.example.nl/documenten/api/v1
        auth:
            type: zgw-jwt
            clientId: nl-portal
            secret: 00000099000000000001.documenten-api-secret
modules:
    documenten-api:
        standaardDocumentApi: documenten-api
        documentApis:
            documenten-api:
                rsin: "051845623"
                documentTypeUrl: https://open-zaak.example.nl/catalogi/api/v1/informatieobjecttypen/36e40d95-e411-4884-b08f-e5864ec8167f
        vertrouwelijkheidsaanduidingWitlijst: [openbaar, beperkt_openbaar, intern, zaakvertrouwelijk, vertrouwelijk, confidentieel, geheim, zeer_geheim]
        statusWitlijst: [ter_vaststelling, in_bewerking, definitief, gearchiveerd]
```

Een document-URL uit een bron (bijvoorbeeld een bijlage van een bericht) hoort bij de document-API waarvan de basis-URL een exact
voorvoegsel van de URL is (schema, host, poort en pad op segmentgrens). Valt de URL onder geen enkele of onder meer dan een document-API van de
tenant, dan weigert de module hem. Gebruik dus verschillende basis-URL's voor verschillende document-API's van een tenant. De
schakelaar `nl-portal.config.documentenapis.enabled` blijft een opstartinstelling.
