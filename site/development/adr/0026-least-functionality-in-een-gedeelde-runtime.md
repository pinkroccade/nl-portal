# 26. Least functionality in een gedeelde runtime

## Status

Voorgesteld (#532, onderdeel van epic [#530](https://github.com/pinkroccade/nl-portal/issues/530)). De architect
zet deze ADR op *Geaccepteerd* na beoordeling, en weegt daarbij het CISO-perspectief mee. Werkt principe
P-07 en P-08 uit het [principeregister](../../architectuur/principes.md) uit
([ADR 0023](./0023-architectuurprincipes-en-toetsing.md), nog voorgesteld). Vult
[ADR 0014](./0014-feature-flags-via-openfeature.md) en
[ADR 0020](./0020-levenscyclus-feature-flags-en-privacy-evaluatiecontext.md) aan. De eisen voor de centrale
modulegate (T22, [#521](https://github.com/pinkroccade/nl-portal/issues/521)) staan onder "Eisen aan #521".

## Context

Least functionality (NIST SP 800-53 CM-7, BIO2 configuratiebeheer en hardening, ASVS 5.0) zegt: een systeem
biedt alleen de functies die nodig zijn. NL Portal draait als één codebase en één image voor alle klanten
([ADR 0019](./0019-trunk-based-continuous-delivery.md), [ADR 0021](./0021-gedeelde-multi-tenant-runtime-met-cellen.md)).
Code weglaten per klant kan dus niet. De huidige modulegate (`ModuleGate`, #157) laat de beans, de HTTP-clients
en het GraphQL-schema van een uitgeschakelde module altijd bestaan, en elke resolver moet zelf `require`
aanroepen. Een vergeten aanroep is een open endpoint. Wie alleen de code ziet, vraagt zich af waarom
uitgeschakelde modules toch aanwezig zijn en waarom de gate centraal moet.

## Beslissing

Uitgangspunt: **onbereikbaar voor wie geen recht heeft, inert waar het niet nodig is.** Dat werkt in vier lagen.

1. **Product.** Een module die Pink aan geen klant aanbiedt, verdwijnt uit de codebase (voorbeeld: de
   case-module, [ADR 0010](./0010-verwijderen-case-module.md)). Wat in de codebase staat, wordt onderhouden en
   gescand.
2. **Tenant.** Een module die voor een tenant uit staat, antwoordt niet. De beslissing loopt via de
   permission-flag `permission.<module>.enabled` (ADR 0020, aanvulling bij ADR 0021) en is fail-closed: een
   ontbrekende flag of een storing weigert.
3. **Koppelingen en secrets.** Voor een module waar een tenant geen recht op heeft, bestaan geen client en geen
   secret in de configuratie van die tenant (dataminimalisatie, AVG art. 25). Een flag die aan staat zonder
   koppelingsconfiguratie is een fout die de CI signaleert; configuratie zonder flag is toegestaan (dark uitrollen).
4. **Dependencies.** Elke module heeft een kleine dependency-voetafdruk en de kern trekt geen
   moduledependencies binnen. Een bevinding in een library die alleen onbereikbare code gebruikt, wordt met VEX
   getrieerd (`not_affected`, met onderbouwing) en niet stilzwijgend genegeerd.

Daarbij:

5. **Eén centrale gate, deny by default.** Eén centrale gate (GraphQL-instrumentation en WebFilter, de keuze
   voor de vorm is aan T22) vervangt de `require`-aanroep per resolver. Een module-endpoint dat niet bij de
   gate is geregistreerd, is niet bereikbaar. Een ArchUnit-regel dwingt af dat elk module-endpoint
   (GraphQL-controller, REST-resource) bij de gate hoort.
6. **GraphQL-introspectie staat uit in productie.** Het schema van uitgeschakelde modules is voor een
   tenant zonder recht dan niet te ontdekken. Dev-profielen mogen introspectie aanzetten, omdat de
   frontend-codegen het volledige schema nodig heeft. Een test borgt dat `:app` introspectie uit heeft.
7. **Removability fitness function.** Een module kan uit de Gradle-build zonder dat de kern of een andere module
   breekt. Dit is een codetest en geen productvariant: er is geen tweede image. De test draait periodiek (nachtelijk),
   niet bij elke PR, omdat hij per module een build kost.

## Overwogen alternatieven

- **Per-klant-images** (modules per klant uit de build): maximaal least functionality, maar dan zijn er
  N images om te bouwen, te scannen, te signeren en te testen. Dat botst met één build per commit (ADR 0019) en met
  de gedeelde runtime (ADR 0021). Afgewezen.
- **Celniveau** (een module die geen tenant in de cel gebruikt, is inert): in de hoofdcel is de vereniging van
  alle tenants vrijwel altijd alles, dus de winst is klein. Het levert wel een extra configuratievariant op die
  getest moet worden. Afgewezen.
- **Status quo** (gate per resolver, beans altijd aanwezig): een vergeten `require` is een open endpoint en het
  schema lekt wat er bestaat. Afgewezen voor een gedeelde runtime met meerdere tenants.
- **Beans en clients per tenant weglaten** (modules per tenant starten): vergt een Spring-context per tenant of
  dynamische beanregistratie; te complex voor de winst, want niet-aangeroepen code is al inert zodra de gate
  centraal en deny by default is. Afgewezen.

## Eisen aan #521

T22 moet halen, en is pas klaar als de volgende punten controleerbaar zijn:

- [ ] Eén centrale gate beslist per request en per tenant op `permission.<module>.enabled`; geen `require`-aanroep per resolver meer.
- [ ] Deny by default: een module-endpoint zonder registratie bij de gate weigert (test op de applicatiegrens).
- [ ] ArchUnit-regel: elke GraphQL-controller en REST-resource van een module is bij de gate geregistreerd.
- [ ] De tenant komt uit de `TenantContext`; de gate werkt bij meerdere tenants (tweede tenant in de test, #522).
- [ ] Afhankelijkheden tussen modules (`ModuleFlag.requires`) blijven gelden; een uitgeschakelde afhankelijkheid sluit de module fail-closed.
- [ ] Een module is alleen aan wanneer flag én koppelingsconfiguratie van de tenant aanwezig zijn (ADR 0021, beide); flag zonder configuratie wordt gesignaleerd.
- [ ] De weigering lekt niets: dezelfde vaste foutmelding voor "uit" en "niet geconfigureerd", geen OIN of naam van een instelling in het antwoord.
- [ ] GraphQL-introspectie staat uit in `:app`; een test borgt dat.
- [ ] Het [module-gate-patroon](../workflows/module-gate-patroon.md), de flag-referentie en arc42 §8.8 zijn bijgewerkt.

## Gevolgen

- Het module-gate-patroon uit #157 (gate per resolver) is een tussenstap en wordt door T22 vervangen. De
  patroonbeschrijving heeft daarvoor een aanvulling gekregen.
- ADR 0014 en 0020 hebben een aanvulling gekregen: de moduleflag is een permission-flag en de gate is centraal.
- De removability fitness function en de VEX-triage zijn nieuwe controles. Ze bewaken P-07 en P-08, en horen
  bij de bestaande CI en de nachtelijke run; ze zijn nog niet gebouwd en krijgen een eigen ticket.
- Zolang de fitness function ontbreekt, is de toetsing van P-07 een reviewpunt.
- Open punt: dit register verwijst naar ADR 0023 (voorgesteld). Wijzigt de architect P-07 of P-08, dan volgt
  deze ADR.
