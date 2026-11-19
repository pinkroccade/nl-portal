import http from "k6/http";
import { check, group, sleep } from "k6";
import type { Options } from "k6/options";
import { fetchToken } from "../lib/auth.ts";
import { gebruikerVoorVu, loadJourneyConfig } from "../lib/config.ts";
import { buildGraphqlRequest, eersteZaakUuid, graphqlErrors } from "../lib/graphql.ts";
import { kiesProfiel } from "../lib/profielen.ts";
import { journeyOperaties } from "../lib/queries.ts";

// Read-only journeys van een ingelogde burger: inloggen, zakenlijst, zaakdetail (met documenten en
// statussen), taken en profiel (BRP). Het profiel (PERF_PROFILE: smoke, load of stress) bepaalt de belasting.
export const options: Options = kiesProfiel(__ENV.PERF_PROFILE ?? "smoke", __ENV);

const { config, pool } = loadJourneyConfig(__ENV);
const denktijd = Number(__ENV.PERF_THINK_TIME ?? "1");
const metProfiel = (__ENV.PERF_BRP ?? "true") !== "false";

function bevraag(operatie: keyof typeof journeyOperaties, token: string, variables: object = {}) {
  const request = buildGraphqlRequest(journeyOperaties[operatie], token, variables);
  const response = http.post(config.graphqlUrl, request.body, { headers: request.headers, tags: { name: operatie } });
  check(response, {
    [`${operatie}: HTTP 200`]: (r) => r.status === 200,
    [`${operatie}: zonder GraphQL-fouten`]: (r) => graphqlErrors(String(r.body)).length === 0,
  });
  return response;
}

export default function () {
  const gebruiker = gebruikerVoorVu(pool, __VU);
  let token = "";
  group("inloggen", () => {
    token = fetchToken({ ...config, ...gebruiker });
  });
  sleep(denktijd);

  let zaakUuid: string | undefined;
  group("zakenlijst", () => {
    zaakUuid = eersteZaakUuid(String(bevraag("getZaken", token).body));
  });
  sleep(denktijd);

  group("zaakdetail", () => {
    check(zaakUuid, { "zakenlijst bevat een zaak": (uuid) => uuid !== undefined });
    if (zaakUuid) {
      bevraag("getZaak", token, { id: zaakUuid });
    }
  });
  sleep(denktijd);

  group("taken", () => {
    bevraag("getTakenV2", token);
  });
  sleep(denktijd);

  if (metProfiel) {
    group("profiel", () => {
      bevraag("getPersoonV2", token);
    });
  }
}
