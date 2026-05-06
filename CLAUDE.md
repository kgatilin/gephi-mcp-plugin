# Repository Instructions

This is an open-source repository. Keep documentation, examples, issues, and
commits public-safe. Do not include private work paths, private graph contents,
company-specific package names, or conclusions from private graph analysis.

## Architecture Boundary

The project has two layers:

1. **Gephi plugin HTTP API** (`modules/GephiMcpPlugin`)
2. **MCP sidecar** (`mcp-server`)

Keep these layers deliberately separate.

### Gephi Plugin HTTP API

The Gephi plugin runs inside Gephi and exposes localhost HTTP endpoints. These
endpoints should be graph-generic primitives over the active Gephi workspace:

- open/import a graph;
- list nodes, edges, attributes, layouts, and statistics;
- partition/color by an arbitrary attribute;
- rank/size by an arbitrary numeric attribute;
- style selected nodes by ids or arbitrary attribute filters;
- filter/highlight by arbitrary attribute predicates;
- run Gephi layouts and statistics;
- add/delete/set graph elements and attributes.

The plugin must not contain domain semantics. In particular, do not add
Arch Motif, Go, package architecture, contract, dependency-layer, or codebase
analysis concepts to the Java plugin API. Treat graph attributes as opaque data:
the plugin may filter or style `attribute=value`, but it should not know what
that value means.

Avoid high-level preset APIs in the Java plugin when they are just sequences of
existing primitive operations. Add a Java endpoint only when Gephi itself needs a
new primitive capability that cannot be composed from existing endpoints.

### MCP Sidecar

The MCP sidecar wraps the localhost HTTP API as tools for agents. It is allowed
to orchestrate multiple primitive HTTP calls into convenient agent tools, for
example:

- apply a generic style preset by calling partition, ranking, and style tools;
- apply caller-supplied generic style rules by ids or attribute filters;
- choose a default color attribute from available graph attributes;
- load named generic view presets from JSON config and expand them into
  primitive operations;
- run a standard sequence of layout/statistics commands;
- provide safer schemas, defaults, and timeouts for agent use.

MCP-side presets must remain graph-generic. They may refer to generic attribute
names supplied by the graph (`kind`, `layer`, `group`, `modularity_class`,
`degree`, etc.), but they should not encode domain-specific meanings.
Keep built-in presets in `mcp-server/view-presets/defaults.json`; user or
private presets belong in `~/.config/gephi-mcp-plugin/view-presets.json` or the
path pointed to by `GEPHI_MCP_VIEW_PRESETS`, not in public examples.

MCP tool responses should be compact. Full JSON payloads from Gephi can be
large enough to pollute an LLM context, so successful tool calls should save the
full output to a local artifact and return only a small summary plus the saved
artifact id/path. Use bounded read tools when an agent needs to inspect the full
payload later.

### Domain-Specific Semantics

Domain-specific behavior belongs outside this repository, in a separate MCP
server or tool layer. For example, an Arch Motif Gephi MCP can interpret
Arch Motif concepts and call this repository's generic Gephi MCP tools.

When adding a feature, classify it first:

- **Primitive graph/Gephi operation** -> Java plugin HTTP API.
- **Composition of primitive operations** -> MCP sidecar.
- **Domain interpretation or named domain workflow** -> separate domain MCP.
