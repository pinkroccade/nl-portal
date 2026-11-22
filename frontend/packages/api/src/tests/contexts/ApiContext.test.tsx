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
import { act } from "react";
import { createRoot, Root } from "react-dom/client";
import { gql } from "@apollo/client";
import { useApolloClient } from "@apollo/client/react";
import { SessionContext } from "@nl-portal/nl-portal-authentication";
import { ApiProvider } from "../../contexts/ApiContext";

(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

const QUERY = gql`
  query Test {
    ping
  }
`;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

describe("ApiProvider", () => {
  let container: HTMLDivElement;
  let root: Root;
  let markeerVerlopen: ReturnType<typeof vi.fn>;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    markeerVerlopen = vi.fn();
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    // jsdom bewaart een __Host--cookie niet over http: lees het token uit een stub.
    vi.spyOn(document, "cookie", "get").mockReturnValue(
      "x=1; __Host-XSRF-TOKEN=abc%3D123",
    );
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  const voerQueryUit = async () => {
    let uitkomst: Promise<unknown> = Promise.resolve();
    const Kind = () => {
      const client = useApolloClient();
      uitkomst = client
        .query({ query: QUERY, fetchPolicy: "no-cache" })
        .catch(() => undefined);
      return null;
    };
    await act(async () => {
      root.render(
        <SessionContext.Provider
          value={
            {
              markeerVerlopen,
              logout: () => {},
            } as never
          }
        >
          <ApiProvider graphqlUri="/graphql/" restUri="/api/">
            <Kind />
          </ApiProvider>
        </SessionContext.Provider>,
      );
    });
    await act(async () => {
      await uitkomst;
    });
  };

  it("stuurt same-origin met het CSRF-token in de X-XSRF-TOKEN-header", async () => {
    fetchMock.mockResolvedValue(json({ data: { ping: "pong" } }));

    await voerQueryUit();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/graphql");
    expect(init.credentials).toBe("same-origin");
    expect(init.headers["X-XSRF-TOKEN"]).toBe("abc=123");
    expect(markeerVerlopen).not.toHaveBeenCalled();
  });

  it("zet het REST-pad zonder slot-slash in de sessionStorage", async () => {
    fetchMock.mockResolvedValue(json({ data: { ping: "pong" } }));

    await voerQueryUit();

    expect(sessionStorage.getItem("REST_URI")).toBe("/api");
  });

  it("markeert de sessie als verlopen bij een 401", async () => {
    fetchMock.mockResolvedValue(json({}, 401));

    await voerQueryUit();

    expect(markeerVerlopen).toHaveBeenCalledTimes(1);
  });

  it("markeert de sessie als verlopen bij een GraphQL-fout UNAUTHENTICATED", async () => {
    fetchMock.mockResolvedValue(
      json({
        data: null,
        errors: [
          {
            message: "Niet geauthenticeerd",
            extensions: { code: "UNAUTHENTICATED" },
          },
        ],
      }),
    );

    await voerQueryUit();

    expect(markeerVerlopen).toHaveBeenCalledTimes(1);
  });

  it("negeert andere GraphQL-fouten", async () => {
    fetchMock.mockResolvedValue(
      json({
        data: null,
        errors: [{ message: "Kapot", extensions: { code: "INTERNAL_ERROR" } }],
      }),
    );

    await voerQueryUit();

    expect(markeerVerlopen).not.toHaveBeenCalled();
  });

  it("geeft het antwoord ongeschonden door als het geen JSON is", async () => {
    fetchMock.mockResolvedValue(new Response("geen json", { status: 200 }));

    await voerQueryUit();

    expect(markeerVerlopen).not.toHaveBeenCalled();
  });
});
