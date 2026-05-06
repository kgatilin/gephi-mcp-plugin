#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import nodePath from "node:path";
import { z } from "zod";

const DEFAULT_GEPHI_CONTROL_URL = "http://127.0.0.1:8765";
const gephiControlUrl =
  process.env.GEPHI_CONTROL_URL ||
  process.env.GEPHI_MCP_HTTP_URL ||
  DEFAULT_GEPHI_CONTROL_URL;
const outputDir =
  process.env.GEPHI_MCP_OUTPUT_DIR ||
  nodePath.join(homedir(), ".cache", "gephi-mcp-plugin", "outputs");
const userViewPresetsPath =
  process.env.GEPHI_MCP_VIEW_PRESETS ||
  nodePath.join(homedir(), ".config", "gephi-mcp-plugin", "view-presets.json");
const builtInViewPresetsUrl = new URL("./view-presets/defaults.json", import.meta.url);
const summaryMaxBytes = positiveInteger(process.env.GEPHI_MCP_SUMMARY_MAX_BYTES, 2048);
const layoutParameterValueSchema = z.union([z.string(), z.number(), z.boolean()]);
const statisticParameterValueSchema = z.union([z.string(), z.number(), z.boolean()]);
const filterOperatorValues = ["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"];
const filterOperatorSchema = () => z.enum(filterOperatorValues);
const selectorStyleSchema = {
  ids: z.string().optional(),
  attribute: z.string().optional(),
  op: filterOperatorSchema().optional(),
  value: z.string().optional(),
  color: z.string().optional(),
  size: z.number().min(1).optional(),
  alpha: z.number().min(0).max(1).optional(),
  limit: z.number().int().min(1).max(10000).optional(),
};
const edgeStyleSchema = {
  ids: z.string().optional(),
  attribute: z.string().optional(),
  op: filterOperatorSchema().optional(),
  value: z.string().optional(),
  color: z.string().optional(),
  weight: z.number().min(0).optional(),
  alpha: z.number().min(0).max(1).optional(),
  limit: z.number().int().min(1).max(10000).optional(),
};
const filterSchema = z.object({
  element: z.enum(["node", "edge"]).optional(),
  attribute: z.string(),
  op: filterOperatorSchema().optional(),
  value: z.string().optional(),
  mode: z.enum(["view", "highlight"]).optional(),
  scope: z.enum(["visible", "full"]).optional(),
});
const statisticRunSchema = z.object({
  name: z.string(),
  parameters: z.record(statisticParameterValueSchema).optional(),
});
const layoutRunSchema = z.object({
  name: z.string().optional(),
  iterations: z.number().int().min(1).max(5000).optional(),
  parameters: z.record(layoutParameterValueSchema).optional(),
});
const viewPresetStyleOverrideSchema = z.object({
  nodeColorAttribute: z.string().optional(),
  edgeColorAttribute: z.string().optional(),
  nodeSizeAttribute: z.string().optional(),
  minSize: z.number().min(1).optional(),
  maxSize: z.number().min(1).optional(),
  nodeStyles: z.array(z.object(selectorStyleSchema)).max(50).optional(),
  edgeStyles: z.array(z.object(edgeStyleSchema)).max(50).optional(),
});

const server = new McpServer({
  name: "gephi-mcp-plugin-sidecar",
  version: "0.1.11",
});

server.registerTool(
  "gephi_health",
  {
    title: "Gephi Plugin Health",
    description: "Check whether the Gephi MCP Plugin localhost endpoint is alive.",
    inputSchema: {},
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    },
  },
  async () => jsonToolResult("/health"),
);

server.registerTool(
  "get_graph_summary",
  {
    title: "Get Gephi Graph Summary",
    description:
      "Return node count, edge count, and directedness from the active Gephi workspace.",
    inputSchema: {},
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    },
  },
  async () => jsonToolResult("/graph/summary"),
);

server.registerTool(
  "list_saved_outputs",
  {
    title: "List Saved MCP Outputs",
    description:
      "List recent full JSON outputs saved by this MCP sidecar. Normal tool responses return compact summaries; use this to find a saved artifact id.",
    inputSchema: {
      limit: z.number().int().min(1).max(100).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  listSavedOutputs,
);

server.registerTool(
  "read_saved_output",
  {
    title: "Read Saved MCP Output",
    description:
      "Read a bounded prefix of a saved MCP output artifact by id. The id must come from savedOutput.id or list_saved_outputs.",
    inputSchema: {
      id: z.string(),
      maxBytes: z.number().int().min(1).max(100000).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  readSavedOutput,
);

server.registerTool(
  "open_graph",
  {
    title: "Open Graph",
    description:
      "Open a local graph file in Gephi. Supports Gephi projects through the Project API and importer-supported graph files such as GraphML/GEXF through the Import API.",
    inputSchema: {
      path: z.string(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ path }) =>
    jsonToolResult(pathWithQuery("/graph/open", { path }), {
      method: "POST",
      timeoutMs: 120000,
    }),
);

server.registerTool(
  "save_project",
  {
    title: "Save Gephi Project",
    description:
      "Save the current Gephi project as a .gephi file. If path is omitted, the current project must already have a file.",
    inputSchema: {
      path: z.string().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ path }) => jsonToolResult(pathWithQuery("/project/save", { path }), { method: "POST" }),
);

server.registerTool(
  "save_workspace",
  {
    title: "Save Current Workspace",
    description:
      "Alias for save_project: Gephi persists workspaces inside the current .gephi project file.",
    inputSchema: {
      path: z.string().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ path }) => jsonToolResult(pathWithQuery("/project/save", { path }), { method: "POST" }),
);

server.registerTool(
  "list_node_attributes",
  {
    title: "List Node Attributes",
    description: "List node table columns in the active Gephi workspace.",
    inputSchema: {},
    annotations: readOnlyAnnotations(),
  },
  async () => jsonToolResult("/graph/attributes?element=node"),
);

server.registerTool(
  "list_edge_attributes",
  {
    title: "List Edge Attributes",
    description: "List edge table columns in the active Gephi workspace.",
    inputSchema: {},
    annotations: readOnlyAnnotations(),
  },
  async () => jsonToolResult("/graph/attributes?element=edge"),
);

server.registerTool(
  "sample_nodes",
  {
    title: "Sample Nodes",
    description: "Return visible nodes with ids, labels, degree, positions, color, and attributes.",
    inputSchema: {
      limit: z.number().int().min(1).max(500).optional(),
      query: z.string().optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ limit, query }) => jsonToolResult(pathWithQuery("/graph/nodes", { limit, query })),
);

server.registerTool(
  "sample_edges",
  {
    title: "Sample Edges",
    description: "Return visible edges with endpoints, weight, color, and attributes.",
    inputSchema: {
      limit: z.number().int().min(1).max(500).optional(),
      query: z.string().optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ limit, query }) => jsonToolResult(pathWithQuery("/graph/edges", { limit, query })),
);

server.registerTool(
  "get_node",
  {
    title: "Get Node",
    description: "Return one visible node by id, optionally with its neighbourhood.",
    inputSchema: {
      id: z.string(),
      depth: z.number().int().min(0).max(5).optional(),
      limit: z.number().int().min(1).max(1000).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ id, depth, limit }) => jsonToolResult(pathWithQuery("/graph/node", { id, depth, limit })),
);

server.registerTool(
  "get_neighborhood",
  {
    title: "Get Node Neighborhood",
    description: "Return a bounded visible neighbourhood around a node id.",
    inputSchema: {
      id: z.string(),
      depth: z.number().int().min(1).max(5).optional(),
      limit: z.number().int().min(1).max(1000).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ id, depth, limit }) =>
    jsonToolResult(pathWithQuery("/graph/neighborhood", { id, depth, limit })),
);

server.registerTool(
  "analyze_neighborhood",
  {
    title: "Analyze Node Neighborhood",
    description:
      "Extract a bounded visible neighbourhood around a node, save the full subgraph output, and return compact graph-generic metrics for agent analysis.",
    inputSchema: {
      id: z.string(),
      depth: z.number().int().min(1).max(5).optional(),
      limit: z.number().int().min(1).max(1000).optional(),
      nodeAttribute: z.string().optional(),
      nodeAttributes: z.array(z.string()).max(5).optional(),
      edgeAttribute: z.string().optional(),
      edgeAttributes: z.array(z.string()).max(5).optional(),
      labelAttribute: z.string().optional(),
      top: z.number().int().min(1).max(100).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  analyzeNeighborhood,
);

server.registerTool(
  "focus_neighborhood",
  {
    title: "Focus Node Neighborhood",
    description:
      "Extract a bounded visible neighbourhood around a node and focus it in Gephi by styling and/or applying a GraphView node-id filter.",
    inputSchema: {
      id: z.string(),
      depth: z.number().int().min(1).max(5).optional(),
      limit: z.number().int().min(1).max(1000).optional(),
      mode: z.enum(["style", "filter", "both"]).optional(),
      scope: z.enum(["visible", "full"]).optional(),
      color: z.string().optional(),
      alpha: z.number().min(0).max(1).optional(),
      size: z.number().min(1).optional(),
      centerColor: z.string().optional(),
      centerSize: z.number().min(1).optional(),
      edgeColor: z.string().optional(),
      edgeAlpha: z.number().min(0).max(1).optional(),
      edgeWeight: z.number().min(0.001).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  focusNeighborhood,
);

server.registerTool(
  "graph_profile",
  {
    title: "Graph Profile",
    description:
      "Summarize the active graph by structural counts and common kind distributions when those attributes exist.",
    inputSchema: {
      limit: z.number().int().min(1).max(100).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ limit }) => jsonToolResult(pathWithQuery("/graph/profile", { limit })),
);

server.registerTool(
  "attribute_distribution",
  {
    title: "Attribute Distribution",
    description: "Count values for a node or edge attribute in the active graph.",
    inputSchema: {
      element: z.enum(["node", "edge"]).optional(),
      attribute: z.string(),
      limit: z.number().int().min(1).max(500).optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ element, attribute, limit }) =>
    jsonToolResult(pathWithQuery("/graph/distribution", { element, attribute, limit })),
);

server.registerTool(
  "top_nodes",
  {
    title: "Top Nodes",
    description:
      "Rank nodes by degree, in-degree, out-degree, or Gephi edge-weighted degree. Optional kind filters use the node attribute named kind when present.",
    inputSchema: {
      score: z.enum(["degree", "inDegree", "outDegree", "weightedDegree"]).optional(),
      limit: z.number().int().min(1).max(200).optional(),
      includeKinds: z.string().optional(),
      excludeKinds: z.string().optional(),
    },
    annotations: readOnlyAnnotations(),
  },
  async ({ score, limit, includeKinds, excludeKinds }) =>
    jsonToolResult(
      pathWithQuery("/graph/top-nodes", {
        score,
        limit,
        includeKinds,
        excludeKinds,
      }),
    ),
);

server.registerTool(
  "delete_nodes",
  {
    title: "Delete Nodes",
    description:
      "Delete nodes from the active graph by ids or by attribute filter. Defaults to dry-run; pass confirm=true to actually mutate the workspace.",
    inputSchema: {
      ids: z.string().optional(),
      attribute: z.string().optional(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      confirm: z.boolean().optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: destructiveAnnotations(),
  },
  async ({ ids, attribute, op, value, confirm, limit }) =>
    jsonToolResult(pathWithQuery("/graph/delete/nodes", { ids, attribute, op, value, confirm, limit }), {
      method: "POST",
      timeoutMs: 60000,
    }),
);

server.registerTool(
  "delete_edges",
  {
    title: "Delete Edges",
    description:
      "Delete edges from the active graph by ids or by attribute filter. Defaults to dry-run; pass confirm=true to actually mutate the workspace.",
    inputSchema: {
      ids: z.string().optional(),
      attribute: z.string().optional(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      confirm: z.boolean().optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: destructiveAnnotations(),
  },
  async ({ ids, attribute, op, value, confirm, limit }) =>
    jsonToolResult(pathWithQuery("/graph/delete/edges", { ids, attribute, op, value, confirm, limit }), {
      method: "POST",
      timeoutMs: 60000,
    }),
);

server.registerTool(
  "set_node_attribute",
  {
    title: "Set Node Attribute",
    description:
      "Set one attribute on specific node ids. Defaults to dry-run; pass confirm=true to actually mutate the workspace.",
    inputSchema: {
      ids: z.string(),
      attribute: z.string(),
      value: z.string(),
      type: z.enum(["string", "boolean", "int", "long", "float", "double", "number"]).optional(),
      confirm: z.boolean().optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ ids, attribute, value, type, confirm, limit }) =>
    jsonToolResult(pathWithQuery("/graph/set/nodes", { ids, attribute, value, type, confirm, limit }), {
      method: "POST",
    }),
);

server.registerTool(
  "add_node",
  {
    title: "Add Node",
    description: "Add a node to the active graph. Optionally set a generic kind attribute.",
    inputSchema: {
      id: z.string(),
      label: z.string().optional(),
      kind: z.string().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ id, label, kind }) =>
    jsonToolResult(pathWithQuery("/graph/add/node", { id, label, kind }), {
      method: "POST",
    }),
);

server.registerTool(
  "add_edge",
  {
    title: "Add Edge",
    description: "Add an edge to the active graph. Optionally set a generic kind attribute and Gephi edge type.",
    inputSchema: {
      id: z.string().optional(),
      source: z.string(),
      target: z.string(),
      directed: z.boolean().optional(),
      kind: z.string().optional(),
      weight: z.number().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ id, source, target, directed, kind, weight }) =>
    jsonToolResult(pathWithQuery("/graph/add/edge", { id, source, target, directed, kind, weight }), {
      method: "POST",
    }),
);

server.registerTool(
  "style_nodes",
  {
    title: "Style Nodes",
    description:
      "Set visual style on nodes selected by ids or by an attribute filter. Supports concrete node color, alpha, and size.",
    inputSchema: {
      ids: z.string().optional(),
      attribute: z.string().optional(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      color: z.string().optional(),
      alpha: z.number().min(0).max(1).optional(),
      size: z.number().min(1).optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ ids, attribute, op, value, color, alpha, size, limit }) =>
    jsonToolResult(pathWithQuery("/graph/style/nodes", { ids, attribute, op, value, color, alpha, size, limit }), {
      method: "POST",
    }),
);

server.registerTool(
  "style_edges",
  {
    title: "Style Edges",
    description:
      "Set visual style on edges selected by ids or by an attribute filter. Supports concrete edge color, alpha, and weight/thickness.",
    inputSchema: {
      ids: z.string().optional(),
      attribute: z.string().optional(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      color: z.string().optional(),
      alpha: z.number().min(0).max(1).optional(),
      weight: z.number().min(0.001).optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ ids, attribute, op, value, color, alpha, weight, limit }) =>
    jsonToolResult(pathWithQuery("/graph/style/edges", { ids, attribute, op, value, color, alpha, weight, limit }), {
      method: "POST",
    }),
);

server.registerTool(
  "layout_nodes_circle",
  {
    title: "Layout Nodes On Circle",
    description:
      "Move selected nodes to a circle and optionally fix them in place. Select nodes by ids or by an attribute filter.",
    inputSchema: {
      ids: z.string().optional(),
      attribute: z.string().optional(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      radius: z.number().min(1).optional(),
      centerX: z.number().optional(),
      centerY: z.number().optional(),
      startAngle: z.number().optional(),
      clockwise: z.boolean().optional(),
      fixed: z.boolean().optional(),
      limit: z.number().int().min(1).max(10000).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ ids, attribute, op, value, radius, centerX, centerY, startAngle, clockwise, fixed, limit }) =>
    jsonToolResult(
      pathWithQuery("/graph/layout/nodes/circle", {
        ids,
        attribute,
        op,
        value,
        radius,
        centerX,
        centerY,
        startAngle,
        clockwise,
        fixed,
        limit,
      }),
      { method: "POST" },
    ),
);

server.registerTool(
  "partition_nodes",
  {
    title: "Partition Nodes",
    description: "Color visible nodes by a categorical attribute, similar to Gephi partition styling.",
    inputSchema: {
      attribute: z.string(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ attribute }) =>
    jsonToolResult(pathWithQuery("/graph/partition/nodes", { attribute }), { method: "POST" }),
);

server.registerTool(
  "partition_edges",
  {
    title: "Partition Edges",
    description: "Color visible edges by a categorical attribute, similar to Gephi partition styling.",
    inputSchema: {
      attribute: z.string(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ attribute }) =>
    jsonToolResult(pathWithQuery("/graph/partition/edges", { attribute }), { method: "POST" }),
);

server.registerTool(
  "ranking_nodes",
  {
    title: "Ranking Nodes",
    description: "Size visible nodes by a numeric attribute, or by pseudo-attribute degree.",
    inputSchema: {
      attribute: z.string(),
      minSize: z.number().min(1).optional(),
      maxSize: z.number().min(1).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ attribute, minSize, maxSize }) =>
    jsonToolResult(pathWithQuery("/graph/ranking/nodes", { attribute, minSize, maxSize }), {
      method: "POST",
    }),
);

server.registerTool(
  "apply_graph_preset",
  {
    title: "Apply Graph Preset",
    description:
      "Apply a generic quick view preset: color nodes by a common grouping attribute if present, size nodes by degree, and color edges by relation/kind/type.",
    inputSchema: {},
    annotations: mutatingAnnotations(),
  },
  async () => jsonToolResult("/graph/preset/default", { method: "POST" }),
);

server.registerTool(
  "apply_style_preset",
  {
    title: "Apply Style Preset",
    description:
      "Apply a generic visual style preset by orchestrating primitive Gephi API calls: node partition, edge partition, node ranking, and optional node highlighting.",
    inputSchema: {
      nodeColorAttribute: z.string().optional(),
      edgeColorAttribute: z.string().optional(),
      nodeSizeAttribute: z.string().optional(),
      minSize: z.number().min(1).optional(),
      maxSize: z.number().min(1).optional(),
      highlight: z
        .object({
          ids: z.string().optional(),
          attribute: z.string().optional(),
          op: z
            .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
            .optional(),
          value: z.string().optional(),
          color: z.string().optional(),
          size: z.number().min(1).optional(),
          alpha: z.number().min(0).max(1).optional(),
          limit: z.number().int().min(1).max(10000).optional(),
        })
        .optional(),
      nodeStyles: z
        .array(
          z.object({
            ids: z.string().optional(),
            attribute: z.string().optional(),
            op: z
              .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
              .optional(),
            value: z.string().optional(),
            color: z.string().optional(),
            size: z.number().min(1).optional(),
            alpha: z.number().min(0).max(1).optional(),
            limit: z.number().int().min(1).max(10000).optional(),
          }),
        )
        .max(50)
        .optional(),
      edgeStyles: z.array(z.object(edgeStyleSchema)).max(50).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  applyStylePreset,
);

server.registerTool(
  "list_view_presets",
  {
    title: "List View Presets",
    description:
      "List generic MCP-side view presets loaded from the built-in JSON file plus optional user config.",
    inputSchema: {},
    annotations: readOnlyAnnotations(),
  },
  listViewPresets,
);

server.registerTool(
  "apply_view_preset",
  {
    title: "Apply View Preset",
    description:
      "Apply a generic MCP-side view preset that can orchestrate filters, statistics, styling, focus rings, and layouts through primitive Gephi API calls.",
    inputSchema: {
      preset: z.string().optional(),
      dryRun: z.boolean().optional(),
      resetFilters: z.boolean().optional(),
      runLayout: z.boolean().optional(),
      runStatistics: z.boolean().optional(),
      layout: layoutRunSchema.optional(),
      statistics: z.array(statisticRunSchema).max(10).optional(),
      filter: filterSchema.optional(),
      style: viewPresetStyleOverrideSchema.optional(),
      focusAttribute: z.string().optional(),
      focusOp: filterOperatorSchema().optional(),
      focusValue: z.string().optional(),
      focusColor: z.string().optional(),
      focusSize: z.number().min(1).optional(),
      focusAlpha: z.number().min(0).max(1).optional(),
      circleFocus: z.boolean().optional(),
      circleRadius: z.number().min(1).optional(),
      circleFixed: z.boolean().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  applyViewPreset,
);

server.registerTool(
  "filter_graph",
  {
    title: "Filter Graph",
    description:
      "Filter the active Gephi graph by a node or edge attribute. Defaults to a real GraphView filter; use mode=highlight for visual dimming only. Use reset_filters to restore the full graph view.",
    inputSchema: {
      element: z.enum(["node", "edge"]).optional(),
      attribute: z.string(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "in", "not_in", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
      mode: z.enum(["view", "highlight"]).optional(),
      scope: z.enum(["visible", "full"]).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ element, attribute, op, value, mode, scope }) =>
    jsonToolResult(pathWithQuery("/graph/filter", { element, attribute, op, value, mode, scope }), {
      method: "POST",
    }),
);

server.registerTool(
  "reset_filters",
  {
    title: "Reset Filters",
    description: "Reset MCP graph filters by restoring the full Gephi GraphView and normal node/edge opacity.",
    inputSchema: {},
    annotations: mutatingAnnotations(),
  },
  async () => jsonToolResult("/graph/filter/reset", { method: "POST" }),
);

server.registerTool(
  "list_layouts",
  {
    title: "List Layouts",
    description: "List layout algorithms available in the running Gephi installation.",
    inputSchema: {},
    annotations: readOnlyAnnotations(),
  },
  async () => jsonToolResult("/layouts"),
);

server.registerTool(
  "list_statistics",
  {
    title: "List Statistics",
    description:
      "List Gephi statistics algorithms available in the running installation and their writable parameters.",
    inputSchema: {},
    annotations: readOnlyAnnotations(),
  },
  async () => jsonToolResult("/statistics"),
);

server.registerTool(
  "run_statistics",
  {
    title: "Run Statistics",
    description:
      "Run a Gephi statistics algorithm by name. Many statistics add result columns such as degree, modularity_class, pagerank, centrality, or component ids.",
    inputSchema: {
      name: z.string(),
      parameters: z.record(statisticParameterValueSchema).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ name, parameters }) =>
    jsonToolResult(
      pathWithQuery("/statistics/run", {
        ...operationParameters(parameters),
        name,
      }),
      { method: "POST", timeoutMs: 120000 },
    ),
);

server.registerTool(
  "run_layout",
  {
    title: "Run Layout",
    description:
      "Run a Gephi layout algorithm for a bounded number of iterations. Optional parameters are matched against list_layouts property names, display names, or short aliases such as scalingRatio, gravity, adjustSizes, linLogMode, and barnesHutOptimization.",
    inputSchema: {
      name: z.string(),
      iterations: z.number().int().min(1).max(5000).optional(),
      parameters: z.record(layoutParameterValueSchema).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ name, iterations, parameters }) =>
    jsonToolResult(
      pathWithQuery("/layouts/run", {
        ...operationParameters(parameters),
        name,
        iterations,
      }),
      { method: "POST", timeoutMs: 60000 },
    ),
);

function readOnlyAnnotations() {
  return {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  };
}

function mutatingAnnotations() {
  return {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: false,
    openWorldHint: false,
  };
}

function destructiveAnnotations() {
  return {
    readOnlyHint: false,
    destructiveHint: true,
    idempotentHint: false,
    openWorldHint: false,
  };
}

async function listSavedOutputs({ limit = 20 }) {
  try {
    await mkdir(outputDir, { recursive: true });
    const entries = await readdir(outputDir);
    const files = [];
    for (const entry of entries) {
      if (!entry.endsWith(".json")) {
        continue;
      }
      const file = nodePath.join(outputDir, entry);
      const info = await stat(file);
      files.push({
        id: entry,
        path: file,
        bytes: info.size,
        modifiedAt: info.mtime.toISOString(),
      });
    }
    files.sort((left, right) => right.modifiedAt.localeCompare(left.modifiedAt));
    return compactToolResult(
      {
        ok: true,
        outputDir,
        outputs: files.slice(0, limit),
        total: files.length,
      },
      { tool: "list_saved_outputs" },
      null,
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

async function readSavedOutput({ id, maxBytes = 8192 }) {
  try {
    const file = resolveSavedOutputId(id);
    const info = await stat(file);
    const text = await readFile(file, "utf8");
    const encoded = Buffer.from(text, "utf8");
    const truncated = encoded.length > maxBytes;
    const body = truncated ? encoded.subarray(0, maxBytes).toString("utf8") : text;
    const summary = {
      ok: true,
      id: nodePath.basename(file),
      path: file,
      bytes: info.size,
      returnedBytes: Buffer.byteLength(body, "utf8"),
      truncated,
    };
    return {
      content: [
        {
          type: "text",
          text: truncated ? `${body}\n... truncated; increase maxBytes or open the saved file locally.` : body,
        },
      ],
      structuredContent: summary,
    };
  } catch (error) {
    return errorToolResult(error);
  }
}

async function analyzeNeighborhood({
  id,
  depth = 1,
  limit = 200,
  nodeAttribute,
  nodeAttributes,
  edgeAttribute,
  edgeAttributes,
  labelAttribute,
  top = 20,
}) {
  try {
    const subgraph = await fetchJson(pathWithQuery("/graph/neighborhood", { id, depth, limit }));
    const subgraphOutput = await saveToolOutput(subgraph, {
      tool: "analyze_neighborhood.subgraph",
      center: id,
    });
    const nodes = Array.isArray(subgraph.nodes) ? subgraph.nodes : [];
    const edges = Array.isArray(subgraph.edges) ? subgraph.edges : [];
    const nodeIds = new Set(nodes.map((node) => String(node.id)));
    const stats = subgraphNodeStats(nodes, edges, nodeIds);
    const nodeAttributeList = normalizeAttributeList(nodeAttributes, nodeAttribute);
    const edgeAttributeList = normalizeAttributeList(edgeAttributes, edgeAttribute);
    const nodeAnalysisAttributes = uniqueStrings([
      ...nodeAttributeList,
      labelAttribute,
      "degree",
      "indegree",
      "outdegree",
      "pageranks",
      "modularity_class",
    ]);

    return dataToolResult(
      {
        ok: true,
        tool: "analyze_neighborhood",
        center: id,
        depth,
        limit,
        nodes: nodes.length,
        edges: edges.length,
        subgraphOutput,
        nodeAttributeDistributions: attributeDistributions(nodes, nodeAttributeList, top),
        edgeAttributeDistributions: attributeDistributions(edges, edgeAttributeList, top),
        degreeShape: neighborhoodDegreeShape(nodes, stats),
        topSubgraphDegree: topNodesBy(nodes, stats, (node, nodeStats) => nodeStats.degree, top, {
          labelAttribute,
          attributes: nodeAnalysisAttributes,
          scoreName: "subgraphDegree",
        }),
        topGlobalDegree: topNodesBy(nodes, stats, (node) => numberValue(node.degree), top, {
          labelAttribute,
          attributes: nodeAnalysisAttributes,
          scoreName: "globalDegree",
        }),
        sampleNodes: nodes.slice(0, Math.min(top, nodes.length)).map((node) =>
          compactAnalysisNode(node, stats.get(String(node.id)), {
            labelAttribute,
            attributes: nodeAnalysisAttributes,
          }),
        ),
      },
      { tool: "analyze_neighborhood" },
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

async function focusNeighborhood({
  id,
  depth = 1,
  limit = 200,
  mode = "both",
  scope = "visible",
  color = "#6b7280",
  alpha = 0.92,
  size,
  centerColor = "#ef4444",
  centerSize = 24,
  edgeColor = "#374151",
  edgeAlpha = 0.55,
  edgeWeight,
}) {
  try {
    const subgraph = await fetchJson(pathWithQuery("/graph/neighborhood", { id, depth, limit }));
    const nodes = Array.isArray(subgraph.nodes) ? subgraph.nodes : [];
    const edges = Array.isArray(subgraph.edges) ? subgraph.edges : [];
    const ids = nodes.map((node) => String(node.id)).filter(Boolean);
    if (ids.length === 0) {
      throw new Error(`No nodes found for neighbourhood center ${JSON.stringify(id)}.`);
    }

    const operations = [];
    const idsValue = ids.join(",");
    const shouldStyle = mode === "style" || mode === "both";
    const shouldFilter = mode === "filter" || mode === "both";

    if (shouldStyle) {
      operations.push(await runPresetOperation("style_nodes", {
        selector: { ids: idsValue, limit: ids.length },
        request: pathWithQuery("/graph/style/nodes", {
          ids: idsValue,
          color,
          alpha,
          size,
          limit: ids.length,
        }),
        method: "POST",
      }));
      operations.push(await runPresetOperation("style_nodes", {
        phase: "center",
        selector: { ids: id, limit: 1 },
        request: pathWithQuery("/graph/style/nodes", {
          ids: id,
          color: centerColor,
          size: centerSize,
          alpha: 1,
          limit: 1,
        }),
        method: "POST",
      }));
    }

    if (shouldFilter) {
      operations.push(await runPresetOperation("filter_graph", {
        selector: { attribute: "id", op: "in", value: idsValue, limit: ids.length },
        request: pathWithQuery("/graph/filter", {
          element: "node",
          attribute: "id",
          op: "in",
          value: idsValue,
          mode: "view",
          scope,
        }),
        method: "POST",
      }));
    }

    if (shouldStyle && (edgeColor || edgeAlpha !== undefined || edgeWeight !== undefined)) {
      operations.push(await runPresetOperation("style_edges", {
        phase: "visible_neighborhood_edges",
        selector: { attribute: "id", op: "exists", limit: 10000 },
        request: pathWithQuery("/graph/style/edges", {
          attribute: "id",
          op: "exists",
          color: edgeColor,
          alpha: edgeAlpha,
          weight: edgeWeight,
          limit: 10000,
        }),
        method: "POST",
      }));
    }

    const subgraphOutput = await saveToolOutput(subgraph, {
      tool: "focus_neighborhood.subgraph",
      center: id,
    });
    return dataToolResult(
      {
        ok: true,
        tool: "focus_neighborhood",
        center: id,
        depth,
        limit,
        mode,
        scope,
        nodes: nodes.length,
        edges: edges.length,
        subgraphOutput,
        idsSample: ids.slice(0, 25),
        operations,
      },
      { tool: "focus_neighborhood" },
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

async function applyStylePreset({
  nodeColorAttribute,
  edgeColorAttribute,
  nodeSizeAttribute = "degree",
  minSize = 4,
  maxSize = 24,
  highlight,
  nodeStyles = [],
  edgeStyles = [],
}) {
  try {
    return dataToolResult(
      await applyStylePresetData({
        nodeColorAttribute,
        edgeColorAttribute,
        nodeSizeAttribute,
        minSize,
        maxSize,
        highlight,
        nodeStyles,
        edgeStyles,
      }),
      { tool: "apply_style_preset" },
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

async function applyStylePresetData({
  nodeColorAttribute,
  edgeColorAttribute,
  nodeSizeAttribute = "degree",
  minSize = 4,
  maxSize = 24,
  highlight,
  nodeStyles = [],
  edgeStyles = [],
}, options = {}) {
  const operations = [];
  const nodeColor = await resolveAttribute("node", nodeColorAttribute, [
    "layer",
    "kind",
    "type",
    "group",
    "modularity_class",
  ]);
  const edgeColor = await resolveAttribute("edge", edgeColorAttribute, ["layer", "relation", "kind", "type"]);
  const nodeSize = disabledAttribute(nodeSizeAttribute) ? null : nodeSizeAttribute || "degree";

  if (nodeColor) {
    operations.push(await runPresetOperation("partition_nodes", {
      attribute: nodeColor,
      request: pathWithQuery("/graph/partition/nodes", { attribute: nodeColor }),
      method: "POST",
      dryRun: options.dryRun,
    }));
  }
  if (edgeColor) {
    operations.push(await runPresetOperation("partition_edges", {
      attribute: edgeColor,
      request: pathWithQuery("/graph/partition/edges", { attribute: edgeColor }),
      method: "POST",
      dryRun: options.dryRun,
    }));
  }
  if (nodeSize) {
    operations.push(await runPresetOperation("ranking_nodes", {
      attribute: nodeSize,
      request: pathWithQuery("/graph/ranking/nodes", { attribute: nodeSize, minSize, maxSize }),
      method: "POST",
      dryRun: options.dryRun,
    }));
  }
  for (const [index, style] of nodeStyles.entries()) {
    if (options.skipMissingAttributes && style.attribute && !(await options.hasAttribute?.("node", style.attribute))) {
      operations.push(skippedOperation("style_nodes", "missing_node_attribute", { index, selector: operationSelector(style) }));
      continue;
    }
    operations.push(await runPresetOperation("style_nodes", {
      index,
      selector: operationSelector(style),
      request: pathWithQuery("/graph/style/nodes", {
        ids: style.ids,
        attribute: style.attribute,
        op: style.op,
        value: style.value,
        color: style.color,
        size: style.size,
        alpha: style.alpha,
        limit: style.limit,
      }),
      method: "POST",
      dryRun: options.dryRun,
    }));
  }
  for (const [index, style] of edgeStyles.entries()) {
    if (options.skipMissingAttributes && style.attribute && !(await options.hasAttribute?.("edge", style.attribute))) {
      operations.push(skippedOperation("style_edges", "missing_edge_attribute", { index, selector: operationSelector(style) }));
      continue;
    }
    operations.push(await runPresetOperation("style_edges", {
      index,
      selector: operationSelector(style),
      request: pathWithQuery("/graph/style/edges", {
        ids: style.ids,
        attribute: style.attribute,
        op: style.op,
        value: style.value,
        color: style.color,
        weight: style.weight,
        alpha: style.alpha,
        limit: style.limit,
      }),
      method: "POST",
      dryRun: options.dryRun,
    }));
  }
  if (highlight?.ids || highlight?.attribute) {
    if (options.skipMissingAttributes && highlight.attribute && !(await options.hasAttribute?.("node", highlight.attribute))) {
      operations.push(skippedOperation("style_nodes", "missing_node_attribute", { selector: operationSelector(highlight) }));
    } else {
      operations.push(await runPresetOperation("style_nodes", {
        selector: operationSelector(highlight),
        request: pathWithQuery("/graph/style/nodes", {
          ids: highlight.ids,
          attribute: highlight.attribute,
          op: highlight.op,
          value: highlight.value,
          color: highlight.color,
          size: highlight.size,
          alpha: highlight.alpha,
          limit: highlight.limit,
        }),
        method: "POST",
        dryRun: options.dryRun,
      }));
    }
  }

  return {
    ok: true,
    tool: "apply_style_preset",
    mode: "mcp_orchestration",
    nodeColorAttribute: nodeColor,
    edgeColorAttribute: edgeColor,
    nodeSizeAttribute: nodeSize,
    operations,
  };
}

async function listViewPresets() {
  try {
    const loaded = await loadViewPresets();
    const presets = Object.entries(loaded.presets).map(([name, preset]) => ({
      name,
      description: expandPreset(name, loaded.presets).description || preset.description || "",
      extends: preset.extends || null,
      source: loaded.sourcesByPreset[name] || "unknown",
      ...viewPresetCapabilities(expandPreset(name, loaded.presets)),
    }));
    presets.sort((left, right) => left.name.localeCompare(right.name));
    return compactToolResult(
      {
        ok: true,
        builtInPath: builtInViewPresetsUrl.pathname,
        userPath: userViewPresetsPath,
        presets,
      },
      { tool: "list_view_presets" },
      null,
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

function viewPresetCapabilities(preset) {
  return {
    hasStatistics: Array.isArray(preset.statistics) && preset.statistics.length > 0,
    hasLayout: Boolean(preset.layout),
    hasFilter: Boolean(preset.filter || preset.focus?.filter),
    hasFocus: Boolean(preset.focus),
  };
}

async function applyViewPreset(args = {}) {
  try {
    const loaded = await loadViewPresets();
    const presetName = args.preset || loaded.defaultPreset || "architecture_overview";
    if (!loaded.presets[presetName]) {
      throw new Error(`Unknown view preset ${JSON.stringify(presetName)}. Use list_view_presets to see available presets.`);
    }

    const preset = expandPreset(presetName, loaded.presets);
    const config = buildViewPresetConfig(preset, args);
    const dryRun = Boolean(args.dryRun);
    const context = createViewPresetContext();
    const operations = [];

    if (config.resetFilters !== false) {
      operations.push(await runPresetOperation("reset_filters", {
        request: "/graph/filter/reset",
        method: "POST",
        dryRun,
      }));
    }

    if (config.runStatistics !== false) {
      for (const statistic of config.statistics || []) {
        if (!statistic?.name) {
          continue;
        }
        operations.push(await runPresetOperation("run_statistics", {
          name: statistic.name,
          request: pathWithQuery("/statistics/run", {
            ...operationParameters(statistic.parameters),
            name: statistic.name,
          }),
          method: "POST",
          timeoutMs: 120000,
          dryRun,
        }));
      }
    }

    const styleData = await applyStylePresetData(config.style || {}, {
      dryRun,
      skipMissingAttributes: !dryRun,
      hasAttribute: context.hasAttribute,
    });
    operations.push(...styleData.operations);

    if (config.circle) {
      if (!dryRun && config.circle.attribute && !(await context.hasAttribute("node", config.circle.attribute))) {
        operations.push(skippedOperation("layout_nodes_circle", "missing_node_attribute", {
          selector: operationSelector(config.circle),
        }));
      } else {
        operations.push(await runPresetOperation("layout_nodes_circle", {
          selector: operationSelector(config.circle),
          request: pathWithQuery("/graph/layout/nodes/circle", {
            ids: config.circle.ids,
            attribute: config.circle.attribute,
            op: config.circle.op,
            value: config.circle.value,
            radius: config.circle.radius,
            centerX: config.circle.centerX,
            centerY: config.circle.centerY,
            startAngle: config.circle.startAngle,
            clockwise: config.circle.clockwise,
            fixed: config.circle.fixed,
            limit: config.circle.limit,
          }),
          method: "POST",
          dryRun,
        }));
      }
    }

    if (config.runLayout !== false && config.layout?.name) {
      operations.push(await runPresetOperation("run_layout", {
        name: config.layout.name,
        request: pathWithQuery("/layouts/run", {
          ...operationParameters(config.layout.parameters),
          name: config.layout.name,
          iterations: config.layout.iterations,
        }),
        method: "POST",
        timeoutMs: 60000,
        dryRun,
      }));
    }

    if (config.filter) {
      const element = config.filter.element || "node";
      if (!dryRun && config.filter.attribute && !(await context.hasAttribute(element, config.filter.attribute))) {
        operations.push(skippedOperation("filter_graph", `missing_${element}_attribute`, {
          selector: operationSelector(config.filter),
        }));
      } else {
        operations.push(await runPresetOperation("filter_graph", {
          selector: operationSelector(config.filter),
          request: pathWithQuery("/graph/filter", {
            element,
            attribute: config.filter.attribute,
            op: config.filter.op,
            value: config.filter.value,
            mode: config.filter.mode,
            scope: config.filter.scope,
          }),
          method: "POST",
          dryRun,
        }));
      }
    }

    if (config.focus?.attribute || config.focus?.ids) {
      if (!dryRun && config.focus.attribute && !(await context.hasAttribute("node", config.focus.attribute))) {
        operations.push(skippedOperation("style_nodes", "missing_node_attribute", {
          phase: "post_filter_focus",
          selector: operationSelector(config.focus),
        }));
      } else {
        operations.push(await runPresetOperation("style_nodes", {
          phase: "post_filter_focus",
          selector: operationSelector(config.focus),
          request: pathWithQuery("/graph/style/nodes", {
            ids: config.focus.ids,
            attribute: config.focus.attribute,
            op: config.focus.op,
            value: config.focus.value,
            color: config.focus.color,
            size: config.focus.size,
            alpha: config.focus.alpha,
            limit: config.focus.limit,
          }),
          method: "POST",
          dryRun,
        }));
      }
    }

    return dataToolResult(
      {
        ok: true,
        tool: "apply_view_preset",
        mode: "mcp_orchestration",
        preset: presetName,
        source: loaded.sourcesByPreset[presetName] || "unknown",
        dryRun,
        config: compactViewPresetConfig(config),
        operations,
      },
      { tool: "apply_view_preset" },
    );
  } catch (error) {
    return errorToolResult(error);
  }
}

async function loadViewPresets() {
  const builtIn = normalizePresetDocument(await readJsonUrl(builtInViewPresetsUrl), builtInViewPresetsUrl.pathname);
  const loaded = {
    defaultPreset: builtIn.defaultPreset || "architecture_overview",
    presets: { ...builtIn.presets },
    sourcesByPreset: Object.fromEntries(Object.keys(builtIn.presets).map((name) => [name, "built-in"])),
  };

  const user = await readOptionalJsonFile(userViewPresetsPath);
  if (user) {
    const normalized = normalizePresetDocument(user, userViewPresetsPath);
    if (normalized.defaultPreset) {
      loaded.defaultPreset = normalized.defaultPreset;
    }
    for (const [name, preset] of Object.entries(normalized.presets)) {
      loaded.presets[name] = preset;
      loaded.sourcesByPreset[name] = userViewPresetsPath;
    }
  }
  return loaded;
}

async function readJsonUrl(url) {
  return JSON.parse(await readFile(url, "utf8"));
}

async function readOptionalJsonFile(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

function normalizePresetDocument(doc, source) {
  const presets = doc?.presets || doc;
  if (!isPlainObject(presets)) {
    throw new Error(`View preset config ${source} must be an object or contain a presets object.`);
  }
  return {
    defaultPreset: typeof doc?.defaultPreset === "string" ? doc.defaultPreset : null,
    presets,
  };
}

function expandPreset(name, presets, stack = []) {
  if (stack.includes(name)) {
    throw new Error(`View preset inheritance cycle: ${[...stack, name].join(" -> ")}`);
  }
  const preset = presets[name];
  if (!preset) {
    throw new Error(`View preset ${JSON.stringify(name)} extends unknown preset.`);
  }
  if (!preset.extends) {
    return deepClone(preset);
  }
  return mergeDeep(expandPreset(preset.extends, presets, [...stack, name]), withoutKey(preset, "extends"));
}

function buildViewPresetConfig(preset, args) {
  const config = deepClone(preset);
  if (args.resetFilters !== undefined) {
    config.resetFilters = args.resetFilters;
  }
  if (args.runLayout !== undefined) {
    config.runLayout = args.runLayout;
  }
  if (args.runStatistics !== undefined) {
    config.runStatistics = args.runStatistics;
  }
  if (args.statistics) {
    config.statistics = args.statistics;
  }
  if (args.layout) {
    config.layout = mergeDeep(config.layout || {}, args.layout);
  }
  if (args.filter) {
    config.filter = args.filter;
  }
  if (args.style) {
    config.style = mergeDeep(config.style || {}, args.style);
  }

  config.focus = mergeFocusOverrides(config.focus, args);
  if (config.focus) {
    config.style = config.style || {};
    config.style.nodeStyles = [...(config.style.nodeStyles || []), focusStyle(config.focus)];
    if (config.focus.filter && !args.filter) {
      config.filter = {
        element: "node",
        attribute: config.focus.attribute,
        op: config.focus.op || "eq",
        value: config.focus.value,
      };
    }
    const circle = focusCircle(config.focus, args);
    if (circle) {
      config.circle = circle;
    }
  }

  return config;
}

function mergeFocusOverrides(focus, args) {
  const hasOverride = [
    "focusAttribute",
    "focusOp",
    "focusValue",
    "focusColor",
    "focusSize",
    "focusAlpha",
    "circleFocus",
    "circleRadius",
    "circleFixed",
  ].some((key) => args[key] !== undefined);
  if (!focus && !hasOverride) {
    return null;
  }
  const out = { ...(focus || {}) };
  if (args.focusAttribute !== undefined) out.attribute = args.focusAttribute;
  if (args.focusOp !== undefined) out.op = args.focusOp;
  if (args.focusValue !== undefined) out.value = args.focusValue;
  if (args.focusColor !== undefined) out.color = args.focusColor;
  if (args.focusSize !== undefined) out.size = args.focusSize;
  if (args.focusAlpha !== undefined) out.alpha = args.focusAlpha;
  if (args.circleFocus !== undefined) out.circle = args.circleFocus;
  if (args.circleRadius !== undefined) out.circleRadius = args.circleRadius;
  if (args.circleFixed !== undefined) out.circleFixed = args.circleFixed;
  return out.attribute ? out : null;
}

function focusStyle(focus) {
  return {
    attribute: focus.attribute,
    op: focus.op || "eq",
    value: focus.value,
    color: focus.color,
    size: focus.size,
    alpha: focus.alpha,
    limit: focus.limit,
  };
}

function focusCircle(focus, args) {
  if (!focus.circle && args.circleFocus === undefined) {
    return null;
  }
  const circle = isPlainObject(focus.circle) ? focus.circle : {};
  return {
    attribute: focus.attribute,
    op: focus.op || "eq",
    value: focus.value,
    radius: focus.circleRadius || circle.radius,
    centerX: circle.centerX,
    centerY: circle.centerY,
    startAngle: circle.startAngle,
    clockwise: circle.clockwise,
    fixed: focus.circleFixed ?? circle.fixed ?? true,
    limit: focus.limit,
  };
}

function createViewPresetContext() {
  const cache = new Map();
  return {
    hasAttribute: async (element, attribute) => {
      if (!attribute) {
        return true;
      }
      const key = `${element}:${attribute}`;
      if (cache.has(key)) {
        return cache.get(key);
      }
      const attrs = await fetchJson(pathWithQuery("/graph/attributes", { element }));
      const wanted = normalizeIdentifier(attribute);
      const found = (attrs.attributes || []).some((attr) => {
        const id = normalizeIdentifier(attr.id || "");
        const title = normalizeIdentifier(attr.title || "");
        return id === wanted || title === wanted || id.endsWith(wanted) || title.endsWith(wanted);
      });
      cache.set(key, found);
      return found;
    },
  };
}

async function runPresetOperation(operation, {
  request,
  method = "GET",
  timeoutMs,
  dryRun = false,
  ...metadata
}) {
  if (dryRun) {
    return {
      operation,
      ...metadata,
      request,
      method,
      dryRun: true,
    };
  }
  return {
    operation,
    ...metadata,
    result: await fetchJson(request, { method, timeoutMs }),
  };
}

function skippedOperation(operation, reason, metadata = {}) {
  return {
    operation,
    skipped: true,
    reason,
    ...metadata,
  };
}

function operationSelector(value = {}) {
  return {
    ids: value.ids,
    attribute: value.attribute,
    op: value.op,
    value: value.value,
    limit: value.limit,
  };
}

function normalizeAttributeList(attributes, attribute) {
  return uniqueStrings([
    ...(Array.isArray(attributes) ? attributes : []),
    attribute,
  ].filter((value) => value && !disabledAttribute(value)));
}

function attributeDistributions(records, attributes, limit) {
  return attributes.map((attribute) => ({
    attribute,
    values: topCountsMap(countRecordAttribute(records, attribute), limit),
  }));
}

function countRecordAttribute(records, attribute) {
  const counts = new Map();
  for (const record of records) {
    const value = recordAttribute(record, attribute);
    const key = value === undefined || value === null || value === "" ? "(missing)" : String(value);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return counts;
}

function topCountsMap(counts, limit) {
  return [...counts.entries()]
    .sort((left, right) => right[1] - left[1])
    .slice(0, limit)
    .map(([value, count]) => ({ value, count }));
}

function subgraphNodeStats(nodes, edges, nodeIds) {
  const stats = new Map();
  for (const node of nodes) {
    stats.set(String(node.id), {
      degree: 0,
      inDegree: 0,
      outDegree: 0,
      weightedDegree: 0,
    });
  }
  for (const edge of edges) {
    const source = String(edge.source);
    const target = String(edge.target);
    if (!nodeIds.has(source) || !nodeIds.has(target)) {
      continue;
    }
    const weight = numberValue(edge.weight) || 1;
    const sourceStats = stats.get(source);
    const targetStats = stats.get(target);
    if (sourceStats) {
      sourceStats.degree += 1;
      sourceStats.outDegree += 1;
      sourceStats.weightedDegree += weight;
    }
    if (targetStats) {
      targetStats.degree += 1;
      targetStats.inDegree += 1;
      targetStats.weightedDegree += weight;
    }
  }
  return stats;
}

function neighborhoodDegreeShape(nodes, stats) {
  let leaves = 0;
  let sources = 0;
  let sinks = 0;
  let isolated = 0;
  let globalLeaves = 0;
  for (const node of nodes) {
    const nodeStats = stats.get(String(node.id)) || {};
    if ((nodeStats.degree || 0) === 0) {
      isolated++;
    }
    if ((nodeStats.degree || 0) === 1) {
      leaves++;
    }
    if ((nodeStats.inDegree || 0) === 0 && (nodeStats.outDegree || 0) > 0) {
      sources++;
    }
    if ((nodeStats.outDegree || 0) === 0 && (nodeStats.inDegree || 0) > 0) {
      sinks++;
    }
    if (numberValue(node.degree) === 1) {
      globalLeaves++;
    }
  }
  return {
    leaves,
    sources,
    sinks,
    isolated,
    globalLeaves,
  };
}

function topNodesBy(nodes, stats, scoreFn, limit, options = {}) {
  return nodes
    .map((node) => {
      const nodeStats = stats.get(String(node.id)) || {};
      return {
        node,
        nodeStats,
        score: scoreFn(node, nodeStats),
      };
    })
    .filter((item) => Number.isFinite(item.score))
    .sort((left, right) => right.score - left.score)
    .slice(0, limit)
    .map((item) => ({
      ...compactAnalysisNode(item.node, item.nodeStats, options),
      [options.scoreName || "score"]: item.score,
    }));
}

function compactAnalysisNode(node, stats = {}, options = {}) {
  const attributes = {};
  for (const attribute of uniqueStrings(options.attributes || [])) {
    const value = recordAttribute(node, attribute);
    if (value !== undefined && value !== null && value !== "") {
      attributes[attribute] = compactPrimitive(value);
    }
  }
  const labelValue = options.labelAttribute ? recordAttribute(node, options.labelAttribute) : undefined;
  return {
    id: node.id,
    label: node.label,
    title: labelValue === undefined ? undefined : compactPrimitive(labelValue),
    globalDegree: numberValue(node.degree),
    subgraphDegree: stats.degree || 0,
    subgraphInDegree: stats.inDegree || 0,
    subgraphOutDegree: stats.outDegree || 0,
    attributes,
  };
}

function recordAttribute(record, attribute) {
  if (!attribute) {
    return undefined;
  }
  if (Object.prototype.hasOwnProperty.call(record, attribute)) {
    return record[attribute];
  }
  const attrs = isPlainObject(record.attributes) ? record.attributes : {};
  if (Object.prototype.hasOwnProperty.call(attrs, attribute)) {
    return attrs[attribute];
  }
  const wanted = normalizeIdentifier(attribute);
  const entry = Object.entries(attrs).find(([key]) => normalizeIdentifier(key) === wanted);
  return entry ? entry[1] : undefined;
}

function numberValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function uniqueStrings(values) {
  return [...new Set(values.filter((value) => typeof value === "string" && value.trim()).map((value) => value.trim()))];
}

function compactViewPresetConfig(config) {
  return {
    description: config.description,
    resetFilters: config.resetFilters !== false,
    statistics: (config.statistics || []).map((statistic) => statistic.name),
    style: {
      nodeColorAttribute: config.style?.nodeColorAttribute,
      edgeColorAttribute: config.style?.edgeColorAttribute,
      nodeSizeAttribute: config.style?.nodeSizeAttribute,
      minSize: config.style?.minSize,
      maxSize: config.style?.maxSize,
      nodeStyles: config.style?.nodeStyles?.length || 0,
      edgeStyles: config.style?.edgeStyles?.length || 0,
    },
    focus: config.focus ? operationSelector(config.focus) : null,
    circle: config.circle ? operationSelector(config.circle) : null,
    layout: config.layout
      ? {
          name: config.layout.name,
          iterations: config.layout.iterations,
          parameters: config.layout.parameters,
        }
      : null,
    filter: config.filter || null,
  };
}

function deepClone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function mergeDeep(base, override) {
  if (!isPlainObject(base) || !isPlainObject(override)) {
    return deepClone(override);
  }
  const out = deepClone(base);
  for (const [key, value] of Object.entries(override)) {
    if (isPlainObject(value) && isPlainObject(out[key])) {
      out[key] = mergeDeep(out[key], value);
    } else {
      out[key] = deepClone(value);
    }
  }
  return out;
}

function withoutKey(value, key) {
  const out = { ...value };
  delete out[key];
  return out;
}

async function resolveAttribute(element, requested, candidates) {
  if (disabledAttribute(requested)) {
    return null;
  }
  if (requested && requested !== "auto") {
    return requested;
  }
  const data = await fetchJson(pathWithQuery("/graph/attributes", { element }));
  const attrs = data.attributes || [];
  for (const candidate of candidates) {
    const wanted = normalizeIdentifier(candidate);
    const found = attrs.find((attr) => {
      const id = normalizeIdentifier(attr.id || "");
      const title = normalizeIdentifier(attr.title || "");
      return id === wanted || title === wanted || id.endsWith(wanted);
    });
    if (found) {
      return candidate;
    }
  }
  return null;
}

function disabledAttribute(value) {
  const normalized = normalizeIdentifier(value || "");
  return normalized === "none" || normalized === "off" || normalized === "false";
}

function normalizeIdentifier(value) {
  return String(value)
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

async function dataToolResult(data, metadata = {}) {
  const savedOutput = await saveToolOutput(data, metadata);
  return compactToolResult(data, metadata, savedOutput);
}

function compactToolResult(data, metadata = {}, savedOutput = null) {
  const compact = {
    ok: inferOk(data),
    savedOutput,
    summary: compactValue(data),
  };
  if (metadata.tool) {
    compact.tool = metadata.tool;
  }
  if (metadata.endpoint) {
    compact.endpoint = metadata.endpoint;
  }
  if (metadata.method) {
    compact.method = metadata.method;
  }

  const fitted = fitCompactResult(compact, data);
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(fitted, null, 2),
      },
    ],
    structuredContent: fitted,
  };
}

function errorToolResult(error) {
  return {
    isError: true,
    content: [
      {
        type: "text",
        text: error instanceof Error ? error.message : String(error),
      },
    ],
  };
}

async function jsonToolResult(path, options = {}) {
  try {
    const data = await fetchJson(path, options);
    return dataToolResult(data, {
      endpoint: path,
      method: options.method || "GET",
    });
  } catch (error) {
    return errorToolResult(error);
  }
}

async function saveToolOutput(data, metadata = {}) {
  await mkdir(outputDir, { recursive: true });
  const createdAt = new Date().toISOString();
  const slug = outputSlug(metadata);
  const id = `${createdAt.replace(/[:.]/g, "-")}-${slug}-${randomUUID().slice(0, 8)}.json`;
  const file = nodePath.join(outputDir, id);
  const payload = {
    metadata: {
      createdAt,
      gephiControlUrl,
      ...metadata,
    },
    data,
  };
  const text = JSON.stringify(payload, null, 2);
  await writeFile(file, text, "utf8");
  return {
    id,
    path: file,
    bytes: Buffer.byteLength(text, "utf8"),
    createdAt,
  };
}

function outputSlug(metadata = {}) {
  const source = metadata.tool || metadata.endpoint || "output";
  return (
    String(source)
      .replace(/[?#].*$/, "")
      .replace(/[^a-zA-Z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .toLowerCase()
      .slice(0, 48) || "output"
  );
}

function resolveSavedOutputId(id) {
  const basename = nodePath.basename(id);
  if (basename !== id) {
    throw new Error("Saved output id must be a file id, not a path.");
  }
  if (!/^[a-zA-Z0-9._-]+$/.test(basename)) {
    throw new Error("Saved output id contains unsupported characters.");
  }
  const filename = basename.endsWith(".json") ? basename : `${basename}.json`;
  return nodePath.join(outputDir, filename);
}

function inferOk(data) {
  if (data && typeof data === "object" && !Array.isArray(data) && "ok" in data) {
    return Boolean(data.ok);
  }
  return true;
}

function fitCompactResult(compact, data) {
  const text = JSON.stringify(compact, null, 2);
  if (Buffer.byteLength(text, "utf8") <= summaryMaxBytes) {
    return compact;
  }
  return {
    ok: compact.ok,
    savedOutput: compact.savedOutput,
    tool: compact.tool,
    endpoint: compact.endpoint,
    method: compact.method,
    summary: minimalSummary(data),
  };
}

function minimalSummary(value) {
  if (Array.isArray(value)) {
    return { type: "array", count: value.length };
  }
  if (!isPlainObject(value)) {
    return compactPrimitive(value);
  }

  const result = {
    type: "object",
    keys: Object.keys(value).slice(0, 50),
  };
  const counts = {};
  const primitives = {};
  for (const [key, item] of Object.entries(value)) {
    if (Array.isArray(item)) {
      counts[key] = item.length;
    } else if (isPlainObject(item)) {
      counts[key] = { keys: Object.keys(item).length };
    } else {
      primitives[key] = compactPrimitive(item);
    }
  }
  if (Object.keys(counts).length > 0) {
    result.counts = counts;
  }
  if (Object.keys(primitives).length > 0) {
    result.values = primitives;
  }
  return result;
}

function compactValue(value, depth = 0) {
  if (Array.isArray(value)) {
    const sampleSize = depth === 0 ? 5 : 2;
    return {
      type: "array",
      count: value.length,
      sample: value.slice(0, sampleSize).map((item) => compactValue(item, depth + 1)),
    };
  }
  if (!isPlainObject(value)) {
    return compactPrimitive(value);
  }

  if ("operation" in value && "result" in value) {
    return compactOperation(value);
  }

  if (depth > 2) {
    return {
      type: "object",
      keyCount: Object.keys(value).length,
      keys: Object.keys(value).slice(0, 20),
    };
  }

  const result = {};
  const entries = Object.entries(value);
  const maxKeys = depth === 0 ? 24 : 10;
  for (const [key, item] of entries.slice(0, maxKeys)) {
    if (key === "attributes" && isPlainObject(item)) {
      result[key] = compactAttributes(item);
    } else {
      result[key] = compactValue(item, depth + 1);
    }
  }
  if (entries.length > maxKeys) {
    result._truncatedKeys = entries.length - maxKeys;
  }
  return result;
}

function compactOperation(operation) {
  const result = isPlainObject(operation.result) ? operation.result : {};
  const selector = isPlainObject(operation.selector) ? operation.selector : {};
  return {
    operation: operation.operation,
    index: operation.index,
    attribute: operation.attribute,
    selector: {
      ids: selector.ids,
      attribute: selector.attribute,
      op: selector.op,
      value: selector.value,
      limit: selector.limit,
    },
    ok: "ok" in result ? Boolean(result.ok) : undefined,
    matchedNodes: result.matchedNodes,
    styledNodes: result.styledNodes,
    matchedEdges: result.matchedEdges,
    styledEdges: result.styledEdges,
    updatedNodes: result.updatedNodes,
    updatedEdges: result.updatedEdges,
  };
}

function compactAttributes(attributes) {
  const entries = Object.entries(attributes);
  const values = {};
  for (const [key, value] of entries) {
    if (isSignalAttribute(key)) {
      values[key] = compactPrimitive(value);
    }
  }
  return {
    type: "attributes",
    count: entries.length,
    keys: entries.map(([key]) => key).slice(0, 24),
    signals: values,
  };
}

function isSignalAttribute(key) {
  const normalized = normalizeIdentifier(key);
  return [
    "kind",
    "type",
    "layer",
    "group",
    "foreign",
    "detaillevel",
    "iscontract",
    "modularityclass",
  ].some((signal) => normalized === signal || normalized.endsWith(signal));
}

function compactPrimitive(value) {
  if (typeof value === "string" && value.length > 160) {
    return `${value.slice(0, 157)}...`;
  }
  if (value === undefined) {
    return null;
  }
  return value;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function positiveInteger(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

async function fetchJson(path, options = {}) {
  const url = new URL(path, normalizeBaseUrl(gephiControlUrl));
  const { timeoutMs = 5000, ...requestOptions } = options;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  let response;
  try {
    response = await fetch(url, { ...requestOptions, signal: controller.signal });
  } catch (error) {
    if (error?.name === "AbortError") {
      throw new Error(`Timed out calling ${url}`);
    }
    throw new Error(`Failed to call ${url}: ${error.message}`);
  } finally {
    clearTimeout(timeout);
  }

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Gephi endpoint ${url} returned HTTP ${response.status}: ${text}`);
  }

  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`Gephi endpoint ${url} returned non-JSON response: ${text}`);
  }
}

function normalizeBaseUrl(rawUrl) {
  return rawUrl.endsWith("/") ? rawUrl : `${rawUrl}/`;
}

function pathWithQuery(path, params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  const suffix = search.toString();
  return suffix ? `${path}?${suffix}` : path;
}

function operationParameters(parameters = {}) {
  const query = {};
  for (const [key, value] of Object.entries(parameters)) {
    if (key !== "name" && key !== "iterations") {
      query[key] = value;
    }
  }
  return query;
}

const transport = new StdioServerTransport();
await server.connect(transport);
