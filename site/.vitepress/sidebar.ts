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

// Sidebar per section. Directory-shaped content (ADR's, arc42, workflows, release notes) is read
// from disk so a new page can't be forgotten; hand-picked sections have an explicit order.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

export const SITE_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

export interface SidebarItem {
  text: string;
  link?: string;
  items?: SidebarItem[];
  collapsed?: boolean;
}

function pageTitle(file: string): string {
  const h1 = readFileSync(file, "utf8").match(/^#\s+(.+?)\s*$/m);
  return (h1 ? h1[1] : file).replace(/[*`]/g, "");
}

function link(file: string): string {
  const rel = relative(SITE_ROOT, file).replaceAll("\\", "/").replace(/\.md$/, "");
  return "/" + rel.replace(/(^|\/)index$/, "$1");
}

/** Pages directly in `dir` (index first), then sub-directories as collapsed groups. */
function dirItems(dir: string, exclude: string[] = []): SidebarItem[] {
  const abs = join(SITE_ROOT, dir);
  const entries = readdirSync(abs).sort((a, b) => a.localeCompare(b, "en", { numeric: true }));
  const pages = entries.filter((e) => e.endsWith(".md") && statSync(join(abs, e)).isFile());
  const ordered = [...pages.filter((p) => p === "index.md"), ...pages.filter((p) => p !== "index.md")];
  const items: SidebarItem[] = ordered.map((p) => ({
    text: p === "index.md" ? "Overzicht" : pageTitle(join(abs, p)),
    link: link(join(abs, p)),
  }));
  for (const sub of entries.filter((e) => statSync(join(abs, e)).isDirectory() && e !== "img" && !exclude.includes(e))) {
    const children = dirItems(`${dir}/${sub}`);
    if (children.length > 0) items.push({ text: sub, collapsed: true, items: children });
  }
  return items;
}

const page = (dir: string, file: string): SidebarItem => ({ text: pageTitle(join(SITE_ROOT, dir, file)), link: link(join(SITE_ROOT, dir, file)) });

/** Kubernetes-sectie: index, dan tutorial, how-to's, referentie en uitleg, op bestandsvoorvoegsel. */
function kubernetesItems(): SidebarItem[] {
  const dir = "hosting-en-configuratie/kubernetes";
  const files = readdirSync(join(SITE_ROOT, dir)).filter((f) => f.endsWith(".md") && f !== "index.md").sort((a, b) => a.localeCompare(b, "en"));
  const group = (text: string, prefix: string): SidebarItem => ({
    text,
    collapsed: false,
    items: files.filter((f) => f.startsWith(prefix)).map((f) => page(dir, f)),
  });
  return [
    { text: "Overzicht", link: "/hosting-en-configuratie/kubernetes/" },
    group("Tutorial", "tutorial-"),
    group("How-to's", "how-to-"),
    group("Referentie", "referentie-"),
    group("Uitleg", "uitleg-"),
  ];
}

export const sidebar: Record<string, SidebarItem[]> = {
  "/architectuur/": [
    {
      text: "Architectuur",
      items: [
        { text: "Wat is NL Portal", link: "/architectuur/" },
        page("architectuur", "applicatiearchitectuur.md"),
        page("architectuur", "5-lagenmodel.md"),
        page("architectuur", "principes.md"),
        page("architectuur", "authentication-en-authorization.md"),
        page("architectuur", "c4-model.md"),
        page("architectuur", "regeldekking.md"),
      ],
    },
    { text: "Integraties", collapsed: true, items: dirItems("architectuur/integraties") },
    { text: "Patronen", collapsed: true, items: dirItems("architectuur/patronen") },
    { text: "arc42", collapsed: true, items: dirItems("architectuur/arc42") },
  ],
  "/gebruik-en-inrichting/": [{ text: "Gebruik & inrichting", items: dirItems("gebruik-en-inrichting") }],
  "/hosting-en-configuratie/": [
    {
      text: "Hosting & configuratie",
      items: [
        ...dirItems("hosting-en-configuratie", ["kubernetes"]),
        // Kubernetes: de pagina's staan per Diátaxis-type bij elkaar (de titel van elke pagina toont het type).
        { text: "Kubernetes (Helm)", collapsed: false, items: kubernetesItems() },
      ],
    },
  ],
  "/development/": [
    {
      text: "Development",
      items: [
        { text: "Overzicht", link: "/development/" },
        page("development", "glossarium.md"),
        page("development", "repositories.md"),
        page("development", "best-practices.md"),
        page("development", "demo-fixtures.md"),
      ],
    },
    { text: "Workflows", collapsed: true, items: dirItems("development/workflows") },
    { text: "ADR's", collapsed: true, items: dirItems("development/adr") },
    { text: "Productmanagement", collapsed: true, items: dirItems("development/productmanagement") },
    { text: "Release-notes-archief", collapsed: true, items: dirItems("development/release-notes") },
  ],
};
