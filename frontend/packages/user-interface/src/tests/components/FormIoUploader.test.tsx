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
import { fireEvent, render, waitFor } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import FormIoUploader, {
  PortalFileUpload,
} from "../../components/formio/FormIoUploader";

describe("FormIoUploader", () => {
  it("is truthy", () => {
    expect(FormIoUploader).toBeTruthy();
  });
});

describe("PortalFileUpload", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  const onUploadStart = vi.fn();
  const onUploadEnd = vi.fn();
  const onChange = vi.fn();

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(document, "cookie", "get").mockReturnValue(
      "__Host-XSRF-TOKEN=token-1",
    );
    sessionStorage.setItem("REST_URI", "/api");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    onUploadStart.mockClear();
    onUploadEnd.mockClear();
    onChange.mockClear();
    sessionStorage.clear();
  });

  const toon = () =>
    render(
      <IntlProvider locale="nl" messages={{}} onError={() => {}}>
        <PortalFileUpload
          id="upload"
          context={{ zaak: { id: "z1" } }}
          multiple
          onChange={onChange}
          onUploadStart={onUploadStart}
          onUploadEnd={onUploadEnd}
          informatieobjecttype="/type/{{ zaak.id }}"
          taakId="taak-1"
        />
      </IntlProvider>,
    );

  const kiesBestand = (container: HTMLElement) => {
    const invoer = container.querySelector(
      "input[type=file]",
    ) as HTMLInputElement;
    const bestand = new File(["abc"], "bijlage.txt", { type: "text/plain" });
    fireEvent.change(invoer, { target: { files: [bestand] } });
  };

  it("uploadt same-origin met het CSRF-token en zonder Authorization-header", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ url: "/doc/1" }), { status: 200 }),
    );
    const { container } = toon();

    kiesBestand(container);

    await waitFor(() => expect(onUploadEnd).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/taak/taak-1/document/content");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("same-origin");
    expect(init.headers).toEqual({ "X-XSRF-TOKEN": "token-1" });
    expect((init.body as FormData).get("informatieobjecttype")).toBe(
      "/type/z1",
    );
    expect(onUploadStart).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(onChange).toHaveBeenLastCalledWith([
        expect.objectContaining({ url: "/doc/1", isUploaded: true }),
      ]),
    );
  });

  it("verwijdert het bestand weer bij een mislukte upload", async () => {
    fetchMock.mockResolvedValue(new Response("nee", { status: 403 }));
    const { container } = toon();

    kiesBestand(container);

    await waitFor(() => expect(onUploadEnd).toHaveBeenCalled());
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith([]));
  });

  it("verwerkt een netwerkfout als mislukte upload", async () => {
    fetchMock.mockRejectedValue(new TypeError("netwerk"));
    const { container } = toon();

    kiesBestand(container);

    await waitFor(() => expect(onUploadEnd).toHaveBeenCalled());
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith([]));
  });
});
