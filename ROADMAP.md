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

Result:

- Add MCP server transport on localhost.
- Expose read-only tools:
  - `get_graph_summary`
  - `list_node_attributes`
  - `list_edge_attributes`
  - `get_selection`
  - `get_visible_graph_stats`

Verify:

- MCP inspector/client can list tools.
- Tool calls return current workspace data.
- Server remains localhost-only and documents its auth story.

## Stage 2 - View Presets

Result:

- Add mutating tools for repeatable view setup:
  - `style_nodes_by_attribute`
  - `style_edges_by_attribute`
  - `size_nodes_by_degree`
  - `apply_code_graph_preset`
- First preset targets `archmotif` exports:
  - node color by `kind`
  - foreign nodes grey
  - calls/contains/dependsOn edge palettes

Verify:

- Import an `archmotif` GraphML file.
- Call `apply_code_graph_preset`.
- Gephi view updates without manual Appearance clicks.

## Stage 3 - Layout Tools

Result:

- Add layout commands:
  - `list_layouts`
  - `run_layout(name, iterations, params)`
  - `stop_layout`
- Start with built-in layouts available through Gephi APIs, especially
  ForceAtlas2 and Yifan Hu when present.

Verify:

- Running layout moves node positions in the active workspace.
- Long-running layouts are cancellable.

## Stage 4 - Filters And Focus

Result:

- Add tools:
  - `filter_nodes(attribute, op, value)`
  - `filter_edges(attribute, op, value)`
  - `focus_node(id, depth)`
  - `reset_filters`

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

- MCP transport: use the official Java SDK directly inside the NetBeans module,
  or run a tiny sidecar process that talks to the plugin over localhost HTTP.
- Auth: per-session token, manual enable toggle, or both.
- Threading: which Gephi API calls need dispatch onto Swing/NetBeans UI thread.
- Distribution: publish as standalone NBM, GitHub release zip, or Gephi
  Marketplace plugin later.

