# 20. Levenscyclus van feature flags en privacy van de evaluatiecontext

## Status

Geaccepteerd (#148, onderdeel van [#143](https://github.com/pinkroccade/nl-portal/issues/143)). Vult
[ADR 0014](./0014-feature-flags-via-openfeature.md) aan; die legt het mechanisme vast, deze ADR de
afspraken eromheen. De CI-controle op vervaldatums en de eigenaar-controle zijn nog niet gebouwd.

**Aanvulling 2026-11-21** ([ADR 0021](./0021-gedeelde-multi-tenant-runtime-met-cellen.md)): de moduleflag, die
bepaalt of een module voor een tenant beschikbaar is, is een *permission*-flag:
`permission.<module>.enabled`, fail-closed. De oudere naam `ops.<module>.enabled` vervalt (breaking change
onder ADR 0012). Reden: "mag deze tenant deze module gebruiken" is een toegangsbeslissing; een storing van
het flag-systeem mag nooit een module bij een tenant aanzetten.

**Aanvulling 2026-11-24** (#143): de indeling in vier typen volgt
[Feature Toggles van Martin Fowler](https://martinfowler.com/articles/feature-toggles.html): een flag krijgt zijn type
naar zijn doel, levensduur en dynamiek, niet naar de techniek. Een permission-flag is langlevend en gaat over wie
iets mag gebruiken (hier: welke tenant welke module); een ops-flag is een operationele schakelaar of noodrem; een
release-flag is kortlevend en verbergt onafgemaakt werk; een experiment-flag is kortlevend en splitst verkeer.
Instellingen die nooit veranderen en per tenant verschillen (thema, URL's) zijn geen flags maar tenantconfiguratie.
Verder vervalt het gebruikers-id uit de evaluatiecontext en daarmee de HMAC en de sleutel per tenant (punt 4).

## Context

ADR 0014 kiest OpenFeature met een flagd-bestand als standaard. Zonder afspraken groeit een flagbestand
vanzelf vol met vergeten release-schakelaars, en is het verleidelijk om voor targeting of experimenten
persoonsgegevens in de evaluatiecontext te stoppen. Die context kan bij een externe provider (#147)
buiten de eigen infrastructuur terechtkomen, en NL Portal verwerkt BSN en KVK-nummers. Een lezer die
alleen de code ziet, vraagt zich af waarom de context zo karig is en waarom flags een vervaldatum hebben.

## Beslissing

1. **Vier toggle-typen met een eigen foutgedrag.** *Permission*-flags zijn fail-closed: de code-default is
   uit en een fout of ontbrekende flag weigert. *Ops*-, *experiment*- en *release*-flags vallen terug op
   de waarde in het meegeleverde flagd-bestand, zodat de portal bij een storing van het flag-systeem
   blijft werken.
2. **Naamgeving `<type>.<module>.<naam>`**, bijvoorbeeld `permission.berichten.enabled` voor een module en
   `ops.frontend.message-count` voor een operationele schakelaar. Elke flag heeft
   precies één eigenaar (een team of rol, geen persoon) die bij de flagdefinitie is vastgelegd.
3. **Levensduur per type.** Release- en experiment-flags hebben een vervaldatum; een CI-controle faalt
   zodra een flag over datum is, zodat hij wordt opgeruimd (code en definitie). Ops- en permission-flags
   mogen lang leven en hebben geen vervaldatum.
4. **De evaluatiecontext bevat alleen**: tenant (de OIN, uit de `TenantContext`), omgeving en rol of
   machtigingsniveau. Er is **geen gebruikers-id** in de context: alle huidige flags beslissen per tenant en
   niet per burger, en de sessie in de BFF bevat de identiteit al. **Nooit** BSN, KVK-nummer, naam of ander
   persoonsgegeven, en ook geen hash daarvan: de BSN-ruimte is klein genoeg om een gewone hash terug te
   rekenen. Ontstaat er een gebruiksgeval voor targeting per burger, dan wordt dat een eigen besluit. Dan
   geldt: de `sub` van de realm van de tenant (pseudoniem, `iss` plus `sub` is per tenant uniek) zolang de
   provider in het proces draait; bij een externe provider een pairwise subject van de identity provider of
   een HMAC via een HSM-backed Vault Transit, nooit een HMAC met een sleutel in het proces.
5. **Credentials en andere secrets staan nooit in een flag-systeem**, ook niet als flagwaarde of
   in een flagvariant.
6. **Evaluatie gebeurt alleen in de backend.** De frontend krijgt alleen de uitkomst via het
   frontend-config-endpoint, nooit de context, de flagdefinitie of een flag-server-token.
7. **Het flagd-bestand is de zero-server default.** De productie-provider volgt uit spike #147; deze
   afspraken gelden voor elke provider.

## Overwogen alternatieven

- **Gewone hash (SHA-256) van het gebruikers-id of BSN als context**: voor een BSN is dat met een
  opzoektabel terug te rekenen, dus feitelijk een persoonsgegeven.
- **Gebruikers-id als HMAC met een sleutel per tenant** (de eerste versie van deze ADR): bij flagd in
  bestandsmodus verlaat de context het proces niet, dus de HMAC beschermt niets extra. Hij kost wel een
  secret per tenant in de secret store, en een HMAC in het proces valt onder ASVS 13.3.3 (crypto in een
  beveiligingsmodule, L3). Daarom vervalt hij samen met het gebruikers-id (#207).
- **Naam of KVK-nummer als context voor gerichte uitrol**: handig voor targeting, maar een flagprovider
  hoort geen persoonsgegevens te verwerken. Targeten gaat op tenant of rol.
- **Geen vervaldatum, opruimen op gevoel**: dat is de standaardweg naar dode flags en onbedoelde
  codepaden. Een CI-controle kost weinig en maakt opruimen afdwingbaar.
- **Flags evalueren in de frontend (client-side SDK)**: zou de flagdefinitie en een provider-token naar
  de browser brengen; zie beslissing 6.

## Gevolgen

- Een nieuwe flag zonder eigenaar of (voor release/experiment) vervaldatum komt niet door CI. Daarvoor
  is een vervolgticket nodig; het formaat van eigenaar en vervaldatum in het flagd-bestand
  (bijvoorbeeld `metadata`) is dan nog te kiezen.
- Wie targeting op een ander kenmerk wil, moet dat via een nieuwe ADR of herziening van deze
  toestaan; de context blijft bewust klein.
- Bestaande tests (`FeatureContextFactoryTest`, `FeatureContextPrivacyTest`) bewaken beslissing 4 al
  deels; een nieuwe contextattribuut moet daar langs.
- Dezelfde regels gelden voor een externe provider, ook bij logging en telemetrie door die provider.
