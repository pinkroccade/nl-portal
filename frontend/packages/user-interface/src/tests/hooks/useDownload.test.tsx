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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { MouseEvent } from "react";
import useDownload from "../../hooks/useDownload";

const klik = () =>
  ({ preventDefault: vi.fn() }) as unknown as MouseEvent<HTMLElement>;

describe("useDownload", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let klikken: HTMLAnchorElement[];

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    klikken = [];
    vi.spyOn(document, "cookie", "get").mockReturnValue(
      "__Host-XSRF-TOKEN=token-1",
    );
    // jsdom weigert `view: window` van de hook als Window; de klik zelf is hier niet van belang.
    vi.stubGlobal("MouseEvent", class extends Event {});
    window.URL.createObjectURL = vi.fn(() => "blob:test");
    window.URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, "dispatchEvent").mockImplementation(
      function (this: HTMLAnchorElement) {
        klikken.push(this);
        return true;
      },
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("haalt same-origin op zonder CSRF-token (GET) en downloadt onder de bestandsnaam uit Content-Disposition", async () => {
    fetchMock.mockResolvedValue(
      new Response("inhoud", {
        status: 200,
        headers: { "Content-Disposition": 'attachment; filename="doc.pdf"' },
      }),
    );
    const { result } = renderHook(() => useDownload("/api/document/1"));
    const event = klik();

    await act(async () => {
      await result.current.onClick(event);
    });

    expect(event.preventDefault).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledWith("/api/document/1", {
      credentials: "same-origin",
      headers: {},
    });
    expect(klikken).toHaveLength(1);
    expect(klikken[0].download).toBe("doc.pdf");
    expect(klikken[0].href).toBe("blob:test");
    expect(result.current.loading).toBe(false);
  });

  it("geeft de meegegeven bestandsnaam voorrang", async () => {
    fetchMock.mockResolvedValue(
      new Response("inhoud", {
        headers: { "Content-Disposition": 'attachment; filename="doc.pdf"' },
      }),
    );
    const { result } = renderHook(() => useDownload("/x", "eigen.txt"));

    await act(async () => {
      await result.current.onClick(klik());
    });

    expect(klikken[0].download).toBe("eigen.txt");
  });

  it("downloadt niet bij een foutstatus", async () => {
    const fout = vi.spyOn(console, "error").mockImplementation(() => {});
    fetchMock.mockResolvedValue(new Response("nee", { status: 401 }));
    const { result } = renderHook(() => useDownload("/x", "a.txt"));

    await act(async () => {
      await result.current.onClick(klik());
    });

    expect(klikken).toHaveLength(0);
    expect(fout).toHaveBeenCalledWith("Failed to download file");
    expect(result.current.loading).toBe(false);
  });

  it("downloadt niet zonder bestandsnaam", async () => {
    const fout = vi.spyOn(console, "error").mockImplementation(() => {});
    fetchMock.mockResolvedValue(new Response("inhoud"));
    const { result } = renderHook(() => useDownload("/x"));

    await act(async () => {
      await result.current.onClick(klik());
    });

    expect(klikken).toHaveLength(0);
    expect(fout).toHaveBeenCalledWith("Failed to download file, no filename");
  });
});
