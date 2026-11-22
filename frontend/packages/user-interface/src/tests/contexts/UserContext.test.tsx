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
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { MockedProvider } from "@apollo/client/testing/react";
import { SessionContext } from "@nl-portal/nl-portal-authentication";
import {
  QUERY_GET_PERSOON_V2,
  GetBedrijfDocument,
  QUERY_GET_USER_DIGITALE_ADRESSEN,
} from "@nl-portal/nl-portal-api";
import { UserProvider } from "../../contexts/UserContext";

const contactMock = {
  request: { query: QUERY_GET_USER_DIGITALE_ADRESSEN, variables: {} },
  result: { data: { getUserDigitaleAdressen: [] } },
};

const renderUserProvider = (
  mocks: object[],
  sessie: { vertegenwoordigde: "burger" | "bedrijf"; namensEenAnder: boolean },
) =>
  render(
    <MockedProvider mocks={mocks}>
      <SessionContext.Provider
        value={{
          sessie: { ingelogd: true, ...sessie },
          isCompany: sessie.vertegenwoordigde === "bedrijf",
          isProxy: sessie.namensEenAnder,
          markeerVerlopen: () => {},
          logout: () => {},
        }}
      >
        <UserProvider>child</UserProvider>
      </SessionContext.Provider>
    </MockedProvider>,
  );

describe("UserProvider", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("reports a persoon fetch failure through console.error instead of an unhandled rejection (typescript:S9383 pattern)", async () => {
    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderUserProvider(
      [
        contactMock,
        {
          request: { query: QUERY_GET_PERSOON_V2, variables: {} },
          error: new Error("Network error"),
        },
      ],
      { vertegenwoordigde: "burger", namensEenAnder: true },
    );

    await waitFor(() => {
      expect(consoleErrorSpy).toHaveBeenCalledWith(
        "Error fetching persoon:",
        expect.anything(),
      );
    });
  });

  it("reports a bedrijf fetch failure through console.error instead of an unhandled rejection (typescript:S9383 pattern)", async () => {
    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderUserProvider(
      [
        contactMock,
        {
          request: { query: GetBedrijfDocument, variables: {} },
          error: new Error("Network error"),
        },
      ],
      { vertegenwoordigde: "bedrijf", namensEenAnder: false },
    );

    await waitFor(() => {
      expect(consoleErrorSpy).toHaveBeenCalledWith(
        "Error fetching bedrijf:",
        expect.anything(),
      );
    });
  });
});
