# Telegram Tool Starter (OpenClaw-style)

Reusable starter template for building local-first tools with:

- strict JSON schema input/output
- workspace-scoped auth + permissions
- local queue/audit logging by default
- retry-wrapped external API calls

## What this template exposes

Tool name: `telegram_send_message`

Input schema highlights:

- `workspace_id` (required)
- `workspace_token` (required, validated against local SHA-256 hash)
- `chat_id` (required)
- `text` (required, max 4096 chars)
- `dry_run` (optional, default `true`)
- `parse_mode` (optional: `None | MarkdownV2 | HTML`)

Output schema highlights:

- `ok`, `error`
- `request_id`
- `workspace_id`, `chat_id`
- `dry_run`, `queued`
- `attempts`, `message_id`

## Setup

1. Install dependencies

```bash
cd openclaw-tools/telegram-starter
npm install
```

2. Create workspace config

```bash
cp workspaces.local.example.json workspaces.local.json
```

3. Generate a token hash for `workspaces.local.json`

```bash
node -e "const c=require('node:crypto'); console.log(c.createHash('sha256').update('YOUR_WORKSPACE_TOKEN').digest('hex'))"
```

4. Set Telegram bot token in environment

```bash
export OPENCLAW_TELEGRAM_BOT_TOKEN=123456:your_bot_token
```

5. Start MCP server (stdio)

```bash
npm start
```

## Local-first behavior

- Default `dry_run: true` means requests are written to `data/outbox.ndjson`.
- Audit events are written to `data/audit.ndjson`.
- Set `dry_run: false` and `allow_network: true` in workspace policy to send live.

## Workspace auth + permissions

`workspaces.local.json` supports per-workspace policy:

- `token_sha256`: required for auth
- `allow_network`: gate outbound Telegram calls
- `permissions.telegram_send_message.allowed_chat_ids`: restrict destinations
- `permissions.telegram_send_message.max_text_length`: stricter length cap

## Retry behavior

Live Telegram requests retry with exponential backoff for retryable failures:

- HTTP `408`, `429`, `5xx`
- network transport errors

## Test

```bash
npm test
```

## Extend to Discord/Zoom

Reuse these same modules:

- `src/schemas.mjs` for strict I/O contracts
- `src/workspaces.mjs` for auth and policy checks
- `src/localStore.mjs` for queue/audit logging
- `src/retry.mjs` for external API resilience
