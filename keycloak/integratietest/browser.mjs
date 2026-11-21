// Een minimale browser voor de BFF-logintest: node:http met cookiejar per hostnaam, handmatige redirects en een
// routetabel die een publiek hostadres (zoals nginx dat doorgeeft) naar het echte adres wijst met behoud van de Host-header.
import http from "node:http";

export class Browser {
  /** @param {Record<string, {host: string, port: number}>} routes sleutel is `hostnaam:poort` zoals in de URL; waarde is waar de verbinding heen gaat. */
  constructor(routes) {
    this.routes = routes;
    this.cookies = new Map(); // `${hostnaam}|${naam}` -> {naam, waarde, secure, httpOnly, sameSite, path, raw}
    this.log = [];
  }

  cookieHeader(url) {
    const u = new URL(url);
    return [...this.cookies.values()]
      .filter((c) => c.hostnaam === u.hostname && u.pathname.startsWith(c.path))
      .map((c) => `${c.naam}=${c.waarde}`)
      .join("; ");
  }

  opslaan(url, headers) {
    const hostnaam = new URL(url).hostname;
    for (const line of [].concat(headers["set-cookie"] ?? [])) {
      const [pair, ...attrs] = line.split(";").map((s) => s.trim());
      const eq = pair.indexOf("=");
      const naam = pair.slice(0, eq);
      const waarde = pair.slice(eq + 1);
      const attr = (n) => attrs.find((a) => a.toLowerCase().startsWith(`${n}=`))?.slice(n.length + 1);
      const sleutel = `${hostnaam}|${naam}`;
      if (waarde === "" || /^0$/.test(attr("max-age") ?? "") || (attr("expires") && Date.parse(attr("expires")) < Date.now())) {
        this.cookies.delete(sleutel);
        continue;
      }
      this.cookies.set(sleutel, {
        hostnaam,
        naam,
        waarde,
        path: attr("path") ?? "/",
        secure: attrs.some((a) => a.toLowerCase() === "secure"),
        httpOnly: attrs.some((a) => a.toLowerCase() === "httponly"),
        sameSite: attr("samesite"),
        raw: line,
      });
    }
  }

  cookie(hostnaam, naam) {
    return this.cookies.get(`${hostnaam}|${naam}`);
  }

  /** Eén verzoek zonder redirect te volgen. `headers` komen bovenop de standaardkoppen van een browser. */
  verzoek(url, { method = "GET", headers = {}, body } = {}) {
    const u = new URL(url);
    const route = this.routes[u.host] ?? { host: u.hostname, port: Number(u.port || 80) };
    return new Promise((resolve, reject) => {
      const req = http.request(
        { host: route.host, port: route.port, path: u.pathname + u.search, method, headers: { host: u.host, cookie: this.cookieHeader(url), ...headers } },
        (res) => {
          const stukken = [];
          res.on("data", (d) => stukken.push(d));
          res.on("end", () => {
            this.opslaan(url, res.headers);
            const tekst = Buffer.concat(stukken).toString("utf8");
            this.log.push(`${method} ${u.origin}${u.pathname} -> ${res.statusCode}`);
            resolve({ status: res.statusCode, headers: res.headers, tekst, url });
          });
        },
      );
      req.on("error", reject);
      if (body) req.write(body);
      req.end();
    });
  }

  /** Volgt redirects tot een URL waarvoor `stop(url)` waar is (die wordt dan niet opgehaald) of tot een niet-redirect. */
  async volg(url, opties = {}, { stop = () => false, max = 20 } = {}) {
    let huidig = url;
    let init = opties;
    for (let hop = 0; hop < max; hop++) {
      if (hop > 0 && stop(huidig)) return { gestopt: huidig };
      const res = await this.verzoek(huidig, init);
      if (res.status >= 300 && res.status < 400 && res.headers.location) {
        huidig = new URL(res.headers.location, huidig).toString();
        init = {};
        continue;
      }
      return res;
    }
    throw new Error(`Te veel doorverwijzingen: ${this.log.slice(-max).join(" | ")}`);
  }
}

const decodeHtml = (s) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#x3d;/gi, "=").replace(/&#61;/g, "=").replace(/&lt;/g, "<").replace(/&gt;/g, ">");

/** Het eerste formulier van een pagina: actie en de ingevulde velden. */
export function leesFormulier(html) {
  const form = html.match(/<form[^>]*action="([^"]*)"[^>]*>([\s\S]*?)<\/form>/i);
  if (!form) return null;
  const velden = {};
  for (const m of form[2].matchAll(/<input[^>]*>/gi)) {
    const naam = m[0].match(/name="([^"]*)"/i)?.[1];
    if (naam) velden[naam] = decodeHtml(m[0].match(/value="([^"]*)"/i)?.[1] ?? "");
  }
  return { actie: decodeHtml(form[1]), velden };
}

/** De doel-URL van een meta-refresh (de tussenpagina van de BFF na de callback). */
export function metaRefresh(html) {
  const m = html.match(/<meta[^>]*http-equiv="refresh"[^>]*content="[^"]*url=([^"]*)"/i);
  return m ? decodeHtml(m[1]) : null;
}

export function decodeJwt(jwt) {
  return JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString("utf8"));
}
