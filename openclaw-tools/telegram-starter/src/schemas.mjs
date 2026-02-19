const PARSE_MODES = new Set(["None", "MarkdownV2", "HTML"]);

export const TELEGRAM_SEND_MESSAGE_INPUT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    workspace_id: {
      type: "string",
      minLength: 1,
      description: "Workspace identifier used for auth and policy lookup"
    },
    workspace_token: {
      type: "string",
      minLength: 1,
      description: "Workspace secret token (compared against local SHA-256 hash)"
    },
    chat_id: {
      type: "string",
      minLength: 1,
      description: "Telegram chat ID or channel ID"
    },
    text: {
      type: "string",
      minLength: 1,
      maxLength: 4096,
      description: "Message body to send"
    },
    parse_mode: {
      type: "string",
      enum: ["None", "MarkdownV2", "HTML"],
      default: "None",
      description: "Telegram parse mode"
    },
    dry_run: {
      type: "boolean",
      default: true,
      description: "Local-first default. true queues locally instead of hitting Telegram API"
    },
    metadata: {
      type: "object",
      additionalProperties: { type: "string" },
      description: "Optional key/value metadata persisted to local audit logs"
    }
  },
  required: ["workspace_id", "workspace_token", "chat_id", "text"]
};

export const TELEGRAM_SEND_MESSAGE_OUTPUT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    ok: { type: "boolean" },
    request_id: { type: "string" },
    workspace_id: { type: "string" },
    chat_id: { type: "string" },
    dry_run: { type: "boolean" },
    queued: { type: "boolean" },
    attempts: { type: "integer", minimum: 0 },
    message_id: { type: "integer" },
    error: { type: "string" }
  },
  required: ["ok", "request_id", "workspace_id", "chat_id", "dry_run", "queued", "attempts"]
};

function isNonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

export function validateTelegramSendMessageInput(input) {
  const errors = [];
  const source = input && typeof input === "object" ? input : {};

  if (!isNonEmptyString(source.workspace_id)) {
    errors.push("workspace_id must be a non-empty string");
  }
  if (!isNonEmptyString(source.workspace_token)) {
    errors.push("workspace_token must be a non-empty string");
  }
  if (!isNonEmptyString(source.chat_id)) {
    errors.push("chat_id must be a non-empty string");
  }
  if (!isNonEmptyString(source.text)) {
    errors.push("text must be a non-empty string");
  } else if (source.text.length > 4096) {
    errors.push("text must be <= 4096 characters");
  }

  const parseMode = source.parse_mode ?? "None";
  if (!PARSE_MODES.has(parseMode)) {
    errors.push("parse_mode must be one of: None, MarkdownV2, HTML");
  }
  if (source.dry_run !== undefined && typeof source.dry_run !== "boolean") {
    errors.push("dry_run must be a boolean");
  }
  if (source.metadata !== undefined) {
    if (!source.metadata || typeof source.metadata !== "object" || Array.isArray(source.metadata)) {
      errors.push("metadata must be an object if provided");
    } else {
      for (const [key, value] of Object.entries(source.metadata)) {
        if (typeof value !== "string") {
          errors.push(`metadata.${key} must be a string`);
        }
      }
    }
  }

  const known = new Set(["workspace_id", "workspace_token", "chat_id", "text", "parse_mode", "dry_run", "metadata"]);
  for (const key of Object.keys(source)) {
    if (!known.has(key)) {
      errors.push(`Unknown field: ${key}`);
    }
  }

  if (errors.length > 0) {
    return { ok: false, errors };
  }

  return {
    ok: true,
    value: {
      workspace_id: source.workspace_id.trim(),
      workspace_token: source.workspace_token,
      chat_id: source.chat_id.trim(),
      text: source.text,
      parse_mode: parseMode,
      dry_run: source.dry_run ?? true,
      metadata: source.metadata ?? {}
    }
  };
}
