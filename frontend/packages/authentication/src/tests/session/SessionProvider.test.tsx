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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, useContext } from "react";
import { createRoot, Root } from "react-dom/client";
import { IntlProvider } from "react-intl";
import SessionContext, { SessionProvider } from "../../session/SessionContext";
import type { SessieStatus } from "../../session/sessie";

(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;
HTMLDialogElement.prototype.showModal = function () {
  this.setAttribute("open", "");
};
HTMLDialogElement.prototype.close = function () {
  this.removeAttribute("open");
};

const ingelogd: SessieStatus = {
  ingelogd: true,
  vertegenwoordigde: "bedrijf",
  namensEenAnder: true,
  inlogmiddel: "digid",
  inactiviteitSeconden: 900,
};
const anoniem = (inlogmiddelen: string[]): SessieStatus => ({
  ingelogd: false,
  loginPad: "/oauth2/authorization/00000099000000000001",
  inlogmiddelen,
});

describe("SessionProvider", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    window.history.replaceState({}, "", "/zaken");
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    vi.restoreAllMocks();
  });

  const render = async (
    status: Promise<SessieStatus>,
    navigeer = vi.fn(),
    kinderen: React.ReactNode = "kind",
  ) => {
    await act(async () => {
      root.render(
        <IntlProvider locale="nl" messages={{}} onError={() => {}}>
          <SessionProvider
            haalStatus={() => status}
            navigeer={navigeer}
            autoIdleSessionLogout={false}
          >
            {kinderen}
          </SessionProvider>
        </IntlProvider>,
      );
      await status.catch(() => {});
    });
    return navigeer;
  };

  it("toont niets zolang de sessie wordt opgehaald", () => {
    act(() => {
      root.render(
        <IntlProvider locale="nl" messages={{}} onError={() => {}}>
          <SessionProvider haalStatus={() => new Promise(() => {})}>
            kind
          </SessionProvider>
        </IntlProvider>,
      );
    });

    expect(container.textContent).toBe("");
  });

  it("toont de kinderen voor een ingelogde burger en geeft type vertegenwoordigde en namens-een-ander door", async () => {
    const Lezer = () => {
      const { isCompany, isProxy, sessie } = useContext(SessionContext);
      return <span>{`${isCompany}-${isProxy}-${sessie.inlogmiddel}`}</span>;
    };

    await render(Promise.resolve(ingelogd), vi.fn(), <Lezer />);

    expect(container.textContent).toBe("true-true-digid");
  });

  it("stuurt een anonieme gebruiker met een enkel inlogmiddel meteen naar de backend, met het huidige pad", async () => {
    const navigeer = await render(Promise.resolve(anoniem(["digid"])));

    expect(navigeer).toHaveBeenCalledWith(
      "/oauth2/authorization/00000099000000000001?inlogmiddel=digid&returnTo=%2Fzaken",
    );
    expect(container.textContent).toBe("");
  });

  it("toont een knop per inlogmiddel als er meer zijn", async () => {
    const navigeer = await render(Promise.resolve(anoniem(["digid", "test"])));

    const knoppen = Array.from(container.querySelectorAll("button"));
    expect(knoppen.map((k) => k.textContent)).toEqual(["digid", "test"]);
    expect(navigeer).not.toHaveBeenCalled();

    act(() => knoppen[1].click());

    expect(navigeer).toHaveBeenCalledWith(
      "/oauth2/authorization/00000099000000000001?inlogmiddel=test&returnTo=%2Fzaken",
    );
  });

  it("toont na een mislukte login een melding in plaats van opnieuw door te sturen", async () => {
    window.history.replaceState({}, "", "/?inloggen=mislukt");

    const navigeer = await render(Promise.resolve(anoniem(["digid"])));

    expect(navigeer).not.toHaveBeenCalled();
    expect(container.querySelector("[role=alert]")).not.toBeNull();
    expect(container.querySelectorAll("button")).toHaveLength(1);
  });

  it("toont een melding als de sessie niet op te halen is", async () => {
    await render(Promise.reject(new Error("netwerk")));

    expect(container.querySelector("[role=alert]")).not.toBeNull();
  });

  it("toont een melding en een weg terug naar inloggen zodra de backend 401 geeft", async () => {
    let verlopen: () => void = () => {};
    const Verloop = () => {
      verlopen = useContext(SessionContext).markeerVerlopen;
      return <span>pagina</span>;
    };
    await render(
      Promise.resolve({
        ...ingelogd,
        loginPad: "/oauth2/authorization/1",
        inlogmiddelen: ["digid"],
      }),
      vi.fn(),
      <Verloop />,
    );
    expect(container.textContent).toBe("pagina");

    act(() => verlopen());

    expect(container.querySelector("[role=alert]")).not.toBeNull();
    expect(container.textContent).not.toContain("pagina");
    expect(container.querySelectorAll("button")).toHaveLength(1);
  });
});
