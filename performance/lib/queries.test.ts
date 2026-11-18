import { test } from "node:test";
import assert from "node:assert/strict";
import { journeyOperaties } from "./queries.ts";

test("elke journey-operatie is een query en geen mutatie (alleen lezen)", () => {
  for (const [naam, query] of Object.entries(journeyOperaties)) {
    assert.match(query, /^\s*query\b/, naam);
    assert.doesNotMatch(query, /mutation/i, naam);
  }
});

test("de operatienaam in de query komt overeen met de sleutel", () => {
  for (const [naam, query] of Object.entries(journeyOperaties)) {
    assert.match(query, new RegExp(String.raw`query\s+${naam}\b`), naam);
  }
});

test("het zaakdetail vraagt documenten en statussen op", () => {
  assert.match(journeyOperaties.getZaak, /documenten\s*\{/);
  assert.match(journeyOperaties.getZaak, /status\s*\{/);
  assert.match(journeyOperaties.getZaak, /statusGeschiedenis\s*\{/);
});
