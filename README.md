# PocketMCP

Turn your Android phone into an MCP (Model Context Protocol) server.
AI agents and desktop scripts can call your phone for live data and actions over LAN or Tailscale.

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

## Quick start

### 1) Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2) Start server in app

1. Open PocketMCP.
2. Set port (default `8080`).
3. Generate or paste API key.
4. Start server from switch or button.
5. Copy endpoint/config from the Quick Connect section.

### 3) Enable Permissions

**Required for full functionality:**

- **Phone**: CALL_PHONE permission in app settings
- **Microphone**: RECORD_AUDIO permission in app settings
- **SMS**: SEND_SMS, READ_SMS permissions
- **Notifications**: Settings > Apps > Special Access > Notification Access
- **Accessibility**: Settings > Apps > Special Access > Accessibility Services

### 3) Verify server

```bash
# health
curl http://192.168.1.100:8080/health

# list tools
curl -X POST http://192.168.1.100:8080/mcp \
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
      "url": "http://192.168.1.100:8080/mcp",
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
node bridge.mjs --url http://192.168.1.100:8080/mcp --api-key your-api-key --verbose
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

phone = PocketMCPClient("http://192.168.1.100:8080", api_key="your-api-key")

print(phone.device_info())
print(phone.list_apps(limit=25))
print(phone.notifications("list", 10))
print(phone.human_command("vibrate my phone for 5 seconds"))
```

Python client now includes retries, typed exceptions, stricter argument validation, and a CLI:

```bash
# health check (default action)
python3 pocket_mcp_client.py 192.168.1.100:8080 your-api-key

# list tools
python3 pocket_mcp_client.py 192.168.1.100:8080 your-api-key --list-tools

# call one tool with JSON args
python3 pocket_mcp_client.py 192.168.1.100:8080 your-api-key \
  --call send_message \
  --args '{"app":"whatsapp","phone_number":"+15551234567","message":"hello from desktop"}'
```

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
