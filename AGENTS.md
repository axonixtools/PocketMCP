# PocketMCP — Agent Guide

> **Purpose**: This document helps AI coding agents understand the PocketMCP project quickly and make safe, correct changes.

## Project Overview

PocketMCP is an **Android application** that turns a phone into an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server. Desktop AI agents and scripts can call the phone over LAN (or Tailscale) to execute tools like sending messages, checking notifications, running shell commands, controlling the screen, and querying device sensors.

- **App package**: `com.pocketmcp`
- **Language**: Kotlin (Android)
- **License**: MIT
- **Server version** (hard-coded in `McpServer.kt`): `0.2.6`
- **Protocol version**: `2024-11-05`

### Repository Layout

```
phone-mcp/
├── app/                          # Android app module (Gradle subproject)
│   ├── src/main/java/com/pocketmcp/
│   │   ├── MainActivity.kt       # Single activity UI (XML + View-based, not Compose screens)
│   │   ├── accessibility/        # PhoneAccessibilityService.kt
│   │   ├── server/               # Ktor MCP HTTP/WebSocket server + JSON-RPC types
│   │   ├── service/              # McpServerService (Android foreground service)
│   │   └── tools/                # ~30+ MCP tool implementations
│   ├── src/main/res/             # XML layouts, drawables, colors, strings
│   ├── src/main/assets/          # app_action_presets.json (human-intelligence presets)
│   ├── src/main/AndroidManifest.xml
│   └── build.gradle.kts          # App-level build config
├── mcp-bridge/                   # Node.js stdio-to-HTTP MCP bridge
│   ├── bridge.mjs                # Main entry point
│   └── package.json
├── scripts/
│   └── setup-emulator-mcp.ps1    # PowerShell ADB port-forward helper for emulators
├── pocket_mcp_client.py          # Python client library + CLI
├── mcp-config.example.json       # Example MCP client config (Claude Desktop, Cursor, etc.)
├── build.gradle.kts              # Root build file
├── settings.gradle.kts           # Project settings
├── gradle.properties             # Gradle JVM options, AndroidX flags
└── README.md                     # User-facing documentation
```

### Companion Projects in Repo

- **`mcp-bridge/`** — Node.js ES module (`type: "module"`). Bridges stdio MCP transport to PocketMCP's HTTP endpoint. Uses `@modelcontextprotocol/sdk`, `ws`, and `bonjour-service` for mDNS discovery.
- **`pocket_mcp_client.py`** — Python 3 client with typed exceptions, retry logic, WebSocket support (optional `websockets`), and mDNS discovery (optional `zeroconf`). Can be imported or run as CLI.
- **`openclaw-tools/telegram-starter/`** — Small Node.js Telegram bot starter (separate from core app; has its own `package.json` and tests).

---

## Technology Stack

### Android App

| Layer | Technology |
|-------|-----------|
| Build system | Gradle 9.0-milestone-1 (wrapper), Kotlin DSL |
| Android Gradle Plugin | `8.13.2` |
| Kotlin | `1.9.20` |
| Compile SDK | `34` |
| Min SDK | `26` (Android 8.0) |
| Target SDK | `34` |
| JVM target | `1.8` |
| UI framework | XML layouts + Jetpack Compose (BOM `2024.02.00`, compiler `1.5.4`) — the app is currently view-based with Compose enabled for future migration |
| HTTP server | Ktor `2.3.7` (Netty engine, CORS, WebSockets, content negotiation) |
| Serialization | kotlinx-serialization-json `1.6.0` |
| Coroutines | kotlinx-coroutines-android `1.7.3` |
| Architecture | Single `Activity` + `Service`; no MVVM or architecture components beyond ViewModel-Compose integration |

### Node.js Bridge

- Runtime: Node.js (ES modules)
- Key deps: `@modelcontextprotocol/sdk ^1.12.0`, `ws ^8.19.0`, `bonjour-service ^1.3.0`

### Python Client

- Standard library + `requests`, `urllib3`
- Optional extras: `websockets` (for WS transport), `zeroconf` (for mDNS discovery)

---

## Build and Run Commands

### Android App

```bash
# Debug build
./gradlew assembleDebug

# Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean
./gradlew clean
```

The pre-release APK in the repo root (`PocketMCP-debug.apk`) is a manually built artifact.

### Node.js Bridge

```bash
cd mcp-bridge
npm install
node bridge.mjs --url http://PHONE_IP:8080/mcp --api-key YOUR_KEY --verbose
```

### Python Client

```bash
# As CLI
python pocket_mcp_client.py 192.0.2.100:8080 YOUR_API_KEY
python pocket_mcp_client.py 192.0.2.100:8080 YOUR_API_KEY --list-tools
python pocket_mcp_client.py 192.0.2.100:8080 YOUR_API_KEY --call device_info

# As library
python -c "from pocket_mcp_client import PocketMCPClient; c = PocketMCPClient('http://192.0.2.100:8080', api_key='key'); print(c.device_info())"
```

---

## Code Organization

### Package Structure (`app/src/main/java/com/pocketmcp`)

- **`MainActivity.kt`** — View-binding based dashboard with 4 pages (Server, Logs, Tools, Settings) driven by `BottomNavigationView` and `ViewAnimator`. Handles server start/stop via intents to `McpServerService`, API key generation, permission state checks, and connection info rendering.
- **`accessibility/`** — `PhoneAccessibilityService` extends Android `AccessibilityService`. Provides global actions (home, back, recents, lock), screen gestures (swipe, tap), screen snapshot capture (accessibility tree traversal), and node-based UI interaction.
- **`server/`** —
  - `McpServer.kt` — Ktor embedded Netty server. Exposes endpoints: `GET /`, `GET /health`, `POST /mcp`, `GET /mcp` (SSE), `GET /sse`, `/ws` (WebSocket), `GET /tools`. Handles JSON-RPC `initialize`, `tools/list`, `tools/call`.
  - `McpJsonRpc.kt` — Serializable data classes for JSON-RPC and MCP types.
  - `McpToolHandler.kt` — Interface that every tool implements (`name`, `description`, `inputSchema`, `execute`).
- **`service/`** — `McpServerService` is an Android `Service` that runs the Ktor server in a foreground notification. Supports actions: `START`, `STOP`, `QUERY_STATUS`. Broadcasts status back to `MainActivity`. Also registers mDNS (`_mcp._tcp`) via `NsdManager` when the server starts.
- **`tools/`** — One Kotlin file per MCP tool. Tools are registered into `McpToolRegistry` inside `McpServer.init`. There are ~35 tools covering:
  - System: `device_info`, `shell`, `launch_app`, `list_apps`, `global_action`, `flashlight`, `volume_control`, `phone_alert`, `screenshot`, `read_file`
  - Communication: `send_message`, `make_call`, `search_contacts`, `whatsapp_automation`, `send_whatsapp_business_message`, `social_media`
  - Automation: `scroll_screen`, `search_screen`, `tap`, `app_actions`, `workflow_automation`
  - Notifications: `notifications`, `smart_notification_manager`, `notification_diagnostic`
  - Media: `voice_record`, `transcribe_audio`, `transcribe_file`, `transcribe_whatsapp_audio`
  - Network/Utility: `get_location`, `http_request`, `human_command`, `intelligent_action`
- **`agent/`** — Experimental orchestration layer for humanoid automation:
  - `model/` intent/task/step/result data models
  - `nlu/` natural-language parsing and entity resolution
  - `planner/` task graph building and risk policy checks
  - `execution/` stateful step execution, verification, and retries
  - `skills/` app-specific planning packs (for example YouTube, X)
  - `perception/` popup/blocker detection helpers
  - `memory/` session context and execution trace persistence
  - `dialog/` clarification prompts when intent is ambiguous

### Tool Conventions

- Every tool implements `McpToolHandler`.
- Schema is declared with `kotlinx.serialization.json.buildJsonObject` matching JSON Schema style (`type`, `properties`, `required`, `enum`, `description`).
- `execute` receives `JsonObject? args` and `Context context`.
- Helper utilities live in `ToolResultUtils.kt`: `resultText()`, `resultJson()`, `resultError()`, and typed arg extractors (`argString`, `argInt`, `argBoolean`, `argJsonObject`, etc.).
- Screen-state safety checks (e.g., verifying the correct app is in foreground before/after intents) are common in messaging and automation tools. These rely on `PhoneAccessibilityService.captureScreenSnapshot()` and helper functions in `ScreenStateGuards.kt`.

### Assets

- `app/src/main/assets/app_action_presets.json` — Human-intelligence command patterns and smart workflow presets consumed by `HumanCommandTool` and related automation tools.

### Resources

- `activity_main.xml` is the single root layout with 4 child page layouts.
- Strings are fully externalized in `res/values/strings.xml`.
- Colors and drawables use a custom dark theme (`Theme.PocketMCP`).

---

## MCP Protocol Details

### Transport

The server supports three transports simultaneously on the same port:
1. **HTTP POST `/mcp`** — Standard StreamableHTTP request/response.
2. **SSE `/mcp` and `/sse`** — Server-sent events for streaming.
3. **WebSocket `/ws`** — Bidirectional JSON-RPC over WebSocket.

### Authentication

If an API key is configured, every non-public endpoint requires either:
- Header `X-API-Key: <key>`
- Header `Authorization: Bearer <key>`

Public endpoints (no key required): `GET /`, `GET /health`, SSE/WebSocket handshake paths.

### Supported JSON-RPC Methods

- `initialize` — Returns protocol version, server info, capabilities.
- `notifications/initialized` — Acknowledged but no-op.
- `tools/list` — Returns all registered tools with schemas.
- `tools/call` — Executes a tool by name with arguments.

### Error Codes

Defined in `McpJsonRpc.kt`:
- `-32700` Parse error
- `-32600` Invalid request
- `-32601` Method not found
- `-32602` Invalid params
- `-32603` Internal error
- `-32001` Tool not found

---

## Security Model

- **API key auth** is strongly recommended and generated by the app UI.
- **Shell tool** blocks dangerous fragments (`rm -rf`, `mkfs`, `dd`, `reboot`, `poweroff`, fork bombs, `setenforce 0`, `pm uninstall`). Max command length 500 chars, timeout 1–30s, output truncated to 12,000 chars.
- **File reads** are restricted to app storage and external storage roots.
- **HTTP proxy tool** (`http_request`) blocks loopback URLs.
- **Safety filter** mode cycles between `Strict`, `Balanced`, and `Off` in the UI; some tools use this to gate behavior.
- **Screen-state guards** in messaging/automation tools verify the target app actually reached the foreground before and after intents, preventing wrong-recipient actions.

### Required Android Permissions / Services

Many tools need special permissions or services enabled by the user in system settings:

| Capability | Requirement |
|-----------|-------------|
| Shell, file read, device info | Standard runtime permissions / none |
| Location | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` |
| Contacts | `READ_CONTACTS` |
| Calling | `CALL_PHONE` |
| SMS | `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS` |
| Notifications | Notification Listener Service (`BIND_NOTIFICATION_LISTENER_SERVICE`) |
| UI automation | Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`) |
| Microphone / voice | `RECORD_AUDIO`, foreground service type `microphone` |

---

## Testing Strategy

**Current state**: The project has **no automated tests**. There are no `test/` or `androidTest/` source directories.

If you add tests:
- Use `src/test/java/` for JVM unit tests (mock Ktor/tool logic with standard JUnit 4).
- Use `src/androidTest/java/` for instrumented tests (requires an emulator or device).
- The `build.gradle.kts` already declares `testImplementation("junit:junit:4.13.2")`, `androidx.test.ext:junit`, and `espresso-core`.

---

## Deployment / Distribution

- **APK builds** are produced manually via `./gradlew assembleDebug`.
- The repo includes a checked-in `PocketMCP-debug.apk` at the root for pre-release distribution.
- GitHub Releases are used for beta builds (see `README.md`).
- There is no CI/CD pipeline, Play Store integration, or automated signing configuration in the repo.

---

## Development Guidelines

### Adding a New Tool

1. Create a new Kotlin file in `app/src/main/java/com/pocketmcp/tools/`.
2. Implement `McpToolHandler`.
3. Use `buildJsonObject` to declare `inputSchema`.
4. Use `resultJson()`, `resultText()`, or `resultError()` from `ToolResultUtils.kt` for return values.
5. Register the tool in `McpServer.init { registry.register(YourTool()) }`.
6. Add a user-facing description to `README.md` if the tool is user-facing.

### Extending `intelligent_action`

1. Add/adjust parsing rules in `agent/nlu/CommandParser.kt` and `IntentCatalog.kt`.
2. Add app-specific plans in `agent/skills/` and register in `AppSkillRegistry.kt`.
3. Keep risky actions gated through `agent/planner/PolicyGate.kt`.
4. Reuse existing low-level tools from `tools/` in `agent/execution/TaskExecution.kt`.
5. Preserve trace logging (`agent/memory/ExecutionTraceStore.kt`) for reliability analysis.

### Adding a New Endpoint

If you need a raw HTTP endpoint (not MCP JSON-RPC), add it inside the Ktor `routing { }` block in `McpServer.kt`. Public endpoints should bypass the API key intercept (see `/health` as an example).

### UI Changes

- The UI is **not** Compose-first yet. `MainActivity` uses XML layouts via `findViewById`. Compose is enabled in the build but is not the primary UI layer.
- Strings belong in `res/values/strings.xml`.
- Colors belong in `res/values/colors.xml`.
- Drawables belong in `res/drawable/`.

### Logging

- Use Android `Log` with tag `PocketMCP` (or sub-tags like `PocketMCP.Accessibility`).
- Avoid logging API keys or user contact data.

### Kotlin Style

- Follow existing Kotlin conventions in the codebase: explicit types on public APIs, camelCase, 4-space indentation.
- Prefer `buildJsonObject { }` and `buildJsonArray { }` for JSON construction.
- Prefer `runCatching { }.onSuccess { }.onFailure { }` for error handling in coroutines.
- Use `suspendCancellableCoroutine` for bridging Android callback APIs to coroutines (see accessibility gestures).

---

## Common Pitfalls

1. **Emulator networking**: From the host, do not use `10.0.2.15`. Use `scripts/setup-emulator-mcp.ps1` to forward ADB ports and talk to `127.0.0.1:8080`.
2. **Accessibility service must be enabled manually** in Android Settings for UI automation tools to work. The app can only open the settings screen, not toggle it programmatically.
3. **Foreground service types**: `McpServerService` declares `dataSync|microphone`. If you add tools requiring other foreground service types (e.g. `camera`, `location`), update the manifest and `AndroidManifest.xml` accordingly.
4. **mDNS registration** happens only when the server successfully starts. It is torn down in `onDestroy()`.
5. **Tool name collisions** will throw `IllegalArgumentException` from `McpToolRegistry.register()`.

---

## Useful References

- `README.md` — Full user guide, built-in tool table, quick start, security model.
- `mcp-config.example.json` — Template for Claude Desktop / Cursor MCP client configuration.
- `app/src/main/java/com/pocketmcp/tools/ShellCommandTool.kt` — Good example of timeout, safety filters, and coroutine-based process I/O.
- `app/src/main/java/com/pocketmcp/tools/DeviceInfoTool.kt` — Simple tool with no args, returns JSON.
- `app/src/main/java/com/pocketmcp/tools/MessagingTool.kt` — Complex tool with deep linking, screen-state guards, and app alias normalization.
