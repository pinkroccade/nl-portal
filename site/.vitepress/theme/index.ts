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

// The default theme hides `themeConfig.footer` on any page that has a sidebar, which is every page
// of the four main sections. Compliance is footer-only (ADR 0006), so render the whole footer (logo, link,
// copyright) here on all pages and hide the default one in custom.css to avoid a duplicate.

import { h } from "vue";
import DefaultTheme from "vitepress/theme";
import { withBase, type Theme } from "vitepress";
import { footerComplianceLink } from "../sections";
import "./custom.css";

export default {
  extends: DefaultTheme,
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      "layout-bottom": () =>
        h("footer", { class: "compliance-footer" }, [
          h("img", { class: "footer-logo footer-logo-light", src: withBase("/logo-light.svg"), alt: "Pink Common Ground" }),
          h("img", { class: "footer-logo footer-logo-dark", src: withBase("/logo-dark.svg"), alt: "Pink Common Ground" }),
          h("a", { href: withBase(footerComplianceLink.link) }, footerComplianceLink.text),
          h("span", { class: "footer-copyright" }, "PinkRoccade"),
        ]),
    }),
} satisfies Theme;
