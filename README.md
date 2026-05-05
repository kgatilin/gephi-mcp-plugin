# Gephi MCP Plugin

Local control bridge for Gephi Desktop. The goal is to let an agent inspect and
manipulate the currently open Gephi workspace through MCP tools: summarize the
graph, apply layouts, style nodes and edges, filter views, focus around a node,
and export the result without manually clicking through the Gephi UI.

## Status

Early proof of concept, but the first useful control surface is in place. The
Gephi plugin builds as an NBM and starts a localhost-only HTTP control server
inside Gephi. A stdio MCP sidecar wraps that endpoint as agent tools.

Current MCP tools:

- `gephi_health`
- `get_graph_summary`
- `list_node_attributes`
- `list_edge_attributes`
- `sample_nodes`
- `sample_edges`
- `get_node`
- `get_neighborhood`
- `partition_nodes`
- `partition_edges`
- `ranking_nodes`
- `apply_code_graph_preset`
- `filter_graph`
- `reset_filters`
- `list_layouts`
- `run_layout`

The mutating tools update the current Gephi workspace in place: layouts change
node positions, partition/ranking tools change element color/size, and filters
replace the visible Gephi graph view until `reset_filters` is called.

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
modules/GephiMcpPlugin/target/gephi-mcp-plugin-0.1.4-SNAPSHOT.nbm
```

Install it in Gephi Desktop through `Tools -> Plugins -> Downloaded -> Add
Plugins...`, then restart Gephi.

When replacing an already installed local build, the NBM must have a higher
module specification version. If Gephi still reports that the plugin is already
installed, uninstall `Gephi MCP Plugin` from `Tools -> Plugins -> Installed`,
restart Gephi, and then install the new NBM.

For local development, Gephi's NetBeans launcher can also reload the module jar
without using the plugin UI. Close Gephi first, then run:

```bash
npm run gephi:reload
```

The script builds the plugin and runs Gephi with `--reload` against the built
module jar. Set `GEPHI_BIN=/path/to/gephi/bin/gephi` if Gephi is installed
somewhere else.

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
cd /Users/kgatilin/dev/tools/gephi-mcp-plugin
mvn package org.gephi:gephi-maven-plugin:run
```

In Gephi, import a GraphML/GEXF file into the current workspace. A tiny sample
is included at:

```text
/Users/kgatilin/dev/tools/gephi-mcp-plugin/examples/tiny.graphml
```

Terminal 2: check the plugin HTTP endpoint.

```bash
curl -s http://127.0.0.1:8765/health
curl -s http://127.0.0.1:8765/graph/summary
curl -s http://127.0.0.1:8765/layouts
```

Then check the MCP sidecar.

```bash
npm run mcp:list
npm run mcp:health
npm run mcp:summary
npm run mcp:layouts
npm run mcp:nodes
```

`npm run mcp:list` only checks MCP tool registration. `npm run mcp:summary`
requires Gephi to be running with the plugin loaded.

Example mutating calls:

```bash
npm run mcp:preset
npm run mcp:ranking-degree
npm run mcp:partition-kind
npm run mcp:run-layout
npm run mcp:filter-foreign
npm run mcp:reset-filters
```

The preset assumes an `archmotif`-style graph with attributes such as `kind`,
`foreign`, and `relation`. Generic partition/ranking/filter tools can use any
attribute listed by `list_node_attributes` or `list_edge_attributes`.

To connect an MCP-capable agent, configure it as a stdio server:

```json
{
  "command": "node",
  "args": ["/Users/kgatilin/dev/tools/gephi-mcp-plugin/mcp-server/index.js"],
  "env": {
    "GEPHI_CONTROL_URL": "http://127.0.0.1:8765"
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
- `GephiFacade`: calls into Gephi APIs such as Graph, GraphView, Layout, and
  element styling.
- `mcp-server`: stdio MCP sidecar that wraps the local HTTP endpoint as tools.

MCP should sit on top of this facade rather than mixing protocol code with
Gephi API calls. That keeps the first implementation testable via plain HTTP
and makes it easier to swap the MCP Java SDK transport later.

## Security

The server binds to `127.0.0.1` only. Before using this outside a local
experiment, the plugin should add a per-session token or explicit enable switch.
Localhost is not a permission model.

## References

- Gephi plugin development: https://docs.gephi.org/desktop/Plugins/
- Gephi plugin Maven workflow: https://github.com/gephi/gephi-plugins
- MCP Java SDK server: https://java.sdk.modelcontextprotocol.io/latest/server/
