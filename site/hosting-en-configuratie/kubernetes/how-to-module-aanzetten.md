# How-to: een module aanzetten

*Diátaxis: how-to.* Voorbeeld: de module `taak` (taken uit Open Object) voor een tenant.

Elke module staat standaard uit. Een module aanzetten bestaat uit drie dingen: de schakelaar en niet-geheime instellingen in de chart, de koppeling en
modulegegevens in het tenantbestand, en de geheimen in het Secret.

1. **Zet de afhankelijkheden aan.** Een module die een andere nodig heeft, rendert niet zolang die uit staat:

   | Module | Heeft nodig |
   |---|---|
   | `taak`, `formulieren`, `product`, `prefill` | `objectenapi` |
   | `berichten` | `objectenapi`, `documentenapis` |
   | `zakenapi` | `catalogiapi` |

   ```yaml
   backend:
     modules:
       objectenapi: { enabled: true }
       taak: { enabled: true }
   ```

2. **Vul de modulegegevens in** als de module ze in de chart wil (bijvoorbeeld `backend.modules.besluitenapi.url` en `clientId`,
   `backend.modules.clamav.hostname`). Welke modules welke velden eisen, staat in de [values-referentie](./referentie-values.md); de chart weigert een
   ingeschakelde module met een ontbrekende verplichte waarde.

3. **Beschrijf de koppeling in het tenantbestand** (`koppelingen` en `modules`): de URL van de bron, het authenticatietype en de *naam* van het geheim. Zie
   [Tenantbestand, flag en secret store](../tenantbestand-flag-en-secret-store.md) en [Connectiviteit](../connectiviteit.md).

4. **Voeg het geheim toe** aan het Secret, bijvoorbeeld `NL_PORTAL_GEHEIMEN_<OIN>_OBJECTENAPI_TOKEN` (waarde `Token <token>`), en bij sommige modules een
   vaste key ([secret-keys](./referentie-secret-keys.md)). Zie [secrets via ESO](./how-to-secrets-eso.md).

5. **Open de egress** van de backend naar de bron ([egress per module](./referentie-infra-vereisten.md#egress-per-module)).

Uitzondering: `berichten` zet je aan met de feature flag `permission.berichten.enabled` in `backend.featureFlags` en `modules.berichten` in het tenantbestand;
`backend.modules.berichten.enabled` verklaart alleen de bedoeling, zodat de afhankelijkheidscontrole draait. Feature flags lever je als YAML onder
`backend.featureFlags`; de chart maakt er het flagbestand van ([Feature flags](../feature-flags.md), [Module dependency guide](../module-dependency-guide.md)).

Controleer daarna `GET /actuator/modulestatus` op de beheerpoort ([Beheerpoort: modulestatus](../beheer-modulestatus.md)), als je `backend.beheer` hebt aangezet.
