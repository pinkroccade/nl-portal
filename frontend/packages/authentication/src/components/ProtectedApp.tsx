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
import { PropsWithChildren, useContext, useState } from "react";
import IdleTimer from "./IdleTimer";
import { Paragraph } from "@utrecht/component-library-react";
import { FormattedMessage, useIntl } from "react-intl";
import { Modal } from "@gemeente-denhaag/modal";
import SessionContext from "../session/SessionContext";

export type ProtectedAppProps = {
  autoIdleSessionLogout?: boolean;
  idleTimeoutMinutes?: number;
  showAutomaticLogoutWarning?: boolean;
};

/**
 * Wat alleen een ingelogde burger ziet: de kinderen, met de inactiviteitstimer. Na de inactiviteitstijd van de tenant
 * (hoogstens 15 minuten, DigiD-eis) logt de portal de burger uit; de backend handhaaft dezelfde tijd, dit is de waarschuwing.
 */
export const ProtectedApp = ({
  autoIdleSessionLogout = true,
  idleTimeoutMinutes = 15,
  showAutomaticLogoutWarning = true,
  children,
}: PropsWithChildren<ProtectedAppProps>) => {
  const { logout } = useContext(SessionContext);
  const [showLogoutWarning, setShowLogoutWarning] = useState(false);
  const intl = useIntl();
  const waarschuwingNa = idleTimeoutMinutes - 5;

  return (
    <>
      {autoIdleSessionLogout && (
        <>
          <IdleTimer
            idleTimeoutMinutes={idleTimeoutMinutes}
            onTimeOut={logout}
          />
          {showAutomaticLogoutWarning && waarschuwingNa >= 0 && (
            <IdleTimer
              idleTimeoutMinutes={waarschuwingNa}
              onTimeOut={() => setShowLogoutWarning(true)}
            />
          )}
          {showLogoutWarning && (
            <Modal
              open={showLogoutWarning}
              title={intl.formatMessage({ id: "auth.inactive.title" })}
              closeLabel={intl.formatMessage({ id: "auth.inactive.close" })}
              onToggle={(open) => setShowLogoutWarning(open)}
              actions={(toggle) => [
                {
                  children: intl.formatMessage({
                    id: "auth.inactive.logout",
                  }),
                  variant: "secondary-action",
                  onClick: () => logout(),
                },
                {
                  children: intl.formatMessage({
                    id: "auth.inactive.stayLoggedIn",
                  }),
                  variant: "primary-action",
                  onClick: () => {
                    setShowLogoutWarning(false);
                    toggle();
                  },
                },
              ]}
            >
              <Paragraph>
                <FormattedMessage id="auth.inactive.text" />
              </Paragraph>
            </Modal>
          )}
        </>
      )}
      {children}
    </>
  );
};

export default ProtectedApp;
