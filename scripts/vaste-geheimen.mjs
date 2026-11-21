// Spoort vaste wachtwoorden, secrets en tokens op in configuratiebestanden (compose, imports, app-dev).
// Een waarde is alleen toegestaan als ze leeg is of uit verwijzingen bestaat: een compose-variabele zonder
// standaardwaarde (met optionele foutmelding), een Spring-placeholder, een {{placeholder}} uit een template of een
// geheimverwijzing in een tenantbestand (`<oin>.<naam>`: alleen de naam, de waarde staat in de secret store).
// Zo hoeft de bewaking zelf geen geheimen of voorbeeldwaarden te kennen.

const VERWIJZING = /\$\{[A-Z0-9_]+(?::\?[^}]*|:)?\}|\{\{[A-Z0-9_]+\}\}/g;
const GEHEIM_NAAM = /(PASSWORD|SECRET|TOKEN|WACHTWOORD|HEADER_VALUE|APIKEY)/i;
const ASSIGNMENT = /^\s*(?:-\s*)?["']?([A-Za-z0-9_.-]+)["']?\s*[=:]\s*(.*?)\s*,?\s*$/;
const URL_MET_WACHTWOORD = /\b[a-z][a-z0-9+.-]*:\/\/[^\s:@/]+:([^\s@/]+)@/gi;

const GEHEIM_VERWIJZING = /^\d{20}\.[a-z0-9]+([._-][a-z0-9]+)*$/;

function isVerwijzing(waarde) {
  const rest = waarde
    .replace(/^["']|["']$/g, "")
    .replace(VERWIJZING, "")
    .replace(/^(\{noop\}|Token |Bearer )/, "")
    .trim();
  // "dummy" is de bewust betekenisloze invulling van de Worldline-preprod-koppeling in :app-dev.
  return rest === "" || GEHEIM_VERWIJZING.test(rest) || /^(true|false|dummy|\d{1,3})$/.test(rest);
}

/** Geeft per gevonden vaste waarde een melding ("regel N: naam"); de waarde zelf komt er nooit in. */
export function vasteGeheimen(tekst) {
  const gevonden = [];
  for (const [i, regel] of tekst.split(/\r?\n/).entries()) {
    if (/^\s*(#|\/\/)/.test(regel)) continue;
    const m = ASSIGNMENT.exec(regel);
    if (m && GEHEIM_NAAM.test(m[1]) && !isVerwijzing(m[2])) gevonden.push(`regel ${i + 1}: ${m[1]}`);
    for (const u of regel.matchAll(URL_MET_WACHTWOORD)) {
      if (!isVerwijzing(u[1])) gevonden.push(`regel ${i + 1}: wachtwoord in url`);
    }
  }
  return gevonden;
}
