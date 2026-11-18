import { test } from "node:test";
import assert from "node:assert/strict";
import { buildGraphqlRequest, eersteZaakUuid, graphqlErrors } from "./graphql.ts";

test("bouwt een JSON-request met bearer-token", () => {
  const request = buildGraphqlRequest("{ __typename }", "tok", { a: 1 });

  assert.deepEqual(JSON.parse(request.body), { query: "{ __typename }", variables: { a: 1 } });
  assert.equal(request.headers["Authorization"], "Bearer tok");
  assert.equal(request.headers["Content-Type"], "application/json");
});

test("een geldig antwoord heeft geen fouten", () => {
  assert.deepEqual(graphqlErrors('{"data":{"__typename":"Query"}}'), []);
});

test("GraphQL-fouten bij HTTP 200 tellen als fout", () => {
  assert.deepEqual(graphqlErrors('{"errors":[{"message":"kapot"}],"data":null}'), ["kapot"]);
});

test("een antwoord zonder data of fouten telt als fout", () => {
  assert.equal(graphqlErrors("{}").length, 1);
});

test("een antwoord dat geen JSON is telt als fout", () => {
  assert.equal(graphqlErrors("<html>502</html>").length, 1);
});

test("haalt de uuid van de eerste zaak uit een zakenlijst", () => {
  const antwoord = '{"data":{"getZaken":{"content":[{"uuid":"aaa"},{"uuid":"bbb"}]}}}';
  assert.equal(eersteZaakUuid(antwoord), "aaa");
});

test("een lege zakenlijst of ongeldig antwoord geeft geen zaak-uuid", () => {
  assert.equal(eersteZaakUuid('{"data":{"getZaken":{"content":[]}}}'), undefined);
  assert.equal(eersteZaakUuid("geen json"), undefined);
});

test("een request krijgt een W3C traceparent-header zodat de backend-trace te koppelen is", () => {
  const request = buildGraphqlRequest("{ __typename }", "tok");
  assert.match(request.headers["traceparent"] ?? "", /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
});

test("elk request krijgt een eigen trace-id", () => {
  const a = buildGraphqlRequest("{ a }", "tok").headers["traceparent"];
  const b = buildGraphqlRequest("{ a }", "tok").headers["traceparent"];
  assert.notEqual(a, b);
});
