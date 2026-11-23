/*
 * Copyright 2026 PinkRoccade Local Government B.V.
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
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  csrfHeaders,
  csrfToken,
  haalSessie,
  loginUrl,
  logoutViaFormulier,
  SessieOphalenMislukt,
  veiligReturnPad,
} from "../../session/sessie";

describe("csrfToken en csrfHeaders", () => {
  const cookies = "a=1; __Host-XSRF-TOKEN=abc%3D%3D; b=2";
  const locatie = {
    href: "https://portal.example/pagina",
    origin: "https://portal.example",
  };

  it("lezen het token uit het Host-cookie", () => {
    expect(csrfToken(cookies)).toBe("abc==");
    expect(
      csrfHeaders({ url: "/graphql", method: "POST" }, cookies, locatie),
    ).toEqual({ "X-XSRF-TOKEN": "abc==" });
  });

  it("geven niets terug zolang het cookie er niet is", () => {
    expect(csrfToken("a=1; XSRF-TOKEN=x")).toBeUndefined();
    expect(
      csrfHeaders({ url: "/graphql", method: "POST" }, "", locatie),
    ).toEqual({});
  });

  it("sturen het token niet mee bij GET, HEAD en OPTIONS", () => {
    for (const method of [undefined, "GET", "get", "HEAD", "OPTIONS"])
      expect(
        csrfHeaders({ url: "/api/bestand", method }, cookies, locatie),
      ).toEqual({});
  });

  it("sturen het token mee bij elke muterende methode op dezelfde origin, ook met een absolute URL", () => {
    for (const method of ["POST", "put", "PATCH", "DELETE"])
      expect(
        csrfHeaders(
          { url: "https://portal.example/api/x", method },
          cookies,
          locatie,
        ),
      ).toEqual({ "X-XSRF-TOKEN": "abc==" });
  });

  it("lekken het token niet naar een andere origin of een onleesbare URL", () => {
    expect(
      csrfHeaders(
        { url: "https://elders.example/upload", method: "POST" },
        cookies,
        locatie,
      ),
    ).toEqual({});
    expect(
      csrfHeaders(
        { url: "http://portal.example/upload", method: "POST" },
        cookies,
        locatie,
      ),
    ).toEqual({});
    expect(
      csrfHeaders({ url: "http://[", method: "POST" }, cookies, locatie),
    ).toEqual({});
  });
});

describe("haalSessie", () => {
  it("haalt de status same-origin en zonder cache op", async () => {
    const haal = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ ingelogd: true, vertegenwoordigde: "burger" }),
    });

    const status = await haalSessie(haal as unknown as typeof fetch);

    expect(status).toEqual({ ingelogd: true, vertegenwoordigde: "burger" });
    expect(haal).toHaveBeenCalledWith(
      "/api/public/sessie",
      expect.objectContaining({
        credentials: "same-origin",
        cache: "no-store",
      }),
    );
  });

  it("faalt bij een andere uitkomst dan 200", async () => {
    const haal = vi.fn().mockResolvedValue({ ok: false, status: 500 });

    await expect(
      haalSessie(haal as unknown as typeof fetch),
    ).rejects.toBeInstanceOf(SessieOphalenMislukt);
  });
});

describe("loginUrl en veiligReturnPad", () => {
  it("nemen alleen een relatief pad binnen het portaal mee", () => {
    expect(loginUrl("/oauth2/authorization/1", "digid", "/zaken?a=1")).toBe(
      "/oauth2/authorization/1?inlogmiddel=digid&returnTo=%2Fzaken%3Fa%3D1",
    );
    expect(loginUrl("/oauth2/authorization/1", "digid", "/")).toBe(
      "/oauth2/authorization/1?inlogmiddel=digid",
    );
    for (const slecht of [
      "https://evil.example",
      "//evil.example",
      "/\\evil",
      "javascript:alert(1)",
      "evil",
    ]) {
      expect(veiligReturnPad(slecht)).toBeUndefined();
      expect(loginUrl("/oauth2/authorization/1", "test", slecht)).toBe(
        "/oauth2/authorization/1?inlogmiddel=test",
      );
    }
  });
});

describe("logoutViaFormulier", () => {
  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("stuurt een POST naar /logout met het CSRF-token als formulierveld", () => {
    // jsdom bewaart een `__Host-`-cookie niet zonder https; daarom wordt het cookie gestubd.
    vi.spyOn(document, "cookie", "get").mockReturnValue(
      "__Host-XSRF-TOKEN=token-1",
    );
    const submit = vi
      .spyOn(HTMLFormElement.prototype, "submit")
      .mockImplementation(() => {});

    logoutViaFormulier();

    const formulier = document.querySelector("form")!;
    expect(formulier.method).toBe("post");
    expect(formulier.getAttribute("action")).toBe("/logout");
    expect(
      (formulier.querySelector("input[name=_csrf]") as HTMLInputElement).value,
    ).toBe("token-1");
    expect(submit).toHaveBeenCalled();
  });

  it("verstuurt ook zonder token (de backend weigert dat dan)", () => {
    const submit = vi
      .spyOn(HTMLFormElement.prototype, "submit")
      .mockImplementation(() => {});

    logoutViaFormulier();

    expect(document.querySelector("input[name=_csrf]")).toBeNull();
    expect(submit).toHaveBeenCalled();
  });
});
