# Gephi MCP Plugin

Local control bridge for Gephi Desktop. The goal is to let an agent inspect and
manipulate the currently open Gephi workspace through MCP tools: summarize the
graph, apply layouts, style nodes and edges, filter views, focus around a node,
run Gephi statistics, and export the result without manually clicking through
the Gephi UI.

## Status

Early proof of concept, but the first useful control surface is in place. The
Gephi plugin builds as an NBM and starts a localhost-only HTTP control server
inside Gephi. A stdio MCP sidecar wraps that endpoint as agent tools.

Current MCP tools:

- `gephi_health`
- `list_saved_outputs`
- `read_saved_output`
- `open_graph`
- `save_project`
- `save_workspace`
- `get_graph_summary`
- `list_node_attributes`
- `list_edge_attributes`
- `sample_nodes`
- `sample_edges`
- `get_node`
- `get_neighborhood`
- `analyze_neighborhood`
- `focus_neighborhood`
- `graph_profile`
- `attribute_distribution`
- `top_nodes`
- `partition_nodes`
- `partition_edges`
- `ranking_nodes`
- `style_nodes`
- `style_edges`
- `layout_nodes_circle`
- `apply_graph_preset`
- `apply_style_preset`
- `list_view_presets`
- `apply_view_preset`
- `filter_graph`
- `reset_filters`
- `list_layouts`
- `run_layout`
- `list_statistics`
- `run_statistics`
- `set_node_attribute`
- `add_node`
- `add_edge`
- `delete_nodes`
- `delete_edges`

The mutating tools update the current Gephi workspace in place: layouts change
node positions, `open_graph` loads a local graph/project file, `save_project`
persists the current Gephi project to a `.gephi` file, partition/ranking tools
change element color/size, filters switch the active Gephi `GraphView`, and
statistics may add result columns such as `degree`, `pagerank`, or
`modularity_class`. Destructive edit tools are explicit and default to dry-run
where that is meaningful; pass `confirm=true` to actually delete or bulk-edit.

MCP responses are intentionally compact. The sidecar saves every successful full
JSON result under `~/.cache/gephi-mcp-plugin/outputs` by default and returns a
small summary with `savedOutput.id` and `savedOutput.path`. Set
`GEPHI_MCP_OUTPUT_DIR` to change the directory or
`GEPHI_MCP_SUMMARY_MAX_BYTES` to tune the inline summary budget. Use
`list_saved_outputs` and `read_saved_output` when an agent needs to inspect a
saved payload without dumping the whole graph into context.

## Build

Requirements:

- JDK 17+
- Maven 3.6.3+

Build the plugin:

```bash
mvn clean package
```

The installable NBM is written to:

```text
modules/GephiMcpPlugin/target/gephi-mcp-plugin-0.1.11-SNAPSHOT.nbm
```

Install it in Gephi Desktop through `Tools -> Plugins -> Downloaded -> Add
Plugins...`, then restart Gephi.

When replacing an already installed local build, the NBM must have a higher
module specification version. If Gephi still reports that the plugin is already
installed, uninstall `Gephi MCP Plugin` from `Tools -> Plugins -> Installed`,
restart Gephi, and then install the new NBM.

For local development, Gephi's NetBeans launcher can also install the module jar
without using the plugin UI. Close Gephi first, then run:

```bash
npm run gephi:reload
```

The script builds the plugin and runs Gephi with `--reload` against the built
module jar on first install. If Gephi's module registry already points at that
jar, the script starts Gephi normally so the freshly built jar is loaded once.
Set `GEPHI_BIN=/path/to/gephi/bin/gephi` if Gephi is installed somewhere else.
Pass a `.gephi` workspace path to open it through Gephi's launcher `--open`
flag:

```bash
npm run gephi:reload -- /path/to/workspace.gephi
```

For a detached macOS launch, use `open -a Gephi --args --nosplash --open
/path/to/workspace.gephi`; plain `open -a Gephi /path/to/workspace.gephi` may
start Gephi without opening the workspace.

Do not send mutating MCP commands while the Gephi window is still opening the
workspace. The plugin checks Swing/AWT responsiveness before layout, styling,
and filter commands and returns `ui_busy` if the visualization canvas is not
ready yet.

Run Gephi with the plugin pre-installed:

```bash
mvn package org.gephi:gephi-maven-plugin:run
```

Install the MCP sidecar dependencies:

```bash
npm install
```

## Local Smoke Test

Terminal 1: run Gephi with the plugin pre-installed.

```bash
cd gephi-mcp-plugin
mvn package org.gephi:gephi-maven-plugin:run
```

In Gephi, import a GraphML/GEXF file into the current workspace. A tiny sample
is included at:

```text
examples/tiny.graphml
```

Terminal 2: check the plugin HTTP endpoint.

```bash
curl -s http://127.0.0.1:8765/health
curl -s -X POST "http://127.0.0.1:8765/graph/open?path=$(pwd)/examples/tiny.graphml"
curl -s http://127.0.0.1:8765/graph/summary
curl -s http://127.0.0.1:8765/layouts
curl -s http://127.0.0.1:8765/statistics
curl -s -X POST 'http://127.0.0.1:8765/layouts/run?name=ForceAtlas%202&iterations=100&scalingRatio=15&gravity=1&adjustSizes=true&barnesHutOptimization=true'
curl -s -X POST 'http://127.0.0.1:8765/statistics/run?name=Degree'
```

Then check the MCP sidecar.

```bash
npm run mcp:list
npm run mcp:health
npm run mcp:open-example
npm run mcp:summary
npm run mcp:profile
npm run mcp:top-degree
npm run mcp:top-weighted
npm run mcp:layouts
npm run mcp:statistics
npm run mcp:nodes
npm run mcp:view-presets
npm run mcp:view-preset-dry
```

`npm run mcp:list` only checks MCP tool registration. `npm run mcp:summary`
requires Gephi to be running with the plugin loaded.

Example mutating calls:

```bash
npm run mcp:preset
npm run mcp:save-project
npm run mcp:style-preset
npm run mcp:view-preset
npm run mcp:ranking-degree
npm run mcp:partition-kind
npm run mcp:style-node-kind-type
npm run mcp:thin-edges
npm run mcp:circle-node-kind-package
npm run mcp:run-layout
npm run mcp:filter-kind
npm run mcp:reset-filters
npm run mcp:run-degree
npm run mcp:run-modularity
```

The preset is intentionally generic: it uses common grouping attributes such as
`kind`, `type`, `group`, or `modularity_class` when present, sizes nodes by
degree, and colors edges by relation/kind/type. Partition, ranking, filter, and
attribute distribution tools can use any attribute listed by
`list_node_attributes` or `list_edge_attributes`. The filter tool uses a real
Gephi `GraphView` by default, so non-matching nodes and edges disappear from the
active workspace view without deleting graph data. It supports single-value
predicates such as `eq` plus multi-value predicates such as `in` and `not_in`,
so callers can filter graph layers carried as attributes. Use `mode=highlight`
when you only want the older visual dimming behavior, and `reset_filters` to
return to the full graph view.
`apply_style_preset` is implemented in the MCP sidecar by orchestrating
primitive HTTP calls; it is not a separate Java plugin endpoint. It also accepts
generic `nodeStyles` and `edgeStyles` rules, so a caller can apply a repeatable
palette and edge weight/opacity by ids or attribute filters without adding
domain-specific code to the Gephi plugin.
`apply_view_preset` is also MCP-side orchestration. It loads named generic view
presets from `mcp-server/view-presets/defaults.json` plus an optional user JSON
config at `~/.config/gephi-mcp-plugin/view-presets.json` or the path in
`GEPHI_MCP_VIEW_PRESETS`. A view preset can reset graph filters, run Gephi
statistics, apply node/edge styling, place selected nodes on a circle, run a
layout, and apply a final GraphView filter. Use `dryRun=true` to inspect the
planned primitive calls without mutating the Gephi workspace. Built-in presets
include `architecture_overview`, `internal_architecture`, `structure_model`,
`behavior_calls`, `type_usage`, `attribute_focus`, `attribute_shell`, and
`modularity_clusters`.
`style_nodes` and `layout_nodes_circle` are generic node-selection operations:
they select by ids or by any node attribute filter. `style_edges` does the same
for edges and can adjust color, alpha, and weight/thickness.
`analyze_neighborhood` is an MCP-side analysis helper: it calls the generic
neighborhood primitive, saves the full extracted subgraph as a local output
artifact, and returns compact distributions and degree-shape metrics for the
requested node/edge attributes. It is intended for agent workflows that need a
bounded subgraph contract without dumping the whole graph into the LLM context.
`focus_neighborhood` is the corresponding visual helper: it extracts the same
bounded neighborhood, styles the selected node ids, and can apply a real
GraphView filter so the neighborhood becomes the active visible graph.
`open_graph` accepts a local path and currently supports `.gephi` projects via
Gephi's Project API plus importer-supported graph files such as GraphML and
GEXF via Gephi's Import API.
`top_nodes` is graph-generic. It supports `degree`, `inDegree`, `outDegree`,
and `weightedDegree`; `weightedDegree` uses Gephi edge weights instead of
domain-specific edge semantics. Use `attribute_distribution` and
`includeKinds`/`excludeKinds` when a graph carries useful grouping attributes.
`run_layout` accepts a `parameters` object. Parameter names are matched against
the names returned by `list_layouts`, display names, and short aliases such as
`scalingRatio`, `gravity`, `adjustSizes`, `linLogMode`, and
`barnesHutOptimization`.
`run_statistics` accepts a `parameters` object matched against JavaBean setter
names exposed by the selected Gephi statistic, for example `directed`,
`useWeight`, `resolution`, `probability`, or `epsilon`.

Custom view preset config is JSON. It may define a `defaultPreset`, a `presets`
object, and preset inheritance through `extends`. Arrays override inherited
arrays; objects merge recursively.

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

To connect an MCP-capable agent, configure it as a stdio server:

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

The plugin is based on the official `gephi-plugins` Maven workflow.
The POM also declares Gephi's third-party repository because one Gephi
transitive dependency (`net.java.dev:stax-utils:snapshot-20100402`) is hosted
there rather than in Maven Central.

## Development Shape

The project is deliberately split into two layers:

- `ControlServer`: local transport and request handling.
- `GephiFacade`: calls into Gephi APIs such as Graph, Layout, Statistics, and
  element styling.
- `mcp-server`: stdio MCP sidecar that wraps the local HTTP endpoint as tools.

MCP should sit on top of this facade rather than mixing protocol code with
Gephi API calls. That keeps the first implementation testable via plain HTTP
and makes it easier to swap the MCP Java SDK transport later.

Domain-specific behaviour belongs in a separate MCP layer that calls these
generic tools. For example, an Arch Motif MCP can understand contracts,
producers, and code-architecture scores while reusing this plugin for Gephi
workspace access.

## Security

The server binds to `127.0.0.1` only. Before using this outside a local
experiment, the plugin should add a per-session token or explicit enable switch.
Localhost is not a permission model.

## References

- Gephi plugin development: https://docs.gephi.org/desktop/Plugins/
- Gephi plugin Maven workflow: https://github.com/gephi/gephi-plugins
- MCP Java SDK server: https://java.sdk.modelcontextprotocol.io/latest/server/
