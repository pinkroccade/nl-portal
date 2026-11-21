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
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { FormattedMessage } from "react-intl";
import ProtectedApp from "../components/ProtectedApp";
import {
  haalSessie,
  loginUrl,
  logoutViaFormulier,
  type SessieStatus,
} from "./sessie";

export type SessionContextInterface = {
  sessie: SessieStatus;
  isCompany: boolean;
  isProxy: boolean;
  /** De backend gaf 401: de sessie is verlopen. De provider toont dan de melding en een weg terug naar inloggen. */
  markeerVerlopen: () => void;
  logout: () => void;
};

const SessionContext = createContext<SessionContextInterface>(
  {} as SessionContextInterface,
);

export const useSession = () => useContext(SessionContext);

export type SessionProviderProps = {
  children: React.ReactNode;
  autoIdleSessionLogout?: boolean;
  /** Valt terug op de inactiviteitstijd van de tenant uit de sessie, en anders op 15 minuten. */
  idleTimeoutMinutes?: number;
  /** Alleen voor tests. */
  haalStatus?: () => Promise<SessieStatus>;
  navigeer?: (url: string) => void;
};

type Fase = "laden" | "ingelogd" | "anoniem" | "fout";
type Melding = "mislukt" | "verlopen" | "fout";

const huidigPad = () => window.location.pathname + window.location.search;

const LoginPagina = ({
  sessie,
  melding,
  navigeer,
}: {
  sessie?: SessieStatus;
  melding?: Melding;
  navigeer: (url: string) => void;
}) => (
  <main>
    <h1>
      <FormattedMessage id="auth.login.title" />
    </h1>
    {melding && (
      <p role="alert">
        <FormattedMessage id={`auth.login.${melding}`} />
      </p>
    )}
    {sessie?.loginPad &&
      sessie.inlogmiddelen?.map((middel) => (
        <p key={middel}>
          <button
            type="button"
            onClick={() =>
              navigeer(loginUrl(sessie.loginPad!, middel, huidigPad()))
            }
          >
            <FormattedMessage
              id={`auth.login.button.${middel}`}
              defaultMessage={middel}
            />
          </button>
        </p>
      ))}
  </main>
);

/**
 * Vervangt de OIDC-afhandeling in de browser: de gebruikersstatus komt uit het sessie-endpoint. Niet ingelogd en een enkel
 * ingeschakeld inlogmiddel: meteen naar de backend. Meerdere middelen, een mislukte login of een verlopen sessie: een melding en
 * een knop per middel (de volwaardige inlogpagina volgt in #485).
 */
export const SessionProvider = ({
  children,
  autoIdleSessionLogout,
  idleTimeoutMinutes,
  haalStatus = () => haalSessie(),
  navigeer = (url) => window.location.assign(url),
}: SessionProviderProps) => {
  const [fase, setFase] = useState<Fase>("laden");
  const [sessie, setSessie] = useState<SessieStatus>();
  const [verlopen, setVerlopen] = useState(false);

  useEffect(() => {
    let actief = true;
    haalStatus()
      .then((status) => {
        if (!actief) return;
        setSessie(status);
        setFase(status.ingelogd ? "ingelogd" : "anoniem");
      })
      .catch(() => {
        if (actief) setFase("fout");
      });
    return () => {
      actief = false;
    };
  }, []);

  const markeerVerlopen = useCallback(() => setVerlopen(true), []);
  const mislukt =
    new URLSearchParams(window.location.search).get("inloggen") === "mislukt";
  const enkelMiddel =
    sessie?.loginPad && sessie.inlogmiddelen?.length === 1
      ? sessie.inlogmiddelen[0]
      : undefined;

  useEffect(() => {
    if (fase === "anoniem" && !verlopen && !mislukt && enkelMiddel) {
      navigeer(loginUrl(sessie!.loginPad!, enkelMiddel, huidigPad()));
    }
  }, [fase, verlopen, mislukt, enkelMiddel]);

  const waarde = useMemo<SessionContextInterface | undefined>(
    () =>
      sessie?.ingelogd
        ? {
            sessie,
            isCompany: sessie.vertegenwoordigde === "bedrijf",
            isProxy: sessie.namensEenAnder === true,
            markeerVerlopen,
            logout: () => logoutViaFormulier(),
          }
        : undefined,
    [sessie, markeerVerlopen],
  );

  if (fase === "laden") return null;
  if (fase === "fout")
    return <LoginPagina melding="fout" sessie={sessie} navigeer={navigeer} />;
  if (verlopen)
    return (
      <LoginPagina melding="verlopen" sessie={sessie} navigeer={navigeer} />
    );
  if (fase === "anoniem") {
    if (!mislukt && enkelMiddel) return null;
    return (
      <LoginPagina
        melding={mislukt ? "mislukt" : undefined}
        sessie={sessie}
        navigeer={navigeer}
      />
    );
  }
  if (!waarde) return null;

  const idleMinuten =
    idleTimeoutMinutes ??
    (sessie?.inactiviteitSeconden
      ? Math.floor(sessie.inactiviteitSeconden / 60)
      : undefined);

  return (
    <SessionContext.Provider value={waarde}>
      <ProtectedApp
        autoIdleSessionLogout={autoIdleSessionLogout}
        idleTimeoutMinutes={idleMinuten}
      >
        {children}
      </ProtectedApp>
    </SessionContext.Provider>
  );
};

export default SessionContext;
