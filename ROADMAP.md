# Roadmap

## Goal

Make Gephi Desktop controllable by an agent through a local MCP server, while
keeping Gephi as the interactive graph exploration UI.

Target interaction:

```text
User: Hide foreign nodes, color by kind, run ForceAtlas2, focus around Register.
Agent: calls Gephi MCP tools.
Gephi: current workspace updates in place.
```

## Stage 0 - Plugin Skeleton

Result:

- Gephi plugin module builds as an NBM.
- Running Gephi with the plugin starts a localhost-only control server.
- `GET /health` returns plugin status.
- `GET /graph/summary` reads the active Gephi graph model.

Verify:

- `mvn clean package` succeeds.
- `mvn org.gephi:gephi-maven-plugin:run` opens Gephi with plugin installed.
- `curl -s http://127.0.0.1:8765/health` returns `status=ok`.
- Import a graph and verify `/graph/summary` reports non-zero nodes/edges.

## Stage 1 - MCP Transport

Current result:

- A stdio MCP sidecar exists in `mcp-server/`.
- It exposes read-only workspace tools:
  - `gephi_health`
  - `get_graph_summary`
  - `list_node_attributes`
  - `list_edge_attributes`
  - `sample_nodes`
  - `sample_edges`
  - `get_node`
  - `get_neighborhood`
- It calls the Gephi plugin's localhost HTTP endpoint.

Remaining:

- Add explicit selection-aware reads if Gephi selection APIs are stable enough.
- Decide whether to keep the sidecar or embed MCP directly inside the plugin.

Verify:

- MCP inspector/client can list tools.
- Tool calls return current workspace data.
- Server remains localhost-only and documents its auth story.

## Stage 2 - View Presets

Current result:

- Added mutating tools for repeatable view setup:
  - `partition_nodes`
  - `partition_edges`
  - `ranking_nodes`
  - `apply_code_graph_preset`
- First preset targets `archmotif` exports:
  - node color by `kind`
  - foreign nodes grey
  - calls/contains/imports/dependencies edge palettes
  - node size by degree

Verify:

- Import an `archmotif` GraphML file.
- Call `apply_code_graph_preset`.
- Gephi view updates without manual Appearance clicks.

## Stage 3 - Layout Tools

Current result:

- Added layout commands:
  - `list_layouts`
  - `run_layout(name, iterations)` runs a bounded synchronous layout.
- Start with built-in layouts available through Gephi APIs, especially
  ForceAtlas2 and Yifan Hu when present.

Remaining:

- Add layout parameter setting.
- Add async long-running layout jobs plus `stop_layout`.

Verify:

- Running layout moves node positions in the active workspace.
- Long-running layouts are cancellable.

## Stage 4 - Filters And Focus

Current result:

- Added tools:
  - `filter_graph(element, attribute, op, value)`
  - `get_neighborhood(id, depth, limit)`
  - `reset_filters`
- Supported filter operations: `eq`, `neq`, `contains`, `exists`, `missing`,
  `gt`, `gte`, `lt`, `lte`.

Remaining:

- Add true visual focus/selection once the right Gephi UI controller boundary is
  chosen.

Verify:

- Agent can hide `foreign=true`.
- Agent can focus a neighbourhood around an `archmotif_id`.

## Stage 5 - Export And Session Capture

Result:

- Add tools:
  - `export_png`
  - `export_svg`
  - `export_gexf`
  - `save_project`
- Record applied commands in a session log so views are reproducible.

Verify:

- Agent can produce a screenshot/export artifact from a single prompt.
- Saved project reopens with styled graph.

## Open Questions

- MCP transport: keep the sidecar process that talks to the plugin over
  localhost HTTP, or embed the official Java SDK directly inside the NetBeans
  module later.
- Auth: per-session token, manual enable toggle, or both.
- Threading: which Gephi API calls need dispatch onto Swing/NetBeans UI thread.
- UI focus: whether MCP should mutate selection/camera in Overview, or keep to
  graph data/view mutations and leave camera control to Gephi.
- Distribution: publish as standalone NBM, GitHub release zip, or Gephi
  Marketplace plugin later.
