import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const conf = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", "frontend/packages/app/conf/nginx.conf"), "utf8");

/** De locations met hun inhoud (de config heeft geen geneste blokken in een location). */
const locations = [...conf.matchAll(/^ {4}location ([^{]+?) \{\r?\n([\s\S]*?)^ {4}\}/gm)].map(([, selector, body]) => ({ selector, body }));
const proxied = locations.filter(({ body }) => body.includes("proxy_pass"));
const csp = conf.match(/set \$csp "([^"]+)"/)[1];

test("de proxy-locations zijn geankerd en er zijn er drie", () => {
  assert.deepEqual(
    proxied.map((l) => l.selector),
    ["= /graphql", "^~ /api/", "~ ^/(oauth2|login|logout)(/|$)"],
  );
});

test("elke proxy-location overschrijft de door de client gezette doorstuurheaders", () => {
  for (const { selector, body } of proxied) {
    assert.match(body, /proxy_set_header Host \$http_host;/, selector);
    assert.match(body, /proxy_set_header X-Forwarded-Host \$http_host;/, selector);
    assert.match(body, /proxy_set_header Forwarded "";/, selector);
    assert.match(body, /proxy_set_header X-Forwarded-For \$remote_addr;/, selector);
  }
});

test("de CSP sluit frames, plug-ins, base-injectie en data: in default-src uit", () => {
  assert.match(csp, /(^|; )default-src 'self'(;|$)/);
  assert.doesNotMatch(csp.match(/default-src [^;]*/)[0], /data:/);
  for (const regel of ["object-src 'none'", "base-uri 'self'", "frame-ancestors 'none'", "connect-src 'self'"]) assert.ok(csp.includes(regel), regel);
});

test("de pagina's en de runtime-config dragen de beveiligingsheaders (add_header erft niet door)", () => {
  const eigen = locations.filter(({ body }) => body.includes("add_header"));
  assert.ok(eigen.length >= 2);
  for (const { selector, body } of eigen) {
    assert.match(body, /add_header X-Content-Type-Options "nosniff" always;/, selector);
    assert.match(body, /add_header Referrer-Policy "[^"]+" always;/, selector);
    assert.match(body, /add_header Strict-Transport-Security \$hsts always;/, selector);
  }
  assert.match(locations.find((l) => l.selector === "/").body, /add_header Content-Security-Policy "\$\{csp\}" always;/);
});

test("HSTS staat alleen aan als de ingress https meldt", () => {
  assert.match(conf, /map \$http_x_forwarded_proto \$hsts \{\s+default "";\s+https "max-age=\d+; includeSubDomains";/);
});
