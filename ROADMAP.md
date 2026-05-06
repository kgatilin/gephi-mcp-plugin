# Roadmap

## Goal

Make Gephi Desktop controllable by an agent through a local MCP server, while
keeping Gephi as the interactive graph exploration UI.

Target interaction:

```text
User: Color by kind, run ForceAtlas2, focus around a node, run PageRank.
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
- It exposes workspace tools:
  - `gephi_health`
  - `open_graph`
  - `get_graph_summary`
  - `list_node_attributes`
  - `list_edge_attributes`
  - `sample_nodes`
  - `sample_edges`
  - `get_node`
  - `get_neighborhood`
  - `graph_profile`
  - `attribute_distribution`
  - `top_nodes`
- It calls the Gephi plugin's localhost HTTP endpoint.

Remaining:

- Add explicit selection-aware reads if Gephi selection APIs are stable enough.
- Decide whether to keep the sidecar or embed MCP directly inside the plugin.

Verify:

- MCP inspector/client can list tools.
- Tool calls return current workspace data.
- Agent can open a generated GraphML/GEXF file without manual Gephi UI clicks.
- Agent can rank nodes by graph-generic metrics and then refine with attribute
  filters/distributions supplied by the graph.
- Server remains localhost-only and documents its auth story.

## Stage 1.5 - Graph Editing

Current result:

- Added explicit edit tools:
  - `set_node_attribute`
  - `add_node`
  - `add_edge`
  - `delete_nodes`
  - `delete_edges`
- Bulk destructive operations default to dry-run and require `confirm=true`.
- HTTP commands wait in a fair queue before returning `queue_timeout`, so
  parallel tool calls are serialized inside the plugin instead of immediately
  failing with `busy`.

Verify:

- Dry-run delete reports matched nodes/edges without changing graph counts.
- Add/delete of a test node restores the original graph summary.
- Parallel read/edit requests serialize through the plugin queue.

## Stage 2 - View Presets

Current result:

- Added mutating tools for repeatable view setup:
  - `partition_nodes`
  - `partition_edges`
  - `ranking_nodes`
  - `apply_graph_preset`
- The default preset is graph-generic:
  - node color by a common grouping attribute when present
  - edge color by relation/kind/type when present
  - node size by degree

Verify:

- Import any supported GraphML/GEXF file.
- Call `apply_graph_preset`.
- Gephi view updates without manual Appearance clicks.

## Stage 3 - Layout Tools

Current result:

- Added layout commands:
  - `list_layouts`
  - `run_layout(name, iterations, parameters)` runs a bounded synchronous
    layout with typed layout property overrides.
- Start with built-in layouts available through Gephi APIs, especially
  ForceAtlas2 and Yifan Hu when present.
- Layout parameters are matched against canonical property names, display
  names, and short aliases such as `scalingRatio`, `gravity`, `adjustSizes`,
  and `barnesHutOptimization`.

Remaining:

- Add async long-running layout jobs plus `stop_layout`.

Verify:

- Running layout moves node positions in the active workspace.
- Long-running layouts are cancellable.

## Stage 4 - Filters And Focus

Current result:

- Added tools:
  - `filter_graph(element, attribute, op, value)`
  - `get_neighborhood(id, depth, limit)`
  - `analyze_neighborhood(id, depth, limit)`
  - `focus_neighborhood(id, depth, limit)`
  - `reset_filters`
- Supported filter operations: `eq`, `neq`, `contains`, `exists`, `missing`,
  `gt`, `gte`, `lt`, `lte`, `in`, and `not_in`.
- Filters use native Gephi `GraphView`s by default, so non-matching nodes and
  edges disappear from the active workspace view without deleting graph data.
  `mode=highlight` keeps the older dimming behavior when callers want context.
- Focus tools combine bounded neighborhood extraction, styling, and optional
  GraphView filtering.

Remaining:

- Add true Overview camera/selection control once the right Gephi UI controller
  boundary is chosen.

Verify:

- Agent can visually isolate an attribute filter through a real GraphView and
  then restore the full graph with `reset_filters`.
- Agent can focus a neighborhood around a node id without locking the workspace.

## Stage 5 - Statistics

Current result:

- Added tools:
  - `list_statistics`
  - `run_statistics(name, parameters)`
- Statistics are discovered from Gephi's `StatisticsBuilder` lookup and run
  through `StatisticsController`.
- Parameters are applied via public JavaBean setters, so built-ins like Degree,
  Modularity, PageRank, Graph Distance, Connected Components, Density,
  Clustering Coefficient, HITS, Weighted Degree, and Eigenvector Centrality can
  be controlled without hard-coding each class.

Remaining:

- Add curated result summaries for the most useful statistics.
- Add async/cancellable statistics jobs for expensive metrics such as graph
  distance on large graphs.

Verify:

- Agent can run Degree and then rank nodes by the generated `degree` column.
- Agent can run Modularity and partition nodes by `modularity_class`.

## Stage 6 - Export And Session Capture

Current result:

  - `save_project`
  - `save_workspace`
  - `list_saved_outputs`
  - `read_saved_output`
- MCP tool responses save full JSON payloads to local output artifacts and
  return compact summaries so agent context stays bounded.

Remaining:

- Add export tools:
  - `export_png`
  - `export_svg`
  - `export_gexf`
- Record applied commands in a session log so views are reproducible beyond the
  current saved-output payloads.

Verify:

- Saved project reopens with styled graph.
- Agent can recover full saved MCP payloads by id without dumping large graph
  payloads into the LLM context.

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
- Domain adapters: keep project-specific semantics in separate MCP wrappers.
  For example, an Arch Motif MCP can expose contract/producers tools while
  calling this plugin for generic Gephi graph access.
