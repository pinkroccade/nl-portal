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
import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { createMemoryRouter, RouterProvider } from "react-router";
import { SessionContext } from "@nl-portal/nl-portal-authentication";
import { LocaleContext } from "@nl-portal/nl-portal-localization";
import Header from "../../components/Header";
import AppContext from "../../contexts/AppContext";
import RouterContext from "../../contexts/RouterContext";
import UserContext from "../../contexts/UserContext";

describe("Header", () => {
  it("is truthy", () => {
    expect(Header).toBeTruthy();
  });

  it("logt uit via de sessie wanneer op uitloggen wordt geklikt", () => {
    const logout = vi.fn();
    const router = createMemoryRouter([
      {
        path: "/",
        element: (
          <Header
            paths={{ account: "/account" } as never}
            logo={{ href: "/" }}
          />
        ),
      },
    ]);

    render(
      <IntlProvider
        locale="nl"
        messages={{ "menu.logout": "Uitloggen" }}
        onError={() => {}}
      >
        <SessionContext.Provider
          value={{ logout, markeerVerlopen: () => {} } as never}
        >
          <LocaleContext.Provider
            value={{
              currentLocale: "nl-NL",
              supportedLocales: ["nl-NL"],
              hrefLang: "nl",
              setCurrentLocale: () => {},
            }}
          >
            <AppContext.Provider value={{ messagesCount: 0 } as never}>
              <RouterContext.Provider value={{ navigationItems: [] } as never}>
                <UserContext.Provider
                  value={{ username: "Jan", usernameVolmacht: "" } as never}
                >
                  <RouterProvider router={router} />
                </UserContext.Provider>
              </RouterContext.Provider>
            </AppContext.Provider>
          </LocaleContext.Provider>
        </SessionContext.Provider>
      </IntlProvider>,
    );

    fireEvent.click(screen.getByTitle("menu.welcome Jan"));
    fireEvent.click(screen.getAllByText("Uitloggen")[0]);

    expect(logout).toHaveBeenCalledTimes(1);
  });
});
