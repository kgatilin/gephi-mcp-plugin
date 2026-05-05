# Architecture

## Context

Gephi Desktop is already a strong interactive graph explorer. The missing
piece is a programmable control plane that an agent can use to apply view
operations consistently.

This plugin runs inside Gephi so it can access Gephi's current workspace and
controllers through the normal NetBeans Lookup mechanism. The plugin exposes a
localhost control endpoint. MCP tools will be added as a protocol layer over
the same command facade.

## Components

```text
MCP client / local agent
        |
        | MCP over localhost HTTP
        v
Gephi MCP Plugin
        |
        | command facade
        v
Gephi APIs
  GraphController
  AppearanceController
  LayoutController
  FilterController
  ExportController
```

## Boundaries

- The plugin owns transport, command validation, auth, and Gephi API calls.
- Gephi owns graph storage, workspace state, layout engines, preview, and
  export.
- `archmotif` owns code graph extraction and metrics. This plugin only consumes
  graph files and their attributes.

## MVP Transport

The first implementation uses a tiny localhost HTTP server with health and
graph-summary endpoints. That keeps the plugin testable before introducing MCP
SDK classloading and transport concerns.

## MCP Layer

The MCP layer should expose narrow tools, not arbitrary code execution:

- read workspace state;
- apply predefined styles/layouts/filters;
- export artifacts;
- never execute scripts from a prompt.

## Security Model

Binding to `127.0.0.1` is required but insufficient. Mutating endpoints should
require a token displayed in Gephi or written to a local session file. The
server should be disabled by default if we ever ship it to other users.

