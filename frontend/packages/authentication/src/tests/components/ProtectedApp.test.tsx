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
import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { act } from "react";
import { createRoot, Root } from "react-dom/client";
import { IntlProvider } from "react-intl";
import ProtectedApp from "../../components/ProtectedApp";
import SessionContext from "../../session/SessionContext";

// jsdom doesn't implement the <dialog> element's imperative API used by @gemeente-denhaag/modal.
(
  globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;
HTMLDialogElement.prototype.showModal = function () {
  this.setAttribute("open", "");
};
HTMLDialogElement.prototype.close = function () {
  this.removeAttribute("open");
};

const logoutMock = vi.fn();

describe("ProtectedApp", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    logoutMock.mockClear();
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  const render = (idleTimeoutMinutes: number, autoIdleSessionLogout = true) =>
    act(() => {
      root.render(
        <IntlProvider locale="en" messages={{}} onError={() => {}}>
          <SessionContext.Provider
            value={{
              sessie: { ingelogd: true },
              isCompany: false,
              isProxy: false,
              markeerVerlopen: () => {},
              logout: logoutMock,
            }}
          >
            <ProtectedApp
              idleTimeoutMinutes={idleTimeoutMinutes}
              autoIdleSessionLogout={autoIdleSessionLogout}
            >
              child
            </ProtectedApp>
          </SessionContext.Provider>
        </IntlProvider>,
      );
    });

  it("logs out through the session when the idle logout warning is confirmed", () => {
    vi.useFakeTimers();

    try {
      render(5);

      // the "show warning" IdleTimer fires after (idleTimeoutMinutes - 5) minutes, i.e. immediately.
      act(() => {
        vi.advanceTimersByTime(1);
      });

      const logoutButton = Array.from(
        container.querySelectorAll("button"),
      ).find((button) => button.textContent === "auth.inactive.logout");
      expect(logoutButton).toBeDefined();

      act(() => {
        logoutButton?.click();
      });

      expect(logoutMock).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("logs out automatically after the idle timeout", () => {
    vi.useFakeTimers();

    try {
      render(15);

      act(() => {
        vi.advanceTimersByTime(15 * 60 * 1000);
      });

      expect(logoutMock).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not run a timer when idle logout is switched off", () => {
    vi.useFakeTimers();

    try {
      render(1, false);

      act(() => {
        vi.advanceTimersByTime(60 * 60 * 1000);
      });

      expect(logoutMock).not.toHaveBeenCalled();
      expect(container.textContent).toBe("child");
    } finally {
      vi.useRealTimers();
    }
  });
});
