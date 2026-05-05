#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gephi_bin="${GEPHI_BIN:-/Applications/Gephi.app/Contents/Resources/gephi/bin/gephi}"

if [[ ! -x "$gephi_bin" ]]; then
  echo "Gephi launcher not found: $gephi_bin" >&2
  echo "Set GEPHI_BIN to the Gephi launcher path." >&2
  exit 1
fi

cd "$repo_root"
mvn -B package

jar_path="$(find "$repo_root/modules/GephiMcpPlugin/target" -maxdepth 1 -name 'gephi-mcp-plugin-*.jar' -print | sort | tail -n 1)"
if [[ -z "$jar_path" ]]; then
  echo "Plugin jar was not built." >&2
  exit 1
fi

echo "Reloading Gephi module from: $jar_path"
"$gephi_bin" --nosplash --reload "$jar_path"
