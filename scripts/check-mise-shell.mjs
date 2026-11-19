#!/usr/bin/env node
// Faalt als een inline mise-taak bash-syntaxis gebruikt zonder `shell = "bash -c"`. Onder Windows draait mise inline
// taken met cmd (de instelling om dat te wijzigen negeert mise in een projectconfig, om veiligheidsredenen); een taak
// als `RUN_MODE=x docker compose up` faalt daar dan met "'RUN_MODE' is not recognized". Zie mise.toml.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const KOP = /^\[tasks\.(?:"([^"]+)"|([A-Za-z0-9_:-]+))\]\s*$/;
// Sleutels die geen commando bevatten; hun waarde telt niet mee.
const GEEN_COMMANDO = /^(description|depends|depends_post|wait_for|dir|env|alias|hide|sources|outputs|shell|usage|confirm)\s*=/;
const BASH_SYNTAXIS = [
  /\$\{/, // ${VAR:-x}
  /\$\(/, // $(commando)
  /(?:^|&&|\|\||;|\|)\s*[A-Z_][A-Z0-9_]*=/m, // VAR=waarde commando, aan het begin van een commando
  /(?:^|[\s"'])\[ /, // [ -n "$x" ]
  /(?:^|[\s;])(?:then|fi)(?:[\s;]|$)/, // if ...; then ...; fi
  /(?:^|[\s"'])\.\/gradlew\b/, // ./gradlew
];

export function takenZonderShell(tekst) {
  const taken = [];
  let huidig;
  for (const regel of tekst.replace(/\r\n/g, "\n").split("\n")) {
    const kop = KOP.exec(regel);
    if (kop) {
      huidig = { naam: kop[1] ?? kop[2], shell: false, commando: [] };
      taken.push(huidig);
    } else if (regel.startsWith("[")) {
      huidig = undefined;
    } else if (huidig) {
      if (/^shell\s*=/.test(regel)) huidig.shell = true;
      // De `run =`-sleutel en een openende aanhalingsteken of haak weg, zodat `^` het begin van het commando is.
      if (!GEEN_COMMANDO.test(regel)) huidig.commando.push(regel.replace(/^\s*run\s*=\s*(?:"""|'''|["'[])?\s*/, ""));
    }
  }
  return taken
    .filter((taak) => !taak.shell && BASH_SYNTAXIS.some((patroon) => patroon.test(taak.commando.join("\n"))))
    .map((taak) => taak.naam);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const fout = takenZonderShell(readFileSync(new URL("../mise.toml", import.meta.url), "utf8"));
  if (fout.length > 0) {
    console.error(`Deze mise-taken gebruiken bash-syntaxis zonder shell = "bash -c" (faalt onder Windows): ${fout.join(", ")}`);
    process.exit(1);
  }
  console.log("Alle inline mise-taken met bash-syntaxis hebben shell = \"bash -c\"");
}
