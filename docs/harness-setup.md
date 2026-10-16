# Connect your AI tool to PocketMCP

PocketMCP exposes the same phone tools over **Streamable HTTP**, **SSE**, and **WebSocket** on one port. Any MCP client can connect in one of two ways — pick whichever fits the tool, or use both:

| Connection style | How it works | Best for |
| ---------------- | ------------ | -------- |
| **Direct Streamable HTTP** | The AI tool talks to `http://<phone-ip>:8080/mcp` itself | OpenCode, Claude Code, Codex CLI, Cursor — anything that supports remote MCP servers with custom headers |
| **Stdio bridge** | The AI tool launches `mcp-bridge/bridge.mjs` (single file, **zero dependencies**) and the bridge forwards to the phone | Claude Desktop and every stdio-only client; also a universal fallback when a client misbehaves with remote servers |

The phone endpoint stays exactly the same in both cases — the bridge is just a local proxy to it.

## Prerequisites

- **Node.js 20+** on the machine running your AI tool. No `npm install` — the bridge and the setup helper have no dependencies.
- Server started in the PocketMCP app (Server tab), with an API key generated.
- Phone and computer on the same Wi-Fi, or both on the same Tailscale tailnet (see [Remote access](#remote-access-tailscale)).

## One command

```bash
node mcp-bridge/setup.mjs
```

The wizard:

1. Finds your phone — saved profile first, then mDNS LAN discovery, then asks you.
2. Verifies `/health`, the API key, the MCP handshake, and tool discovery.
3. Saves the connection to `~/.pocketmcp.json`, so the bridge later needs no flags at all.
4. Writes ready-made configs for the tools you pick (with timestamped backups), or prints copy-paste snippets.

Handy variants:

```bash
node mcp-bridge/setup.mjs doctor                       # end-to-end connectivity check
node mcp-bridge/setup.mjs doctor --call device_info    # ...and actually run a tool
node mcp-bridge/setup.mjs discover                     # list PocketMCP phones on the network
node mcp-bridge/setup.mjs print codex                  # print one tool's config, write nothing
node mcp-bridge/setup.mjs --yes                        # non-interactive: auto-detect tools, write configs
node mcp-bridge/setup.mjs --harness cursor --mode bridge
```

`--mode` selects the connection style:

| Mode | Effect |
| ---- | ------ |
| `http` (default) | Write the direct Streamable HTTP config where the tool supports it |
| `bridge` | Write the stdio-bridge config (the tool launches `node bridge.mjs`) |
| `both` | Write the direct HTTP config **and** print the bridge variant |

---

## OpenCode

Config: `~/.config/opencode/opencode.json` (or `.jsonc`), or `opencode.jsonc` in a project root for project scope.

**Direct HTTP** — note `oauth: false`; without it OpenCode may try OAuth against the phone (which has none) and fail:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "pocketmcp": {
        "type": "remote",
        "url": "http://192.0.2.50:8080/mcp",
        "oauth": false,
        "headers": { "X-API-Key": "YOUR_API_KEY" }
      }
    }
  }
}
```

**Stdio bridge** — same file, a local server entry instead:

```json
{
  "mcp": {
    "servers": {
      "pocketmcp": {
        "type": "local",
        "command": ["node", "/absolute/path/to/mcp-bridge/bridge.mjs"],
        "environment": {
          "POCKET_MCP_URL": "http://192.0.2.50:8080/mcp",
          "POCKET_MCP_API_KEY": "YOUR_API_KEY"
        }
      }
    }
  }
}
```

Verify: restart OpenCode and open the MCP pane — `pocketmcp` should be connected with the phone's tools.

> If your `opencode.jsonc` has comments, the setup helper can't merge it automatically — it prints the snippet and `opencode mcp add` command instead.

## Claude Code

The `claude` CLI manages MCP servers for you.

**Direct HTTP** (user scope = available in every project):

```bash
claude mcp add --transport http pocketmcp http://192.0.2.50:8080/mcp \
  --header "X-API-Key: YOUR_API_KEY" --scope user
```

**Stdio bridge alternative:**

```bash
claude mcp add --env POCKET_MCP_URL=http://192.0.2.50:8080/mcp \
  --env POCKET_MCP_API_KEY=YOUR_API_KEY --transport stdio pocketmcp -- \
  node /absolute/path/to/mcp-bridge/bridge.mjs
```

Important details:

- Header syntax is `--header "Name: value"` (colon separated).
- Claude Code **requires** `"type": "http"` in JSON config — a bare `url` is an error.
- Use `--scope project` to write `.mcp.json` in the current repo instead.

`.mcp.json` equivalent:

```json
{
  "mcpServers": {
    "pocketmcp": {
      "type": "http",
      "url": "http://192.0.2.50:8080/mcp",
      "headers": { "X-API-Key": "YOUR_API_KEY" }
    }
  }
}
```

Verify: `claude mcp list` shows `pocketmcp … ✔ Connected`. Inside a session, `/mcp` lists the live tools. Restart running sessions after adding.

## Codex CLI

Config: `~/.codex/config.toml` (or `.codex/config.toml` inside a trusted project).

**Direct HTTP:**

```toml
# PocketMCP — added by `node mcp-bridge/setup.mjs`. Safe to edit or remove.
[mcp_servers.pocketmcp]
url = "http://192.0.2.50:8080/mcp"
http_headers = { "X-API-Key" = "YOUR_API_KEY" }
```

**Stdio bridge alternative:**

```toml
# PocketMCP — added by `node mcp-bridge/setup.mjs`. Safe to edit or remove.
[mcp_servers.pocketmcp]
command = "node"
args = ["/absolute/path/to/mcp-bridge/bridge.mjs"]
env = { "POCKET_MCP_URL" = "http://192.0.2.50:8080/mcp", "POCKET_MCP_API_KEY" = "YOUR_API_KEY" }
```

Verify: restart Codex, then `codex mcp list` — `pocketmcp` should be listed.

## Cursor

Config: `~/.cursor/mcp.json` (global) or `.cursor/mcp.json` (project).

**Direct HTTP** — Cursor infers the transport from `url`; no `type` field needed:

```json
{
  "mcpServers": {
    "pocketmcp": {
      "url": "http://192.0.2.50:8080/mcp",
      "headers": { "X-API-Key": "YOUR_API_KEY" }
    }
  }
}
```

**Stdio bridge alternative:**

```json
{
  "mcpServers": {
    "pocketmcp": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-bridge/bridge.mjs"],
      "env": {
        "POCKET_MCP_URL": "http://192.0.2.50:8080/mcp",
        "POCKET_MCP_API_KEY": "YOUR_API_KEY"
      }
    }
  }
}
```

Verify: reload Cursor (it re-reads `mcp.json` on restart) and check Settings → MCP. The server's tools appear in Composer/Agent.

## Claude Desktop

Config file locations:

| OS | Path |
| -- | ---- |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Linux | `~/.config/Claude/claude_desktop_config.json` |

Claude Desktop's config file only launches **local (stdio)** servers, so this uses the bridge — Node must be installed:

```json
{
  "mcpServers": {
    "pocketmcp": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-bridge/bridge.mjs"],
      "env": {
        "POCKET_MCP_URL": "http://192.0.2.50:8080/mcp",
        "POCKET_MCP_API_KEY": "YOUR_API_KEY"
      }
    }
  }
}
```

Verify: fully quit and reopen Claude Desktop, then check the tools (hammer) icon. Tool approval appears on first use.

> Newer Claude Desktop builds also support remote **Connectors** (Settings → Connectors) if your plan exposes custom headers — but the bridge path above is the reliable, plan-independent option.

## Any other MCP client

```bash
node mcp-bridge/setup.mjs print generic
```

This prints both snippets (direct HTTP for HTTP-capable clients, stdio bridge for everything else). The generic shape is:

```json
{
  "mcpServers": {
    "pocketmcp": {
      "type": "http",
      "url": "http://192.0.2.50:8080/mcp",
      "headers": { "X-API-Key": "YOUR_API_KEY" }
    }
  }
}
```

Drop the `type` field for clients that don't use it, or switch to the bridge entry (`command` / `args` / `env`) for stdio-only clients.

---

## The raw endpoint (curl)

The phone serves MCP on `http://<phone-ip>:8080`:

```text
POST /mcp      JSON-RPC over Streamable HTTP (JSON or SSE response)
GET  /mcp      SSE stream
GET  /sse      legacy SSE endpoint
WS   /ws       WebSocket transport (pass ?key=<api-key> in the URL)
GET  /health   public, no API key required
```

```bash
# health (no auth)
curl http://192.0.2.50:8080/health

# list tools
curl -X POST http://192.0.2.50:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Auth works via `X-API-Key: <key>`, `Authorization: Bearer <key>`, or `?key=<key>` for handshakes that can't set headers. After 10 failed attempts from one host within a minute the server replies `429 Too Many Requests` — if you hit that while testing, wait a minute and re-check your key.

## Stdio bridge without any config

Once you have run the setup wizard (or any time you pass a URL on the command line), the bridge is a plain stdio MCP server:

```bash
node mcp-bridge/bridge.mjs                                   # profile → mDNS → 127.0.0.1:8080
node mcp-bridge/bridge.mjs --url http://192.0.2.50:8080/mcp --api-key YOUR_API_KEY --verbose
```

| Flag | Environment variable | Default | Purpose |
| ---- | -------------------- | ------- | ------- |
| `--url` | `POCKET_MCP_URL` | profile → mDNS → `http://127.0.0.1:8080/mcp` | Phone endpoint (`ws://` URLs are converted automatically) |
| `--api-key` | `POCKET_MCP_API_KEY` | profile | Key sent as `X-API-Key` |
| `--timeout-ms` | `POCKET_MCP_TIMEOUT_MS` | `30000` | Per-request timeout |
| `--tools-ttl-ms` | `POCKET_MCP_TOOLS_TTL_MS` | `30000` | Tool-list cache TTL (`0` disables) |
| `--no-tool-cache` | `POCKET_MCP_DISABLE_TOOL_CACHE=1` | off | Always fetch the live tool list |
| `--verbose` | `POCKET_MCP_VERBOSE=1` | off | Detailed logs on stderr |
| — | `POCKET_MCP_PROFILE` | `~/.pocketmcp.json` | Alternate profile path |

Test it by hand by piping one JSON-RPC message through it:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | node mcp-bridge/bridge.mjs --url http://192.0.2.50:8080/mcp
```

## Emulator (Android Studio AVD)

The emulator is only reachable through ADB port forwarding:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-emulator-mcp.ps1
```

Then use `http://127.0.0.1:8080/mcp` — which is also the bridge's built-in fallback, so a zero-flag bridge just works against an emulator.

## Remote access (Tailscale)

mDNS discovery only works on the local network. On Tailscale, pass the tailnet address explicitly everywhere (configs and CLI):

```bash
node mcp-bridge/setup.mjs --url http://100.x.y.z:8080/mcp
```

Tailscale traffic is already encrypted end-to-end, so plain `http://` over the tailnet is fine.

## Troubleshooting

| Symptom | Fix |
| ------- | --- |
| `PocketMCP rejected the API key` | Copy the current key from the app's Quick Connect card (a stale key is the most common cause). Keys are compared exactly — no trailing spaces. |
| After 10 bad attempts: `429` | Per-host throttle; wait a minute and retry with the right key. |
| `discover` finds nothing | Phone and computer on different networks, AP/client isolation, or a VPN. Pass `--url http://<phone-ip>:8080/mcp` and check the app's Server tab for the port. |
| Connection refused / timeouts | Server not started in the app; wrong port; phone screen-off battery optimization killed the service (check the notification). |
| Tools fail with accessibility errors | Enable the PocketMCP accessibility service (Settings → Accessibility), needed by UI automation tools. |
| GUI app can't find `node` (VS Code, Cursor, Claude Desktop) | GUI apps on macOS often don't inherit your shell PATH. Replace `"node"` with the absolute path from `which node` (or `where node` on Windows). |
| Windows path with spaces | JSON configs: use forward slashes (`C:/Program Files/...`) or escaped backslashes. The setup helper quotes paths automatically in generated CLI commands. |
| Client shows the server but no tools | Run `node mcp-bridge/setup.mjs doctor` — it pinpoints health vs. auth vs. tool listing. |

## Security notes

- The API key travels over plain HTTP on your LAN — fine on trusted networks, and still recommended everywhere (the phone's server refuses unauthenticated endpoints except `/health` and `/`).
- The bridge stores the connection (URL + key) in `~/.pocketmcp.json`. Delete it any time; the bridge falls back to discovery.
- On untrusted networks (cafés, hotels), use Tailscale instead of exposing port 8080.
