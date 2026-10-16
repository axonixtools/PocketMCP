# PocketMCP — Agent Guide

> **Purpose**: This document helps AI coding agents understand the PocketMCP project quickly and make safe, correct changes.

## Project Overview

PocketMCP is an **Android application** that turns a phone into an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server. Desktop AI agents and scripts can call the phone over LAN (or Tailscale) to execute tools like sending messages, checking notifications, running shell commands, controlling the screen, and querying device sensors.

- **App package**: `com.pocketmcp`
- **Language**: Kotlin (Android)
- **License**: MIT
- **Server version**: derived from the Gradle `versionName` (`1.0.0-beta.2`) via `BuildConfig.VERSION_NAME` in `McpServer.kt`
- **Protocol version**: `2024-11-05`

### Repository Layout

```
phone-mcp/
├── app/                          # Android app module (Gradle subproject)
│   ├── src/main/java/com/pocketmcp/
│   │   ├── MainActivity.kt       # Single-activity Compose host + app state
│   │   ├── accessibility/        # PhoneAccessibilityService.kt
│   │   ├── agent/                # Experimental orchestration (NLU -> plan -> execute)
│   │   ├── security/             # SecurePrefs (Keystore-encrypted storage)
│   │   ├── server/               # Ktor MCP HTTP/WebSocket server + JSON-RPC types
│   │   ├── service/              # McpServerService (Android foreground service)
│   │   ├── tools/                # ~35 MCP tool implementations
│   │   ├── ui/                   # Compose theme, components, screens
│   │   └── util/                 # ApiKeyGenerator, McpConfigBuilder
│   ├── src/main/res/             # strings, colors, drawables
│   ├── src/main/assets/          # app_action_presets.json (human-intelligence presets)
│   ├── src/main/AndroidManifest.xml
│   ├── src/test/java/            # JVM unit tests (JUnit 4)
│   └── build.gradle.kts          # App-level build config
├── .github/workflows/            # CI: build + unit tests on push/PR
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

- **`mcp-bridge/`** — Node.js ES module (`type: "module"`), **zero runtime dependencies** (Node 20+ stdlib only). `bridge.mjs` proxies stdio MCP transport to the phone's Streamable HTTP endpoint (stdlib mDNS discovery, connection profile at `~/.pocketmcp.json`, tool cache, session repair). `setup.mjs` is the one-command wizard/doctor/config writer for OpenCode, Claude Code, Codex, Cursor, and Claude Desktop. `test/` holds the Node test suite (mock phone + end-to-end), run with `node --test`.
- **`pocket_mcp_client.py`** — Python 3 client with typed exceptions, retry logic, WebSocket support (optional `websockets`), and mDNS discovery (optional `zeroconf`). Can be imported or run as CLI.
- **`openclaw-tools/telegram-starter/`** — Small Node.js Telegram bot starter (separate from core app; has its own `package.json` and tests).

---

## Technology Stack

### Android App

| Layer | Technology |
|-------|-----------|
| Build system | Gradle 9.4.1 (wrapper), Kotlin DSL |
| Android Gradle Plugin | `9.2.0` |
| Kotlin | `2.2.10` (Compose compiler plugin `2.2.10`) |
| Compile SDK | `34` |
| Min SDK | `26` (Android 8.0) |
| Target SDK | `34` |
| JVM target | `1.8` |
| Build JDK | 17+ required; CI and local dev use 21 |
| UI framework | Jetpack Compose (Material 3, BOM `2024.02.00`) — Compose is the primary UI |
| HTTP server | Ktor `2.3.7` (Netty engine, CORS, WebSockets, content negotiation) |
| Serialization | kotlinx-serialization-json `1.6.0` |
| Coroutines | kotlinx-coroutines-android `1.7.3` |
| Architecture | Single `Activity` + `Service`; no MVVM or architecture components beyond ViewModel-Compose integration |

### Node.js Bridge

- Runtime: Node.js (ES modules)
- Runtime deps: none (Node 20+ stdlib only); mDNS discovery implemented with `dgram`

### Python Client

- Standard library + `requests`, `urllib3`
- Optional extras: `websockets` (for WS transport), `zeroconf` (for mDNS discovery)

---

## Build and Run Commands

### Android App

```bash
# Debug build
./gradlew assembleDebug

# Unit tests (JVM, no device needed)
./gradlew testDebugUnitTest

# Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean
./gradlew clean
```

A pre-release APK may be present at the repo root (`PocketMCP-debug.apk`) as a locally built artifact; it is untracked (`.gitignore` excludes `*.apk`).

### Node.js Bridge

```bash
cd mcp-bridge
node bridge.mjs --url http://PHONE_IP:8080/mcp --api-key YOUR_KEY --verbose
node setup.mjs   # guided setup: OpenCode, Claude Code, Codex, Cursor, Claude Desktop
node --test      # bridge + setup test suite (Node 20+)
```

No `npm install` — the bridge and setup helper have zero dependencies.

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

- **`MainActivity.kt`** — Single-activity Compose host for the 4-tab dashboard (Server, Logs, Tools, Settings). Handles server start/stop via intents to `McpServerService`, API-key generation via `util/ApiKeyGenerator`, permission state checks, and connection info rendering. The API key is persisted through `security/SecurePrefs` (Android Keystore-encrypted, with one-time migration from the legacy plaintext key).
- **`accessibility/`** — `PhoneAccessibilityService` extends Android `AccessibilityService`. Provides global actions (home, back, recents, lock), screen gestures (swipe, tap), screen snapshot capture (accessibility tree traversal), and node-based UI interaction.
- **`server/`** —
  - `McpServer.kt` — Ktor embedded Netty server. Exposes endpoints: `GET /`, `GET /health`, `POST /mcp`, `GET /mcp` (SSE), `GET /sse`, `/ws` (WebSocket), `GET /tools`. Handles JSON-RPC `initialize`, `tools/list`, `tools/call`. All endpoints except `/` and `/health` require the API key (header, bearer, or `?key=` query parameter).
  - `McpJsonRpc.kt` — Serializable data classes for JSON-RPC and MCP types, plus the shared `encodeJsonRpcResponse` wire encoder used by all transports.
  - `ApiKeyGuard.kt` — API-key validation plus per-host throttling of failed attempts (HTTP 429 after 10 failures/minute).
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

- The UI is built with Jetpack Compose (`ui/screens`, `ui/components`, `ui/theme`); `res/` holds strings, colors, drawables, and manifest resources.
- Strings are fully externalized in `res/values/strings.xml`.
- Compose theme tokens live in `ui/theme/`; legacy resource colors in `res/values/colors.xml`.

---

## MCP Protocol Details

### Transport

The server supports three transports simultaneously on the same port:
1. **HTTP POST `/mcp`** — Standard StreamableHTTP request/response.
2. **SSE `/mcp` and `/sse`** — Server-sent events for streaming.
3. **WebSocket `/ws`** — Bidirectional JSON-RPC over WebSocket.

### Authentication

If an API key is configured, every non-public endpoint requires one of:
- Header `X-API-Key: <key>`
- Header `Authorization: Bearer <key>`
- Query parameter `?key=<key>` or `?api_key=<key>` (for WebSocket/SSE handshakes that cannot set custom headers)

Public endpoints (no key required): `GET /` and `GET /health` only. WebSocket and SSE handshakes are authenticated like every other endpoint. After 10 failed attempts from one host within a minute, the server responds `429 Too Many Requests` until the window expires (`server/ApiKeyGuard.kt`).

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

- **API key auth** is strongly recommended, generated by the app UI, and enforced on every endpoint except `GET /` and `GET /health` — including `/ws` and SSE handshakes. Clients pass it via `X-API-Key`, `Authorization: Bearer`, or `?key=` (for handshakes that cannot set custom headers).
- **Encrypted key storage**: `security/SecurePrefs` uses `EncryptedSharedPreferences` backed by the Android Keystore; a legacy plaintext key (if present) is migrated automatically on first launch.
- **Brute-force throttling**: failed auth attempts are counted per client host (`server/ApiKeyGuard.kt`); exceeding 10 failures in 60 seconds returns HTTP 429.
- **Shell tool** blocks dangerous fragments (`rm -rf`/`rm -fr`/`rm -r -f`/`rm -f -r`, `mkfs`, `dd if=`, `reboot`, `poweroff`, `halt`, `fastboot`, fork bombs, `setenforce 0`, `pm uninstall`). Max command length 500 chars, timeout 1–30s, output truncated to 12,000 chars. This is a best-effort speed bump, not a sandbox.
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

**Current state**: JVM unit tests live in `app/src/test/java/` and run with `./gradlew testDebugUnitTest` (no device required).

Covered today:
- `server/ApiKeyGuardTest` — auth decisions + per-host throttling windows
- `server/JsonRpcCodecTest` — JSON-RPC wire encoding for all transports
- `tools/ShellCommandSafetyTest` — shell safety filter (blocked + allowed cases)
- `tools/ToolResultUtilsTest`, `tools/McpToolRegistryTest` — tool plumbing
- `agent/nlu/CommandParserTest`, `agent/planner/TaskGraphBuilderTest` — NLU parsing + task planning
- `util/ApiKeyGeneratorTest`, `util/McpConfigBuilderTest` — key/config generation

CI (`.github/workflows/ci.yml`) runs `assembleDebug` + `testDebugUnitTest`, then the bridge checks and `node --test` suite on Node 22, on push/PR.

The `mcp-bridge/` suite runs via `node --test` from `mcp-bridge/` (Node 20+): end-to-end bridge tests against a mock phone (`test/helpers/mock-phone.mjs`), transport-variant tests (SSE, sessions, retries, auth), mDNS codec tests with synthetic packets, and pure planner tests for `setup.mjs` config generation.

Conventions:
- Keep unit tests pure JVM — avoid Android framework calls (`Looper`, real `Context`, etc.). `testOptions { unitTests.isReturnDefaultValues = true }` is enabled as a safety net.
- Use `src/androidTest/java/` for instrumented tests (requires an emulator or device).
- The `build.gradle.kts` declares `testImplementation("junit:junit:4.13.2")`, `androidx.test.ext:junit`, and `espresso-core`.

---

## Deployment / Distribution

- **APK builds** are produced manually via `./gradlew assembleDebug`.
- Release artifacts are distributed via GitHub Releases; a locally built `PocketMCP-debug.apk` may exist at the repo root but is not tracked by git.
- GitHub Releases are used for beta builds (see `README.md`).
- CI (`.github/workflows/ci.yml`) builds the debug APK and runs unit tests on every push/PR. There is no Play Store integration or automated signing configuration yet.

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

- The UI is Compose-first: screens in `ui/screens/`, reusable components in `ui/components/`, theme tokens in `ui/theme/`. `MainActivity` hosts the navigation + shared app state.
- Strings belong in `res/values/strings.xml`.
- Colors belong in `ui/theme/` (Compose tokens); legacy resource colors in `res/values/colors.xml`.
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
6. **Build environment**: Gradle requires **JDK 17+** (CI and local dev use JDK 21) and an Android SDK with platform 34 + build-tools installed. Configure `sdk.dir` in `local.properties`.
7. **WebSocket/SSE clients**: when an API key is set, the handshake must include the key (`X-API-Key` header, or `?key=` in the URL for clients that cannot set headers). The stdio bridge and Python client already send it.

---

## Useful References

- `README.md` — Full user guide, built-in tool table, quick start, security model.
- `mcp-config.example.json` — Template for Claude Desktop / Cursor MCP client configuration.
- `app/src/main/java/com/pocketmcp/tools/ShellCommandTool.kt` — Good example of timeout, safety filters, and coroutine-based process I/O.
- `app/src/main/java/com/pocketmcp/tools/DeviceInfoTool.kt` — Simple tool with no args, returns JSON.
- `app/src/main/java/com/pocketmcp/tools/MessagingTool.kt` — Complex tool with deep linking, screen-state guards, and app alias normalization.
