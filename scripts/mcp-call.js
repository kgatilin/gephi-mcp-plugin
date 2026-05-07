#!/usr/bin/env node

import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  getDefaultEnvironment,
  StdioClientTransport,
} from "@modelcontextprotocol/sdk/client/stdio.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "..");
const serverPath = resolve(repoRoot, "mcp-server/index.js");
const command = process.argv[2] || "help";
const args = parseArgs(process.argv[3]);

if (command === "help" || command === "--help" || command === "-h") {
  printUsage();
  process.exit(0);
}

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [serverPath],
  cwd: repoRoot,
  env: {
    ...getDefaultEnvironment(),
    GEPHI_CONTROL_URL:
      process.env.GEPHI_CONTROL_URL ||
      process.env.GEPHI_MCP_HTTP_URL ||
      "http://127.0.0.1:8765",
  },
  stderr: "inherit",
});

const client = new Client({
  name: "gephi-mcp-smoke-client",
  version: "0.1.0",
});

try {
  await client.connect(transport);

  if (command === "list") {
    const result = await client.listTools();
    console.log(JSON.stringify(result.tools, null, 2));
  } else {
    const result = await client.callTool({
      name: command,
      arguments: args,
    });
    console.log(JSON.stringify(result.structuredContent ?? result, null, 2));
  }
} finally {
  await client.close();
}

function parseArgs(raw) {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw);
  } catch (error) {
    console.error(`Invalid JSON arguments: ${raw}`);
    process.exit(2);
  }
}

function printUsage() {
  console.log(`Usage:
  gephi-mcp help
  gephi-mcp list
  gephi-mcp <tool-name> [json-arguments]

Examples:
  gephi-mcp gephi_health
  gephi-mcp get_graph_summary
  gephi-mcp open_graph '{"path":"/tmp/graph.graphml"}'
  gephi-mcp export_preview '{"path":"/tmp/graph.png","width":2400,"height":1800,"edgeThickness":0.25,"arrowSize":0}'
  gephi-mcp apply_view_preset '{"preset":"architecture_overview"}'
  gephi-mcp run_layout '{"name":"ForceAtlas 2","iterations":100,"parameters":{"scalingRatio":10,"adjustSizes":true,"linLogMode":false}}'

This CLI calls Gephi MCP tools through the stdio sidecar. It does not call the
Gephi plugin HTTP endpoint directly.`);
}
