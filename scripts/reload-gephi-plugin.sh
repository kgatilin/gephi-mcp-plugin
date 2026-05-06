#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gephi_bin="${GEPHI_BIN:-/Applications/Gephi.app/Contents/Resources/gephi/bin/gephi}"
gephi_userdir="${GEPHI_USERDIR:-$HOME/Library/Application Support/gephi/0.11}"
module_status="$gephi_userdir/config/Modules/org-kgatilin-gephi-gephi-mcp-plugin.xml"
workspace="${1:-${GEPHI_WORKSPACE:-}}"

if [[ ! -x "$gephi_bin" ]]; then
  echo "Gephi launcher not found: $gephi_bin" >&2
  echo "Set GEPHI_BIN to the Gephi launcher path." >&2
  exit 1
fi
if [[ -n "$workspace" && ! -f "$workspace" ]]; then
  echo "Workspace file not found: $workspace" >&2
  exit 1
fi

cd "$repo_root"
mvn -B package

jar_path="$(find "$repo_root/modules/GephiMcpPlugin/target" -maxdepth 1 -name 'gephi-mcp-plugin-*.jar' -print | sort -V | tail -n 1)"
if [[ -z "$jar_path" ]]; then
  echo "Plugin jar was not built." >&2
  exit 1
fi

echo "Reloading Gephi module from: $jar_path"
gephi_args=(--nosplash)
if [[ -f "$module_status" ]] && grep -Fq "<param name=\"jar\">$jar_path</param>" "$module_status"; then
  echo "Module status already points at this jar; starting Gephi without --reload."
else
  gephi_args+=(--reload "$jar_path")
fi
if [[ -n "$workspace" ]]; then
  gephi_args+=(--open "$workspace")
fi
"$gephi_bin" "${gephi_args[@]}"
