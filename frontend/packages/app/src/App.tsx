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
import "@gemeente-denhaag/design-tokens";
import "@nl-portal/nl-portal-user-interface/index.css";
import "./styles/nl-portal-design-tokens.css";
import { SessionProvider } from "@nl-portal/nl-portal-authentication";
import { LocalizationProvider } from "@nl-portal/nl-portal-localization";
import { ApiProvider } from "@nl-portal/nl-portal-api";
import {
  AppProvider,
  NotificationProvider,
  UserProvider,
} from "@nl-portal/nl-portal-user-interface";
import { CUSTOM_MESSAGES } from "./i18n/custom-messages/custom-messages";
import { config } from "./constants/config";
import { ScrollRestoration } from "react-router";
import CustomLayout from "./components/CustomLayout.tsx";

const App = () => {
  return (
    <div>
      <LocalizationProvider customMessages={CUSTOM_MESSAGES}>
        <SessionProvider
          autoIdleSessionLogout={config.AUTO_IDLE_SESSION_LOGOUT !== "false"}
          idleTimeoutMinutes={
            config.IDLE_TIMEOUT_MINUTES
              ? Number(config.IDLE_TIMEOUT_MINUTES)
              : undefined
          }
        >
          <ApiProvider
            graphqlUri={config.GRAPHQL_URI}
            restUri={config.REST_URI}
          >
            <NotificationProvider>
              <UserProvider>
                <AppProvider>
                  <CustomLayout />
                </AppProvider>
              </UserProvider>
            </NotificationProvider>
          </ApiProvider>
        </SessionProvider>
      </LocalizationProvider>
      <ScrollRestoration />
    </div>
  );
};

export default App;
