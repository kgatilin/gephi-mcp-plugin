# Gephi MCP Plugin

Control Gephi Desktop from agents, scripts, and local CLI commands.

This project adds a local control bridge to Gephi. It lets an agent inspect,
style, filter, lay out, and edit the currently open Gephi workspace without
clicking through the Gephi UI.

## What It Gives You

- Open GraphML, GEXF, and `.gephi` projects in Gephi from an agent or CLI.
- Read the active graph: node/edge counts, attributes, top nodes,
  neighborhoods, and value distributions.
- Change the current Gephi view in place: partition colors, ranking sizes,
  filters, focus neighborhoods, layouts, and statistics.
- Run repeatable view presets so graph exploration is not a manual sequence of
  Gephi clicks.
- Keep LLM context small: large tool outputs are saved to disk and the MCP
  response returns compact summaries plus `savedOutput.path`.
- Use the same control surface three ways: HTTP API for local development, MCP
  tools for agents, and `gephi-mcp` for shell workflows.

## Architecture

```text
Gephi Desktop
  -> Gephi MCP Plugin
     -> localhost HTTP API on 127.0.0.1:8765
        -> stdio MCP sidecar
           -> MCP-capable agents
           -> gephi-mcp CLI
```

| Layer | Path | Responsibility |
| --- | --- | --- |
| Gephi plugin | `modules/GephiMcpPlugin` | Runs inside Gephi, calls Gephi APIs, exposes a localhost-only HTTP API. |
| HTTP API | `ControlServer` / `GephiFacade` | Thin local transport for graph open/save, layout, statistics, styling, filters, and edits. |
| MCP sidecar | `mcp-server/index.js` | Wraps the HTTP API as typed MCP tools, adds saved-output handling and generic view/style preset orchestration. |
| CLI | `gephi-mcp` / `scripts/mcp-call.js` | Calls the MCP tools through the stdio sidecar. It does not call the HTTP API directly. |

Domain-specific graph meaning should live above this project. For example, an
ArchMotif-specific agent can understand contracts and architecture metrics while
using this plugin only for generic Gephi workspace control.

## Status

Early but usable proof of concept. The plugin builds as a Gephi NBM, starts a
local HTTP control server inside Gephi, and the MCP sidecar exposes the current
control surface as tools.

The server binds to `127.0.0.1` only. Localhost is convenient for experiments,
but it is not a permission model; see [Security](#security).

## Requirements

- Gephi Desktop
- JDK 17+
- Maven 3.6.3+
- Node.js 20+
- npm

## Install

### 1. Build the Gephi plugin

```bash
mvn clean package
```

The installable NBM is written to:

```text
modules/GephiMcpPlugin/target/gephi-mcp-plugin-0.1.11-SNAPSHOT.nbm
```

### 2. Install the plugin in Gephi

In Gephi Desktop:

```text
Tools -> Plugins -> Downloaded -> Add Plugins...
```

Select the generated `.nbm`, install it, and restart Gephi.

When replacing an already installed local build, the NBM must have a higher
module specification version. If Gephi says the plugin is already installed,
uninstall `Gephi MCP Plugin` from `Tools -> Plugins -> Installed`, restart
Gephi, then install the new NBM.

### 3. Install sidecar dependencies

```bash
npm install
```

### 4. Install the CLI

For local development, link the package:

```bash
npm link
```

This installs two local commands:

```text
gephi-mcp          # CLI wrapper over MCP tools
gephi-mcp-sidecar  # stdio MCP server
```

You can also skip global linking and run the CLI script directly:

```bash
node scripts/mcp-call.js gephi_health
```

## Quick Start

Start Gephi with the plugin loaded. For local development, this command builds
the plugin and starts Gephi through the Gephi Maven plugin:

```bash
mvn package org.gephi:gephi-maven-plugin:run
```

In another terminal:

```bash
gephi-mcp gephi_health
gephi-mcp open_graph '{"path":"examples/tiny.graphml"}'
gephi-mcp get_graph_summary
gephi-mcp graph_profile '{"limit":20}'
gephi-mcp apply_view_preset '{"preset":"architecture_overview"}'
gephi-mcp run_layout '{"name":"ForceAtlas 2","iterations":100,"parameters":{"scalingRatio":15,"gravity":1,"adjustSizes":true,"barnesHutOptimization":true}}'
```

To launch an already installed local Gephi build with a workspace:

```bash
npm run gephi:reload -- /path/to/workspace.gephi
```

On macOS, for a detached launch:

```bash
open -a Gephi --args --nosplash --open /path/to/workspace.gephi
```

Plain `open -a Gephi /path/to/workspace.gephi` may start Gephi without opening
the workspace.

Do not send mutating MCP commands while Gephi is still opening a workspace. The
plugin checks Swing/AWT responsiveness before layout, styling, and filter
commands and returns `ui_busy` if the visualization canvas is not ready.

## CLI Usage

The CLI calls MCP tools through the stdio sidecar:

```bash
gephi-mcp help
gephi-mcp list
gephi-mcp <tool-name> '<json-arguments>'
```

Examples:

```bash
gephi-mcp gephi_health
gephi-mcp get_graph_summary
gephi-mcp open_graph '{"path":"/tmp/graph.graphml"}'
gephi-mcp sample_nodes '{"query":"service","limit":20}'
gephi-mcp focus_neighborhood '{"id":"n42","depth":2,"limit":200,"mode":"both"}'
gephi-mcp apply_view_preset '{"preset":"internal_architecture"}'
gephi-mcp reset_filters
```

`gephi-mcp list` prints the MCP tool schemas. Tool results are JSON. For large
responses, inspect the returned `savedOutput.path`.

## MCP Agent Configuration

Configure an MCP-capable agent as a stdio server:

```json
{
  "command": "node",
  "args": ["/path/to/gephi-mcp-plugin/mcp-server/index.js"],
  "env": {
    "GEPHI_CONTROL_URL": "http://127.0.0.1:8765",
    "GEPHI_MCP_VIEW_PRESETS": "/path/to/view-presets.json"
  }
}
```

`GEPHI_CONTROL_URL` defaults to `http://127.0.0.1:8765`.

MCP responses are intentionally compact. The sidecar saves every successful full
JSON result under `~/.cache/gephi-mcp-plugin/outputs` by default and returns a
summary with `savedOutput.id` and `savedOutput.path`.

Environment variables:

| Variable | Purpose |
| --- | --- |
| `GEPHI_CONTROL_URL` | Gephi plugin HTTP endpoint. |
| `GEPHI_MCP_HTTP_URL` | Backward-compatible alias for `GEPHI_CONTROL_URL`. |
| `GEPHI_MCP_OUTPUT_DIR` | Directory for full saved MCP outputs. |
| `GEPHI_MCP_SUMMARY_MAX_BYTES` | Inline summary size budget. |
| `GEPHI_MCP_VIEW_PRESETS` | Optional custom view preset JSON path. |

## Tool Reference

### Health, Output, And Projects

| Tool | Mutates Gephi | Description |
| --- | --- | --- |
| `gephi_health` | No | Check whether the Gephi plugin endpoint is alive. |
| `list_saved_outputs` | No | List recent full JSON payloads saved by the MCP sidecar. |
| `read_saved_output` | No | Read a bounded prefix of a saved output artifact. |
| `open_graph` | Yes | Open a local `.gephi`, GraphML, GEXF, or importer-supported graph file. |
| `save_project` | Yes | Save the current Gephi project. |
| `save_workspace` | Yes | Alias for `save_project`; Gephi persists workspaces inside a project. |

### Graph Inspection

| Tool | Mutates Gephi | Description |
| --- | --- | --- |
| `get_graph_summary` | No | Return node count, edge count, and directedness. |
| `graph_profile` | No | Summarize structural counts and common kind distributions. |
| `list_node_attributes` | No | List node table columns in the active workspace. |
| `list_edge_attributes` | No | List edge table columns in the active workspace. |
| `sample_nodes` | No | Return visible nodes with ids, labels, degree, positions, color, and attributes. |
| `sample_edges` | No | Return visible edges with endpoints, weight, color, and attributes. |
| `get_node` | No | Return one visible node by id, optionally with its neighborhood. |
| `get_neighborhood` | No | Return a bounded visible neighborhood around a node id. |
| `analyze_neighborhood` | No | Extract a bounded neighborhood, save the full subgraph, and return compact metrics. |
| `attribute_distribution` | No | Count values for a node or edge attribute. |
| `top_nodes` | No | Rank nodes by degree, in-degree, out-degree, or weighted degree. |

### Styling, Layout, Filters, And Statistics

| Tool | Mutates Gephi | Description |
| --- | --- | --- |
| `partition_nodes` | Yes | Color visible nodes by a categorical attribute. |
| `partition_edges` | Yes | Color visible edges by a categorical attribute. |
| `ranking_nodes` | Yes | Size visible nodes by a numeric attribute or by `degree`. |
| `style_nodes` | Yes | Set node color, alpha, or size by ids or attribute filter. |
| `style_edges` | Yes | Set edge color, alpha, or weight by ids or attribute filter. |
| `layout_nodes_circle` | Yes | Place selected nodes on a circle and optionally fix them. |
| `focus_neighborhood` | Yes | Style or filter to a bounded neighborhood around a node. |
| `filter_graph` | Yes | Apply a real Gephi `GraphView` filter or visual highlight. |
| `reset_filters` | Yes | Restore the full graph view and normal opacity. |
| `list_layouts` | No | List available Gephi layouts and writable parameters. |
| `run_layout` | Yes | Run a layout for a bounded number of iterations. |
| `list_statistics` | No | List available Gephi statistics algorithms and parameters. |
| `run_statistics` | Yes | Run a statistic such as Degree, PageRank, or Modularity. |
| `apply_graph_preset` | Yes | Apply a simple generic color/size preset. |
| `list_view_presets` | No | List built-in and user-defined MCP-side view presets. |
| `apply_style_preset` | Yes | Orchestrate primitive styling calls from one generic style request. |
| `apply_view_preset` | Yes | Apply a full preset: reset filters, run stats, style, layout, and filter. |

### Graph Editing

| Tool | Mutates Gephi | Description |
| --- | --- | --- |
| `set_node_attribute` | Yes | Set one attribute on selected node ids. Defaults to dry-run where applicable. |
| `add_node` | Yes | Add a node to the active graph. |
| `add_edge` | Yes | Add an edge to the active graph. |
| `delete_nodes` | Destructive | Delete nodes by ids or attribute filter. Defaults to dry-run; pass `confirm=true`. |
| `delete_edges` | Destructive | Delete edges by ids or attribute filter. Defaults to dry-run; pass `confirm=true`. |

## View Presets

`apply_view_preset` is MCP-side orchestration. It loads built-in presets from:

```text
mcp-server/view-presets/defaults.json
```

It can also load user presets from:

```text
~/.config/gephi-mcp-plugin/view-presets.json
```

or from the file pointed to by `GEPHI_MCP_VIEW_PRESETS`.

Built-in presets include:

- `architecture_overview`
- `internal_architecture`
- `structure_model`
- `behavior_calls`
- `type_usage`
- `attribute_focus`
- `attribute_shell`
- `modularity_clusters`

Custom preset example:

```json
{
  "defaultPreset": "my_overview",
  "presets": {
    "my_overview": {
      "extends": "architecture_overview",
      "style": {
        "minSize": 4,
        "maxSize": 20,
        "edgeStyles": [
          { "attribute": "kind", "op": "exists", "weight": 0.15, "alpha": 0.45 }
        ]
      },
      "layout": {
        "iterations": 80,
        "parameters": {
          "scalingRatio": 9,
          "gravity": 1.2,
          "adjustSizes": true,
          "linLogMode": false
        }
      }
    }
  }
}
```

Use `dryRun=true` to inspect the primitive calls before mutating the workspace:

```bash
gephi-mcp apply_view_preset '{"preset":"architecture_overview","dryRun":true}'
```

## HTTP API For Development

The HTTP API is useful for smoke tests and plugin development. Agent workflows
should normally use MCP tools or the `gephi-mcp` CLI.

```bash
curl -s http://127.0.0.1:8765/health
curl -s -X POST "http://127.0.0.1:8765/graph/open?path=$(pwd)/examples/tiny.graphml"
curl -s http://127.0.0.1:8765/graph/summary
curl -s http://127.0.0.1:8765/layouts
curl -s http://127.0.0.1:8765/statistics
curl -s -X POST 'http://127.0.0.1:8765/layouts/run?name=ForceAtlas%202&iterations=100&scalingRatio=15&gravity=1&adjustSizes=true&barnesHutOptimization=true'
curl -s -X POST 'http://127.0.0.1:8765/statistics/run?name=Degree'
```

## Development

For local module reload during plugin development:

```bash
npm run gephi:reload
```

The script builds the plugin and runs Gephi with `--reload` against the built
module jar on first install. If Gephi's module registry already points at that
jar, the script starts Gephi normally so the freshly built jar is loaded once.

Set `GEPHI_BIN=/path/to/gephi/bin/gephi` if Gephi is installed somewhere else.

Project shape:

| Path | Purpose |
| --- | --- |
| `modules/GephiMcpPlugin` | Gephi plugin module and HTTP control server. |
| `mcp-server/index.js` | MCP sidecar and generic preset orchestration. |
| `mcp-server/view-presets/defaults.json` | Built-in view presets. |
| `scripts/mcp-call.js` | CLI/MCP smoke client used by `gephi-mcp`. |
| `scripts/reload-gephi-plugin.sh` | Local plugin reload helper. |
| `examples/tiny.graphml` | Minimal sample graph for smoke tests. |

The POM declares Gephi's third-party repository because one Gephi transitive
dependency (`net.java.dev:stax-utils:snapshot-20100402`) is hosted there rather
than in Maven Central.

## Security

The plugin binds to `127.0.0.1` only. Before using this outside a local
experiment, add a per-session token or explicit enable switch. Do not expose the
HTTP endpoint on a public interface.

Destructive MCP tools are explicit. Delete operations default to dry-run unless
called with `confirm=true`.

## References

- Gephi plugin development: https://docs.gephi.org/desktop/Plugins/
- Gephi plugin Maven workflow: https://github.com/gephi/gephi-plugins
- Model Context Protocol: https://modelcontextprotocol.io/
- MCP TypeScript SDK: https://github.com/modelcontextprotocol/typescript-sdk
