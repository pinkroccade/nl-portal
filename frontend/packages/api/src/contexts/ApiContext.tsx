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
import { createContext, useContext, useMemo, useState } from "react";
import {
  ApolloClient,
  HttpLink,
  InMemoryCache,
  InMemoryCacheConfig,
} from "@apollo/client";
import { ApolloProvider } from "@apollo/client/react";
import {
  csrfHeaders,
  formatUrlTrailingSlash,
  SessionContext,
} from "@nl-portal/nl-portal-authentication";
import React from "react";
import { defaultInMemoryCacheOptions } from "../constants/apollo-cache";

interface ContextProps {
  restUri: string;
}

const ApiContext = createContext({} as ContextProps);

interface Props {
  children: React.ReactNode;
  graphqlUri: string;
  restUri: string;
  inMemoryCacheOptions?: InMemoryCacheConfig;
}

export const ApiProvider = ({
  children,
  graphqlUri,
  restUri,
  inMemoryCacheOptions = defaultInMemoryCacheOptions,
}: Props) => {
  const formattedGraphqlUri = formatUrlTrailingSlash(graphqlUri, false);
  const formattedRestUri = formatUrlTrailingSlash(restUri, false);
  const { markeerVerlopen } = useContext(SessionContext);

  // Same-origin met het sessiecookie; elke aanroep stuurt het CSRF-token mee. Een 401 betekent dat de sessie is verlopen:
  // de sessie-provider toont dan een melding en een weg terug naar inloggen.
  const link = useMemo(
    () =>
      new HttpLink({
        uri: formattedGraphqlUri,
        credentials: "same-origin",
        fetch: async (input, init) => {
          const response = await fetch(input, {
            ...init,
            headers: {
              ...(init?.headers as Record<string, string>),
              ...csrfHeaders(),
            },
          });
          if (response.status === 401) markeerVerlopen?.();
          return response;
        },
      }),
    [formattedGraphqlUri, markeerVerlopen],
  );

  const [client] = useState(
    () =>
      new ApolloClient({
        cache: new InMemoryCache(inMemoryCacheOptions),
        link,
      }),
  );

  sessionStorage.setItem("REST_URI", formattedRestUri);

  return (
    <ApiContext.Provider value={{ restUri: formattedRestUri }}>
      <ApolloProvider client={client}>{children}</ApolloProvider>
    </ApiContext.Provider>
  );
};

export default ApiContext;
