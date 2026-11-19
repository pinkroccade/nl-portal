export interface GraphqlRequest {
  body: string;
  headers: Record<string, string>;
}

export function buildGraphqlRequest(query: string, token: string, variables: object = {}): GraphqlRequest {
  return {
    body: JSON.stringify({ query, variables }),
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, traceparent: maakTraceparent() },
  };
}

/** Geeft de foutmeldingen van een GraphQL-antwoord; een leeg resultaat betekent dat het antwoord goed is. */
export function graphqlErrors(responseBody: string): string[] {
  let parsed: { data?: unknown; errors?: { message?: string }[] };
  try {
    parsed = JSON.parse(responseBody);
  } catch {
    return ["antwoord is geen JSON"];
  }
  if (parsed.errors?.length) {
    return parsed.errors.map((fout) => fout.message ?? "onbekende GraphQL-fout");
  }
  if (parsed.data == null) {
    return ["antwoord bevat geen data"];
  }
  return [];
}

/** UUID van de eerste zaak in een `getZaken`-antwoord; `undefined` bij een lege lijst of ongeldig antwoord. */
export function eersteZaakUuid(responseBody: string): string | undefined {
  try {
    return JSON.parse(responseBody)?.data?.getZaken?.content?.[0]?.uuid ?? undefined;
  } catch {
    return undefined;
  }
}

const hex = (lengte: number) => Array.from({ length: lengte }, () => Math.floor(Math.random() * 16).toString(16)).join("");

/** W3C traceparent met een vers trace-id (sampled), zodat een k6-verzoek in Tempo terug te vinden is. */
export const maakTraceparent = () => `00-${hex(32)}-${hex(16)}-01`;
