import path from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { TELEGRAM_SEND_MESSAGE_INPUT_SCHEMA, TELEGRAM_SEND_MESSAGE_OUTPUT_SCHEMA, validateTelegramSendMessageInput } from "./schemas.mjs";
import { authenticateWorkspace, checkTelegramPermissions, loadWorkspaceRegistry, resolveTelegramBotToken } from "./workspaces.mjs";
import { queueOutbox, writeAuditLog } from "./localStore.mjs";
import { withRetry } from "./retry.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const TEMPLATE_ROOT = path.resolve(__dirname, "..");

const WORKSPACES_FILE = process.env.OPENCLAW_WORKSPACES_FILE
  ? path.resolve(process.env.OPENCLAW_WORKSPACES_FILE)
  : path.join(TEMPLATE_ROOT, "workspaces.local.json");

const DATA_DIR = process.env.OPENCLAW_DATA_DIR
  ? path.resolve(process.env.OPENCLAW_DATA_DIR)
  : path.join(TEMPLATE_ROOT, "data");

const TOOL_NAME = "telegram_send_message";

const TOOL_DEFINITION = {
  name: TOOL_NAME,
  description: [
    "Send a Telegram message with workspace-scoped auth, permissions, local-first queueing, and retries.",
    "Default behavior is dry-run queue mode to keep workflows local-first."
  ].join(" "),
  inputSchema: TELEGRAM_SEND_MESSAGE_INPUT_SCHEMA,
  outputSchema: TELEGRAM_SEND_MESSAGE_OUTPUT_SCHEMA
};

function formatToolResult(payload, isError = false) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
    isError
  };
}

async function sendTelegramMessage({ botToken, chatId, text, parseMode }) {
  const url = `https://api.telegram.org/bot${botToken}/sendMessage`;
  const body = {
    chat_id: chatId,
    text
  };
  if (parseMode && parseMode !== "None") {
    body.parse_mode = parseMode;
  }

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
  } catch (error) {
    const err = new Error(`Telegram network error: ${error.message}`);
    err.retryable = true;
    throw err;
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    payload = {};
  }

  if (!response.ok || !payload.ok) {
    const err = new Error(payload.description || `Telegram API error (HTTP ${response.status})`);
    err.retryable = response.status === 408 || response.status === 429 || response.status >= 500;
    throw err;
  }

  return payload.result;
}

async function handleTelegramSendMessage(args) {
  const requestId = randomUUID();
  const validation = validateTelegramSendMessageInput(args);
  if (!validation.ok) {
    return formatToolResult({
      ok: false,
      request_id: requestId,
      errors: validation.errors
    }, true);
  }

  const input = validation.value;
  const resultBase = {
    ok: true,
    request_id: requestId,
    workspace_id: input.workspace_id,
    chat_id: input.chat_id,
    dry_run: input.dry_run,
    queued: false,
    attempts: 0
  };

  try {
    const registry = await loadWorkspaceRegistry(WORKSPACES_FILE);
    const workspace = authenticateWorkspace({
      registry,
      workspaceId: input.workspace_id,
      workspaceToken: input.workspace_token
    });
    checkTelegramPermissions({
      workspace,
      chatId: input.chat_id,
      text: input.text
    });

    await writeAuditLog(DATA_DIR, {
      level: "info",
      event: "telegram_send_message.received",
      request_id: requestId,
      workspace_id: input.workspace_id,
      chat_id: input.chat_id,
      dry_run: input.dry_run,
      metadata: input.metadata
    });

    const networkAllowed = Boolean(workspace.allow_network);
    const shouldQueue = input.dry_run || !networkAllowed;

    if (shouldQueue) {
      await queueOutbox(DATA_DIR, {
        request_id: requestId,
        workspace_id: input.workspace_id,
        tool_name: TOOL_NAME,
        chat_id: input.chat_id,
        text: input.text,
        parse_mode: input.parse_mode,
        reason: input.dry_run ? "dry_run" : "workspace_network_disabled",
        metadata: input.metadata
      });
      const queuedResult = {
        ...resultBase,
        dry_run: true,
        queued: true,
        attempts: 0
      };
      return formatToolResult(queuedResult);
    }

    const botToken = resolveTelegramBotToken({
      workspace,
      workspaceId: input.workspace_id
    });
    if (!botToken) {
      throw new Error("Telegram bot token not configured in environment");
    }

    const { value, attempts } = await withRetry(
      () => sendTelegramMessage({
        botToken,
        chatId: input.chat_id,
        text: input.text,
        parseMode: input.parse_mode
      }),
      {
        maxAttempts: 4,
        baseDelayMs: 250,
        maxDelayMs: 2500,
        shouldRetry: (error) => Boolean(error?.retryable),
        onRetry: ({ attempt, delay, error }) => writeAuditLog(DATA_DIR, {
          level: "warn",
          event: "telegram_send_message.retry",
          request_id: requestId,
          attempt,
          next_delay_ms: delay,
          message: error.message
        })
      }
    );

    const liveResult = {
      ...resultBase,
      dry_run: false,
      queued: false,
      attempts,
      message_id: value?.message_id
    };

    await writeAuditLog(DATA_DIR, {
      level: "info",
      event: "telegram_send_message.sent",
      request_id: requestId,
      attempts,
      message_id: value?.message_id ?? null
    });

    return formatToolResult(liveResult);
  } catch (error) {
    await writeAuditLog(DATA_DIR, {
      level: "error",
      event: "telegram_send_message.failed",
      request_id: requestId,
      message: error.message
    });
    return formatToolResult(
      {
        ...resultBase,
        ok: false,
        error: error.message
      },
      true
    );
  }
}

async function main() {
  const server = new Server(
    {
      name: "openclaw-telegram-starter",
      version: "0.1.0"
    },
    {
      capabilities: {
        tools: {}
      }
    }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [TOOL_DEFINITION]
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args = {} } = request.params;
    if (name !== TOOL_NAME) {
      return formatToolResult({ ok: false, error: `Unknown tool: ${name}` }, true);
    }
    return handleTelegramSendMessage(args);
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write(`[telegram-starter] ready workspaces=${WORKSPACES_FILE}\n`);
}

main().catch((error) => {
  process.stderr.write(`[telegram-starter] fatal ${error.message}\n`);
  process.exit(1);
});
