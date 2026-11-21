/*
 * Copyright 2015-2026 Den Haag, Ritense, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * De frontend werkt met de sessie van de backend (BFF, ADR 0022): de browser heeft alleen een sessiecookie en nooit een token.
 * Alles hier is same-origin; muterende aanroepen sturen het CSRF-token uit het cookie mee in een header.
 */

/** Het sessie-endpoint van de backend: zegt of de burger is ingelogd, zonder tokens of BSN. */
export const SESSIE_PAD = "/api/public/sessie";
export const UITLOG_PAD = "/logout";
export const CSRF_COOKIE = "__Host-XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";
export const CSRF_PARAMETER = "_csrf";

export type SessieStatus = {
  ingelogd: boolean;
  /** `burger` of `bedrijf`. */
  vertegenwoordigde?: "burger" | "bedrijf";
  namensEenAnder?: boolean;
  inlogmiddel?: string;
  /** Absoluut einde van de sessie (ISO-8601). */
  verlooptOp?: string;
  /** Na zoveel seconden zonder activiteit verloopt de sessie. */
  inactiviteitSeconden?: number;
  /** Alleen als er niet is ingelogd: het pad om in te loggen en de ingeschakelde inlogmiddelen. */
  loginPad?: string;
  inlogmiddelen?: string[];
};

/** Het CSRF-token uit het cookie, of `undefined` zolang de backend het nog niet heeft gezet. */
export const csrfToken = (
  cookies: string = document.cookie,
): string | undefined => {
  const gevonden = cookies
    .split(";")
    .map((deel) => deel.trim())
    .find((deel) => deel.startsWith(`${CSRF_COOKIE}=`));
  return gevonden
    ? decodeURIComponent(gevonden.substring(CSRF_COOKIE.length + 1))
    : undefined;
};

/** De header die elke muterende aanroep meestuurt (double submit); leeg zolang er geen token is. */
export const csrfHeaders = (cookies?: string): Record<string, string> => {
  const token = csrfToken(cookies);
  return token ? { [CSRF_HEADER]: token } : {};
};

export class SessieOphalenMislukt extends Error {}

/** Haalt de sessiestatus op. Het antwoord wordt nooit gecachet; een andere uitkomst dan 200 is een fout. */
export const haalSessie = async (
  haal: typeof fetch = fetch,
  pad: string = SESSIE_PAD,
): Promise<SessieStatus> => {
  const response = await haal(pad, {
    credentials: "same-origin",
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  if (!response.ok)
    throw new SessieOphalenMislukt(`Sessie ophalen gaf ${response.status}`);
  return (await response.json()) as SessieStatus;
};

/** Een pad binnen het portaal waar na het inloggen naartoe wordt teruggekeerd; alles anders valt weg. */
export const veiligReturnPad = (pad: string): string | undefined =>
  /^\/(?!\/)[A-Za-z0-9/_.~%=&?-]*$/.test(pad) ? pad : undefined;

/** De URL om in te loggen met een inlogmiddel; de backend controleert het middel en het return-pad opnieuw. */
export const loginUrl = (
  loginPad: string,
  inlogmiddel: string,
  returnTo?: string,
): string => {
  const parameters = new URLSearchParams({ inlogmiddel });
  const veilig = returnTo ? veiligReturnPad(returnTo) : undefined;
  if (veilig && veilig !== "/") parameters.set("returnTo", veilig);
  return `${loginPad}?${parameters.toString()}`;
};

/**
 * Uitloggen is alleen een `POST` met CSRF-token. Een formulier (geen `fetch`), want het antwoord is een redirect naar de
 * Keycloak-sessie die ook moet eindigen en die een `fetch` niet kan volgen.
 */
export const logoutViaFormulier = (doc: Document = document): void => {
  const formulier = doc.createElement("form");
  formulier.method = "post";
  formulier.action = UITLOG_PAD;
  const token = csrfToken(doc.cookie);
  if (token) {
    const veld = doc.createElement("input");
    veld.type = "hidden";
    veld.name = CSRF_PARAMETER;
    veld.value = token;
    formulier.appendChild(veld);
  }
  doc.body.appendChild(formulier);
  formulier.submit();
};
