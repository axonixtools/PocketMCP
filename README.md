# PocketMCP

Turn your Android phone into an MCP (Model Context Protocol) server.
AI agents and desktop scripts can call your phone for live data and actions over LAN or Tailscale.

## Download (Testing Pre-release)

- Latest testing release: [v1.0.0-beta.1](https://github.com/axonixtools/PocketMCP/releases/tag/v1.0.0-beta.1)
- This build is marked as pre-release and may contain bugs while active development continues.

## Open-Source Commitment

PocketMCP is fully open-source and core functionality is free.
The project is MIT-licensed and community contributions are welcome.

## Why this is useful

**High-value use cases for all users:**

1. **Communication Hub**: Send WhatsApp, Instagram, Messenger messages and make calls from desktop
2. **Social Media Automation**: Interact with Instagram, YouTube, X/Twitter automatically
3. **Notification Management**: Monitor and respond to all phone notifications in real-time
4. **Daily readiness check**: "What is my battery and where is my phone right now?"
5. **Contact lookup**: "Find Alice's number from my phone contacts."
6. **App ecosystem control**: Launch and manage 309+ installed applications
7. **Phone diagnostics**: Run safe shell checks without unlocking the phone.

## Built-in tools

| Tool                  | Description                                                       |
| --------------------- | ----------------------------------------------------------------- |
| `device_info`         | Battery, model, OS, network, memory                               |
| `get_location`        | Best recent location from device providers                        |
| `search_contacts`     | Search contacts by name                                           |
| `make_call`           | Make phone calls to any number                                    |
| `send_message`        | Send messages via WhatsApp, Instagram, Messenger, Google Messages |
| `send_whatsapp_business_message` | Dedicated WhatsApp Business contact-safe sender      |
| `whatsapp_automation` | Advanced WhatsApp automation with accessibility control           |
| `social_media`        | Instagram, YouTube, X/Twitter interactions                        |
| `app_actions`         | JSON-preset actions for simpler app automation calls              |
| `notifications`       | Real-time notification monitoring and management                  |
| `shell`               | Run shell commands with safety filters and timeout                |
| `flashlight`          | Turn flashlight on/off/toggle and read state                      |
| `launch_app`          | Open an installed app by package or app name                      |
| `list_apps`           | List launchable installed apps (309+ apps detected)               |
| `global_action`       | Home/back/recents/notifications/quick settings/lock screen        |
| `scroll_screen`       | Scroll current app via accessibility gesture                      |
| `search_screen`       | Find search input on current screen and type query safely         |
| `tap`                 | Tap by visible text/content description or screen coordinates     |
| `volume_control`      | Read and change stream volume levels                              |
| `phone_alert`         | Ring and/or vibrate phone to help locate it quickly               |
| `voice_record`        | Record voice notes from microphone                                |
| `transcribe_audio`    | Speech-to-text from microphone or local audio file path          |
| `transcribe_file`     | Direct audio-file transcription by path                           |
| `transcribe_whatsapp_audio` | Transcribe latest WhatsApp/WhatsApp Business voice note     |
| `human_command`       | Natural-language command router across major tools                |
| `intelligent_action`  | Experimental humanoid planner/executor with retries and traces    |
| `http_request`        | Make outbound HTTP requests from phone                            |
| `read_file`           | Read files from allowed storage paths                             |

## 🆕 New Features (v.0+)

### 📞 **Phone Calling & Messaging**

- **Direct Calls**: Make phone calls to any number with contact name support
- **Multi-Platform Messaging**: WhatsApp, Instagram, Messenger, Google Messages
- **WhatsApp Business Support**: Prioritizes WhatsApp Business when installed
- **Deep Link Integration**: Opens apps with pre-filled content

### 🤖 **Advanced WhatsApp Automation**

- **Complete Automation**: Send messages with contact selection and typing
- **Step-by-Step Control**: Select contacts, type messages, press send/cancel
- **Accessibility Integration**: Full UI automation via accessibility service
- **Business & Personal**: Supports both WhatsApp variants

### 🌐 **Social Media Interactions**

- **Instagram**: Profile search, post interactions (like, comment, share)
- **YouTube**: Video search, like/dislike/comment on videos
- **X/Twitter**: Profile search, post interactions
- **Deep Links**: Direct navigation to specific content

### 🔔 **Real-Time Notifications**

- **Live Monitoring**: Capture all device notifications in real-time
- **Full Data**: Title, text, app, time, priority, visibility
- **Historical Access**: Recent notifications with search and filtering
- **Management**: Clear history, check status, get counts

## App UI & Design

PocketMCP features a **custom-built Jetpack Compose UI** with a premium "Neural Dark" cyber-dashboard aesthetic.

### Design Highlights

- **Neural Dark Theme**: Deep space background (`#03040A`) with electric cyan (`#00E5FF`) and violet (`#7C4DFF`) energy accents
- **Glassmorphism Cards**: Translucent surfaces with subtle borders and active glow states
- **Ambient Animated Background**: Slow-orbiting glow orbs with a subtle mesh grid overlay
- **Fluid Micro-interactions**: Spring-physics press scaling, staggered card entrances, and smooth page transitions
- **Status Orb**: Animated pulsing indicator with dual ripple rings that react to server state

### Screens

| Screen | Description |
|--------|-------------|
| **Server** | Hero status orb, connection info, config inputs, one-tap MCP config copy |
| **Logs** | Live monospace terminal, filterable event stream, color-coded log cards |
| **Tools** | 20-tool searchable library with category filtering and real-time status badges |
| **Settings** | Permission toggles, safety filter, developer mode, API key management |

### Navigation

- Bottom navigation bar with animated scale, icon transitions, and expanding indicator pills
- Horizontal slide + fade page transitions between tabs
- Snackbar feedback for all user actions (replacing legacy Toast)

## Security model

- API key authentication supported via `X-API-Key` (recommended).
- Local network usage by default; use Tailscale for remote access.
- Shell tool blocks high-risk commands and enforces timeout/output limits.
- File reads are restricted to app storage and external storage roots.
- Loopback URLs are blocked in `http_request`.
- **Accessibility Service**: Required for advanced automation features.
- **Notification Access**: Required for real-time notification monitoring.

## Reliability model

- Android foreground service keeps MCP server alive.
- Start/stop/status lifecycle is explicit (`START`, `STOP`, `QUERY_STATUS`).
- UI reflects actual service state using status broadcasts.
- **Enhanced App Discovery**: Detects 309+ launchable apps (up from 25).
- **Graceful Fallbacks**: Manual interaction instructions when automation not possible.

## Intelligent Orchestration (Experimental)

PocketMCP now includes a new high-level orchestration tool, `intelligent_action`, designed for "humanoid" multi-step flows across apps.

### What it adds

- Natural command parsing into structured intents and entities
- Task-graph planning (instead of one-shot command execution)
- Step-by-step execution with retry and verification
- Risk gating for sensitive actions (for example report/flag flows)
- Trace logging for continuous reliability improvement

### Current architecture

- Tool entrypoint: `app/src/main/java/com/pocketmcp/tools/IntelligentActionTool.kt`
- Agent core packages: `app/src/main/java/com/pocketmcp/agent/`
  - `model/` intent/step/result models
  - `nlu/` parser and entity resolution
  - `planner/` graph builder and policy gate
  - `execution/` orchestrator, executor, verifier, retry behavior
  - `skills/` app-specific skill packs (`youtube`, `x`)
  - `perception/` popup/blocker helpers
  - `memory/` session context and trace writing
  - `dialog/` clarification prompts for ambiguous requests

### Usage notes

- Use `dry_run=true` to inspect parsed intents and planned steps before execution.
- Keep `confirm_risky=true` for safer defaults in production-like usage.
- Execution traces are written under app-private files (`agent-traces`) for tuning.

### Example MCP call payload

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "intelligent_action",
    "arguments": {
      "command": "open youtube and search psg and bayern",
      "dry_run": false,
      "confirm_risky": true
    }
  }
}
```

## Quick start

### 1) Install app

Option A: install from pre-release APK

- Download `PocketMCP-debug.apk` from [v1.0.0-beta.1](https://github.com/axonixtools/PocketMCP/releases/tag/v1.0.0-beta.1)
- Install with ADB:

```bash
adb install -r PocketMCP-debug.apk
```

Option B: build from source and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2) Start server in app

1. Open PocketMCP.
2. The **Server** tab opens by default.
3. Set port (default `8080`) in the **Configuration** card.
4. Generate or paste an API key.
5. Tap **Start Server** — the status orb will pulse green when active.
6. Copy the endpoint or full MCP config from the **Quick Connect** card.

### 3) Enable Permissions

**Required for full functionality:**

- **Phone**: `CALL_PHONE` permission in app settings
- **Microphone**: `RECORD_AUDIO` permission in app settings
- **SMS**: `SEND_SMS`, `READ_SMS` permissions
- **Notifications**: Settings → Apps → Special Access → Notification Access
- **Accessibility**: Settings → Apps → Special Access → Accessibility Services

All permission states are visible in the **Settings** tab with one-tap navigation to system settings.

### 4) Verify server

```bash
# health
curl http://192.0.2.100:8080/health

# list tools
curl -X POST http://192.0.2.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Emulator connection (Android Studio AVD)

If PocketMCP runs inside an Android emulator, do **not** use `10.0.2.15` from your host MCP client config.

Use ADB port forwarding and localhost:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-emulator-mcp.ps1
```

Then set MCP URL to:

```text
http://127.0.0.1:8080/mcp
```

Notes:
- `10.0.2.15` is emulator-internal (not host-reachable).
- `10.0.2.2` is for emulator -> host, not host -> emulator.

## MCP Client Config Example

Use `mcp-config.example.json` as a template.

```json
{
  "mcpServers": {
    "phone": {
      "type": "streamableHttp",
      "url": "http://192.0.2.100:8080/mcp",
      "headers": {
        "X-API-Key": "your-api-key"
      }
    }
  }
}
```

## Stdio Bridge (for stdio-only MCP clients)

PocketMCP includes a hardened stdio bridge in `mcp-bridge/bridge.mjs` that proxies stdio MCP calls to your phone's HTTP MCP endpoint.

```bash
cd mcp-bridge
npm install
node bridge.mjs --url http://192.0.2.100:8080/mcp --api-key your-api-key --verbose
```

Bridge configuration options:

- `--url`: target phone MCP URL (auto-adds `http://` if missing)
- `--api-key`: API key sent as `X-API-Key`
- `--timeout-ms`: per-request timeout (default `20000`)
- `--tools-ttl-ms`: tool cache TTL (default `30000`)
- `--no-tool-cache`: disable tool caching
- `--verbose`: emit detailed bridge logs to stderr

Equivalent environment variables:

- `POCKET_MCP_URL`
- `POCKET_MCP_API_KEY`
- `POCKET_MCP_TIMEOUT_MS`
- `POCKET_MCP_TOOLS_TTL_MS`
- `POCKET_MCP_DISABLE_TOOL_CACHE=1`
- `POCKET_MCP_VERBOSE=1`

## Python Client Example

```python
from pocket_mcp_client import PocketMCPClient

phone = PocketMCPClient("http://192.0.2.100:8080", api_key="your-api-key")

print(phone.device_info())
print(phone.list_apps(limit=25))
print(phone.notifications("list", 10))
print(phone.human_command("vibrate my phone for 5 seconds"))
```

Python client now includes retries, typed exceptions, stricter argument validation, and a CLI:

```bash
# health check (default action)
python3 pocket_mcp_client.py 192.0.2.100:8080 your-api-key

# list tools
python3 pocket_mcp_client.py 192.0.2.100:8080 your-api-key --list-tools

# call one tool with JSON args
python3 pocket_mcp_client.py 192.0.2.100:8080 your-api-key \
  --call send_message \
  --args '{"app":"whatsapp","phone_number":"+15551234567","message":"hello from desktop"}'
```

## Development

### Tech Stack

| Layer | Technology |
|-------|-----------|
| UI Framework | Jetpack Compose (Material 3) |
| Language | Kotlin 1.9.20 |
| Build System | Gradle 9.0-milestone-1 with Kotlin DSL |
| HTTP Server | Ktor 2.3.7 (Netty engine) |
| Serialization | kotlinx-serialization-json 1.6.0 |
| Target SDK | 34 (Android 14) |
| Min SDK | 26 (Android 8.0) |

### Project Structure

```
app/src/main/java/com/pocketmcp/
├── MainActivity.kt              # Compose entry point + business logic
├── accessibility/               # PhoneAccessibilityService
├── server/                      # Ktor MCP server + JSON-RPC types
├── service/                     # McpServerService (foreground service)
├── tools/                       # ~35 MCP tool implementations
└── ui/                          # Jetpack Compose UI
    ├── theme/                   # Colors, Typography, Theme
    ├── components/              # Reusable UI components
    │   ├── AnimatedBackground.kt
    │   ├── AnimatedBottomNav.kt
    │   ├── GlassCard.kt
    │   └── StatusOrb.kt
    └── screens/                 # Screen composables
        ├── DashboardScreen.kt
        ├── LogsScreen.kt
        ├── SettingsScreen.kt
        └── ToolsScreen.kt
```

### Adding a New Tool

1. Create a new Kotlin file in `app/src/main/java/com/pocketmcp/tools/`.
2. Implement `McpToolHandler`.
3. Register the tool in `McpServer.init { registry.register(YourTool()) }`.
4. Add it to `ToolsScreen.kt` for UI visibility.

## Permissions

Some tools require Android special permissions or services:

- Phone (`CALL_PHONE`)
- Microphone (`RECORD_AUDIO`)
- SMS (`SEND_SMS`, `READ_SMS`, `RECEIVE_SMS`)
- Notifications access (Notification Listener)
- Accessibility service (for UI automation)

## Security Model

- API key authentication through `X-API-Key` (recommended)
- Local network first; remote access via private networks like Tailscale
- Shell command restrictions with timeout/output limits
- File-read path restrictions
- Loopback URL blocking for `http_request`

## Reliability Notes

- Runs as an Android foreground service
- Explicit service lifecycle (`START`, `STOP`, `QUERY_STATUS`)
- Status broadcast updates reflected in UI

## Roadmap

- Camera capture tools
- More accessibility-driven automations
- WebSocket support
- One-tap pairing workflow
- Voice-first command flows

## License

MIT
