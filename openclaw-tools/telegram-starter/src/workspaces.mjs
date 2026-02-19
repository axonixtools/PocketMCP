import { createHash, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";

function asObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  return value;
}

export function hashToken(token) {
  return createHash("sha256").update(String(token), "utf8").digest("hex");
}

function safeCompareHex(expectedHex, actualHex) {
  try {
    const left = Buffer.from(String(expectedHex), "hex");
    const right = Buffer.from(String(actualHex), "hex");
    if (left.length === 0 || right.length === 0 || left.length !== right.length) {
      return false;
    }
    return timingSafeEqual(left, right);
  } catch {
    return false;
  }
}

export async function loadWorkspaceRegistry(filePath) {
  const raw = await readFile(filePath, "utf8");
  const parsed = JSON.parse(raw);
  const workspaces = asObject(parsed.workspaces);
  return { workspaces };
}

export function authenticateWorkspace({ registry, workspaceId, workspaceToken }) {
  const workspace = asObject(registry.workspaces?.[workspaceId]);
  if (!workspace.token_sha256) {
    throw new Error(`Workspace "${workspaceId}" not found or missing token_sha256`);
  }
  const incomingHash = hashToken(workspaceToken);
  if (!safeCompareHex(workspace.token_sha256, incomingHash)) {
    throw new Error("Workspace token is invalid");
  }
  return workspace;
}

export function checkTelegramPermissions({ workspace, chatId, text }) {
  const permissions = asObject(workspace.permissions?.telegram_send_message);
  const allowedChatIds = Array.isArray(permissions.allowed_chat_ids) ? permissions.allowed_chat_ids.map(String) : [];
  if (allowedChatIds.length > 0 && !allowedChatIds.includes(String(chatId))) {
    throw new Error(`chat_id "${chatId}" is not allowed for this workspace`);
  }
  const maxTextLength = Number.isInteger(permissions.max_text_length) ? permissions.max_text_length : 4096;
  if (text.length > maxTextLength) {
    throw new Error(`text exceeds workspace max_text_length (${maxTextLength})`);
  }
}

export function resolveTelegramBotToken({ workspace, workspaceId }) {
  const workspaceTelegram = asObject(workspace.telegram);
  const configuredEnv = workspaceTelegram.bot_token_env;
  if (configuredEnv && process.env[configuredEnv]) {
    return process.env[configuredEnv];
  }

  const workspaceKey = `OPENCLAW_TELEGRAM_BOT_TOKEN_${String(workspaceId).toUpperCase().replace(/[^A-Z0-9]/g, "_")}`;
  if (process.env[workspaceKey]) {
    return process.env[workspaceKey];
  }
  if (process.env.OPENCLAW_TELEGRAM_BOT_TOKEN) {
    return process.env.OPENCLAW_TELEGRAM_BOT_TOKEN;
  }
  return "";
}
