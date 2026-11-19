/**
 * Thresholds als pass/fail voor k6. De smoke-run is een functionele rooktest: elk falend verzoek faalt
 * de run, de latency-grens is bewust ruim. Echte performancedrempels volgen uit de baseline (#428).
 */
export const smokeThresholds: Record<string, string[]> = {
  http_req_failed: ["rate==0"],
  checks: ["rate==1"],
  http_req_duration: ["p(95)<2000"],
};

/** De GraphQL-operations van de read-only journeys; de `name`-tag van elk verzoek. */
export const OPERATIES = ["getZaken", "getZaak", "getTakenV2", "getPersoonV2"] as const;

/**
 * Thresholds voor de journeys, per GraphQL-operation. Voorlopig signalerend: de grenzen zijn ruim en
 * worden pas aangescherpt als de baseline en de variantie bekend zijn (ADR 0018).
 */
export function journeyThresholds(operaties: readonly string[]): Record<string, string[]> {
  const thresholds: Record<string, string[]> = {
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
    "http_req_duration{name:keycloak_token}": ["p(95)<2000"],
  };
  // Op elke submetric een threshold: alleen dan neemt k6 hem op in de summary-export (zie baseline.ts).
  for (const operatie of operaties) {
    thresholds[`http_req_duration{name:${operatie}}`] = ["p(95)<2000"];
    thresholds[`http_req_failed{name:${operatie}}`] = ["rate<0.01"];
    thresholds[`http_reqs{name:${operatie}}`] = ["count>0"];
  }
  return thresholds;
}
