# Gephi MCP Plugin

Local control bridge for Gephi Desktop. The goal is to let an agent inspect and
manipulate the currently open Gephi workspace through MCP tools: summarize the
graph, apply layouts, style nodes and edges, filter views, focus around a node,
and export the result without manually clicking through the Gephi UI.

## Status

Early proof of concept. The plugin skeleton builds as a Gephi module and starts
a localhost-only HTTP control server inside Gephi:

- `GET http://127.0.0.1:8765/health`
- `GET http://127.0.0.1:8765/graph/summary`

The HTTP surface is intentionally small. It proves that a Gephi plugin can host
a local control endpoint and access the active graph model. The next step is to
wrap the same command layer with MCP tools.

## Build

Requirements:

- JDK 17+
- Maven 3.6.3+

Build the plugin:

```bash
mvn clean package
```

Run Gephi with the plugin pre-installed:

```bash
mvn package org.gephi:gephi-maven-plugin:run
```

The plugin is based on the official `gephi-plugins` Maven workflow.
The POM also declares Gephi's third-party repository because one Gephi
transitive dependency (`net.java.dev:stax-utils:snapshot-20100402`) is hosted
there rather than in Maven Central.

## Development Shape

The project is deliberately split into two layers:

- `ControlServer`: local transport and request handling.
- `GephiFacade`: calls into Gephi APIs such as Graph, Layout, Appearance, Filter,
  and Export.

MCP should sit on top of this facade rather than mixing protocol code with
Gephi API calls. That keeps the first implementation testable via plain HTTP
and makes it easier to swap the MCP Java SDK transport later.

## Security

The server binds to `127.0.0.1` only. Before adding mutating tools, the plugin
should add a per-session token or explicit enable switch. Localhost is not a
permission model.

## References

- Gephi plugin development: https://docs.gephi.org/desktop/Plugins/
- Gephi plugin Maven workflow: https://github.com/gephi/gephi-plugins
- MCP Java SDK server: https://java.sdk.modelcontextprotocol.io/latest/server/
