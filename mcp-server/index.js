#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const DEFAULT_GEPHI_CONTROL_URL = "http://127.0.0.1:8765";
const gephiControlUrl =
  process.env.GEPHI_CONTROL_URL ||
  process.env.GEPHI_MCP_HTTP_URL ||
  DEFAULT_GEPHI_CONTROL_URL;

const server = new McpServer({
  name: "gephi-mcp-plugin-sidecar",
  version: "0.1.0",
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
  "apply_code_graph_preset",
  {
    title: "Apply Code Graph Preset",
    description:
      "Apply an archmotif-oriented view preset: color nodes by kind, grey foreign nodes, size by degree, and color edges by relation.",
    inputSchema: {},
    annotations: mutatingAnnotations(),
  },
  async () => jsonToolResult("/graph/preset/code", { method: "POST" }),
);

server.registerTool(
  "filter_graph",
  {
    title: "Filter Graph",
    description:
      "Replace the visible Gephi view with a node or edge attribute filter. Use reset_filters to restore the full graph.",
    inputSchema: {
      element: z.enum(["node", "edge"]).optional(),
      attribute: z.string(),
      op: z
        .enum(["eq", "neq", "contains", "exists", "missing", "gt", "gte", "lt", "lte"])
        .optional(),
      value: z.string().optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ element, attribute, op, value }) =>
    jsonToolResult(pathWithQuery("/graph/filter", { element, attribute, op, value }), {
      method: "POST",
    }),
);

server.registerTool(
  "reset_filters",
  {
    title: "Reset Filters",
    description: "Restore Gephi's visible view to the full graph.",
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
  "run_layout",
  {
    title: "Run Layout",
    description: "Run a Gephi layout algorithm for a bounded number of iterations.",
    inputSchema: {
      name: z.string(),
      iterations: z.number().int().min(1).max(5000).optional(),
    },
    annotations: mutatingAnnotations(),
  },
  async ({ name, iterations }) =>
    jsonToolResult(pathWithQuery("/layouts/run", { name, iterations }), { method: "POST" }),
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

async function jsonToolResult(path, options = {}) {
  try {
    const data = await fetchJson(path, options);
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(data, null, 2),
        },
      ],
      structuredContent: data,
    };
  } catch (error) {
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
}

async function fetchJson(path, options = {}) {
  const url = new URL(path, normalizeBaseUrl(gephiControlUrl));
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  let response;
  try {
    response = await fetch(url, { ...options, signal: controller.signal });
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

const transport = new StdioServerTransport();
await server.connect(transport);
