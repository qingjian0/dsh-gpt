/**
 * DSH Agent tools for the standalone dsh-gpt LSPosed module.
 *
 * Reads the API endpoint and key from:
 *   $DSH_HOME/config/dsh-gpt-hook.json
 * {
 *   "baseUrl": "http://127.0.0.1:8766",
 *   "apiKey": "<dsh-gpt local API key>"
 * }
 *
 * This adapter never calls the retired 3083 DSH Bridge.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const name = "dsh-gpt-hook-agent";
export const inject = ["tools"];

const OUTPUT = {
  schema: { type: "object", additionalProperties: true },
  render: (_args, value) => [{ type: "text", text: JSON.stringify(value, null, 2) }],
};
const REQUEST_TIMEOUT_MS = 10000;
const MAX_DISCOVERY_LIMIT = 200;

function readConnectionConfig() {
  const home = process.env.DSH_HOME;
  if (!home) throw new Error("DSH_HOME is unavailable; Hook Agent is not configured");
  const file = path.join(home, "config", "dsh-gpt-hook.json");
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    throw new Error("dsh-gpt Hook connection config is missing or invalid");
  }
  const base = new URL(String(parsed.baseUrl || "http://127.0.0.1:8766"));
  const loopback = base.hostname === "127.0.0.1" || base.hostname === "localhost" ||
    base.hostname === "[::1]" || base.hostname === "::1";
  if (base.protocol !== "http:" || !loopback || base.username || base.password ||
      base.pathname !== "/" || base.search || base.hash) {
    throw new Error("Hook API must use a plain HTTP loopback URL");
  }
  const apiKey = typeof parsed.apiKey === "string" ? parsed.apiKey.trim() : "";
  if (apiKey.length < 16 || apiKey.length > 512 || /[\r\n]/.test(apiKey)) {
    throw new Error("dsh-gpt API key is missing or invalid");
  }
  return { baseUrl: base.origin, apiKey };
}

async function request(method, route, body) {
  const { baseUrl, apiKey } = readConnectionConfig();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(new URL(route, baseUrl), {
      method,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await response.text();
    let value;
    try { value = raw ? JSON.parse(raw) : {}; }
    catch { throw new Error("Hook API returned a non-JSON response"); }
    if (!response.ok) {
      const type = value?.error?.type || "request_failed";
      throw new Error(`dsh-gpt API ${response.status}: ${String(type).slice(0, 80)}`);
    }
    if (value?.ok === false) throw new Error("dsh-gpt rejected the Hook operation");
    return value;
  } catch (error) {
    if (error?.name === "AbortError") throw new Error("dsh-gpt Hook API timed out");
    if (String(error?.message || "").includes("fetch failed")) {
      throw new Error("dsh-gpt Hook API is unreachable; check LSPosed module and local API");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function loadDefineTool() {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const roots = [];
  const add = (root) => {
    if (!root) return;
    roots.push(path.resolve(root, "lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js"));
    roots.push(path.resolve(root, "node_modules/@deepseek-ai/dsh-tools/lib/index.js"));
  };
  add(process.env.DSH_HOME);
  let cursor = here;
  for (let depth = 0; depth < 10; depth++) {
    add(cursor);
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }
  for (const candidate of new Set(roots)) {
    try {
      if (!fs.existsSync(candidate)) continue;
      const mod = await import(pathToFileURL(candidate).href);
      if (typeof mod.defineTool === "function") return mod.defineTool;
    } catch { /* try the next engine layout */ }
  }
  return undefined;
}

function register(ctx, defineTool, definition) {
  ctx.tools.register(defineTool({ ...definition, output: OUTPUT }));
}

async function apply(ctx) {
  const defineTool = await loadDefineTool();
  if (!defineTool) {
    console.warn("[dsh-gpt-hook-agent] dsh-tools defineTool unavailable; tools not registered");
    return;
  }

  register(ctx, defineTool, {
    name: "dsh_hook_status",
    description: "Read authenticated dsh-gpt Hook runtime status and live HookHandle inventory.",
    parameters: {},
    execute: async () => request("GET", "/v1/hook/status"),
  });
  register(ctx, defineTool, {
    name: "dsh_hook_events",
    description: "Read the bounded metadata-only Hook observation report. Never returns method arguments, return values, headers, tokens or bodies.",
    parameters: {},
    execute: async () => request("GET", "/v1/hook/events"),
  });
  register(ctx, defineTool, {
    name: "dsh_hook_rules_get",
    description: "Read the current declarative Hook rule snapshot.",
    parameters: {},
    execute: async () => request("GET", "/v1/hook/config"),
  });
  register(ctx, defineTool, {
    name: "dsh_hook_rules_update",
    description: "Replace and validate the declarative Hook configuration. The target runtime's live inventory must be checked separately.",
    parameters: {
      config: { type: "object", required: true, description: "Complete schemaVersion 1 Hook configuration, restricted by dsh-gpt to com.openai.*." },
    },
    execute: async ({ config }) => {
      if (!config || typeof config !== "object" || Array.isArray(config)) {
        throw new Error("config must be a JSON object");
      }
      return request("PUT", "/v1/hook/config", config);
    },
  });
  register(ctx, defineTool, {
    name: "dsh_hook_discover",
    description: "Request bounded metadata-only class and method discovery within com.openai.*.",
    parameters: {
      prefix: { type: "string", description: "Package prefix within com.openai.*" },
      className: { type: "string", description: "Optional exact class name within com.openai.*" },
      limit: { type: "integer", description: "Maximum records, 1 to 200." },
      cursor: { type: "integer", description: "Non-negative page cursor." },
    },
    execute: async ({ prefix = "com.openai.", className, limit = 100, cursor = 0 } = {}) => {
      const boundedLimit = Math.min(MAX_DISCOVERY_LIMIT, Math.max(1, Number(limit) || 100));
      const boundedCursor = Math.max(0, Number(cursor) || 0);
      return request("POST", "/v1/hook/discover", {
        prefix: String(prefix),
        ...(className ? { className: String(className) } : {}),
        limit: boundedLimit,
        cursor: boundedCursor,
      });
    },
  });
  register(ctx, defineTool, {
    name: "dsh_hook_discovery_status",
    description: "Read the lifecycle and metadata result of a discovery request.",
    parameters: { requestId: { type: "string", required: true } },
    execute: async ({ requestId }) => {
      if (typeof requestId !== "string" || !/^[a-f0-9-]{16,64}$/i.test(requestId)) {
        throw new Error("requestId is invalid");
      }
      return request("GET", `/v1/hook/discovery/${encodeURIComponent(requestId)}`);
    },
  });
  register(ctx, defineTool, {
    name: "dsh_hook_discovery_cancel",
    description: "Cancel a queued or running bounded discovery request.",
    parameters: { requestId: { type: "string", required: true } },
    execute: async ({ requestId }) => {
      if (typeof requestId !== "string" || !/^[a-f0-9-]{16,64}$/i.test(requestId)) {
        throw new Error("requestId is invalid");
      }
      return request("DELETE", `/v1/hook/discovery/${encodeURIComponent(requestId)}`);
    },
  });
  register(ctx, defineTool, {
    name: "dsh_hook_enable",
    description: "Enable or disable the LSPosed Hook runtime. This is separate from enabling this DSH Agent plugin.",
    parameters: { enabled: { type: "boolean", required: true } },
    execute: async ({ enabled }) => {
      if (typeof enabled !== "boolean") throw new Error("enabled must be boolean");
      return request("PUT", "/v1/hook/enabled", { enabled });
    },
  });
}

export { apply };
