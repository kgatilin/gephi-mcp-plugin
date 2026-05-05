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
const command = process.argv[2] || "list";

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
      arguments: {},
    });
    console.log(JSON.stringify(result, null, 2));
  }
} finally {
  await client.close();
}
