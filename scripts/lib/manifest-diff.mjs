// Bouwt het PR-comment met de diff van de gerenderde chart-manifests (main tegen PR). Geen I/O; zie
// mise-taak helm:manifest-diff voor het renderen en .github/workflows/ci.yml voor het plaatsen.

export const MARKER = "<!-- nl-portal-helm-manifest-diff -->";
const MAX_COMMENT = 60000; // GitHub staat 65536 tekens toe; houd ruimte voor de kop en de afsluiting.

export function renderDiffComment(sections) {
  const changed = sections.filter((s) => s.diff.trim() !== "");
  const lines = [MARKER, "## Manifest-diff van de Helm-chart", ""];
  if (changed.length === 0) {
    lines.push("Geen verschil in de gerenderde manifests ten opzichte van `main`.");
    return lines.join("\n") + "\n";
  }
  lines.push("Verschil in de gerenderde manifests (`helm template`) ten opzichte van `main`, per CI-scenario.", "");
  for (const s of sections.filter((s) => s.diff.trim() === "")) {
    lines.push(`- \`${s.scenario}\`: geen verschil`);
  }
  const budget = Math.floor((MAX_COMMENT - lines.join("\n").length) / changed.length) - 200;
  for (const s of changed) {
    const diff = s.diff.length > budget ? `${s.diff.slice(0, budget)}\n... (afgekapt)` : s.diff.replace(/\n$/, "");
    lines.push("", "<details>", `<summary><code>${s.scenario}</code></summary>`, "", "```diff", diff, "```", "", "</details>");
  }
  return lines.join("\n") + "\n";
}
