#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

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

async function jsonToolResult(path) {
  try {
    const data = await fetchJson(path);
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

async function fetchJson(path) {
  const url = new URL(path, normalizeBaseUrl(gephiControlUrl));
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  let response;
  try {
    response = await fetch(url, { signal: controller.signal });
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

const transport = new StdioServerTransport();
await server.connect(transport);
