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

### 4) Verify with curl

```bash
# health
curl http://192.168.1.100:8080/health

# list tools
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# make a phone call
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"make_call","arguments":{"phone_number":"+1234567890"}}}'

# send WhatsApp message
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"send_message","arguments":{"app":"whatsapp","phone_number":"+1234567890","message":"Hello from MCP!"}}}'

# send WhatsApp Business message by contact name (safe screen-state verification)
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"send_whatsapp_business_message","arguments":{"contact_name":"Fahad Shakoor","message":"ok bawa gee done ho gaya","strict_contact_match":true}}}'

# same tool with a natural-language command string
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"send_whatsapp_business_message","arguments":{"raw_command":"send message to Fahad Shakoor on whatsapp bussiness kesa laga mera shugal","strict_contact_match":true}}}'

# or via generic send_message (also supports typo alias: whatsapp bussiness)
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"send_message","arguments":{"app":"whatsapp_business","contact_name":"Fahad Shakoor","message":"ok bawa gee done ho gaya","strict_contact_match":true}}}'

# advanced WhatsApp automation
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"whatsapp_automation","arguments":{"action":"send_message","contact_name":"John","message":"Hi John!","whatsapp_type":"business"}}}'

# get recent notifications
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"notifications","arguments":{"action":"list","limit":20}}}'

# list active notifications and open one
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":51,"method":"tools/call","params":{"name":"notifications","arguments":{"action":"active","limit":20}}}'
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":52,"method":"tools/call","params":{"name":"notifications","arguments":{"action":"open","query":"whatsapp","index":1}}}'

# tap a visible button by text
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":53,"method":"tools/call","params":{"name":"tap","arguments":{"text":"Allow"}}}'

# Instagram profile interaction
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"social_media","arguments":{"platform":"instagram","action":"open_profile","query":"username"}}}'

# easy preset-driven action: send WhatsApp Business message
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"app_actions","arguments":{"app":"whatsapp_business","action":"send_message_contact","contact_name":"Riyan Ali","phone_number":"+923302626538","message":"HI testing from MCP server","strict_contact_match":true}}}'

# launch YouTube by app name
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"launch_app","arguments":{"app_name":"YouTube"}}}'

# ring and vibrate phone for 12 seconds
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"phone_alert","arguments":{"action":"both","duration_seconds":12}}}'

# record a 7-second voice note
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"voice_record","arguments":{"action":"record","duration_seconds":7,"filename_prefix":"meeting_note"}}}'

# transcribe live speech for 12 seconds
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"transcribe_audio","arguments":{"action":"listen","duration_seconds":12,"language_tag":"en-US","prefer_offline":true}}}'

# transcribe recorded file by path
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"transcribe_file","arguments":{"audio_path":"/storage/emulated/0/Android/data/com.pocketmcp/files/Music/voice_recordings/voice_1771452202949.m4a","language_tag":"en-US","prefer_offline":true}}}'

# transcribe WhatsApp voice note by path (Personal or Business media folder)
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"transcribe_file","arguments":{"audio_path":"/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/20260218/PTT-20260218-WA0001.opus","language_tag":"en-US","prefer_offline":true}}}'

# one-shot: auto-pick latest WhatsApp Business audio and transcribe
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"transcribe_whatsapp_audio","arguments":{"contact_name":"Fahad Shakoor","whatsapp_type":"business","language_tag":"en-US","prefer_offline":true}}}'

# same one-shot tool with natural language
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"transcribe_whatsapp_audio","arguments":{"raw_command":"transcribe the last audio of fahad shakoor from whatsapp bussiness"}}}'

# natural language router for mixed commands
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"human_command","arguments":{"command":"send message to fahad shakoor on whatsapp bussiness kesa laga mera shugal"}}}'

curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"human_command","arguments":{"command":"record voice for 10 seconds"}}}'

# check transcription status/readiness
curl -X POST http://192.168.1.100:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"transcribe_audio","arguments":{"action":"status"}}}'
```

## Python client

```python
from pocket_mcp_client import PocketMCPClient

phone = PocketMCPClient("http://192.168.1.100:8080", api_key="your-api-key")

# Basic device info
print(phone.device_info())

# App ecosystem (309+ apps)
print(phone.list_apps(limit=50))

# Communication
print(phone.make_call("+1234567890"))
print(phone.send_message("whatsapp", "+1234567890", "Hello from MCP!"))
print(phone.send_whatsapp_business("Fahad Shakoor", "ok bawa gee done ho gaya"))
print(phone.whatsapp_automation("send_message", "John", "Hi there!", "business"))

# Social media
print(phone.social_media("instagram", "search", "travel"))
print(phone.social_media("youtube", "search", "pocket mcp tutorial"))

# Notifications
print(phone.notifications("list", 20))
print(phone.notifications("status"))

# Location and contacts
print(phone.get_location())
print(phone.search_contacts("Alice", limit=5))

# System control
print(phone.shell("getprop ro.product.model"))
print(phone.flashlight("on"))
print(phone.ring_phone(duration_seconds=8))
print(phone.vibrate_phone(duration_seconds=5))
print(phone.record_voice_note(duration_seconds=6, filename_prefix="quick_note"))
print(phone.listen_and_transcribe(duration_seconds=8, language_tag="en-US"))
print(phone.transcribe_file("/storage/emulated/0/Android/data/com.pocketmcp/files/Music/voice_recordings/voice_1771452202949.m4a"))
print(phone.transcribe_whatsapp_audio(contact_name="Fahad Shakoor", whatsapp_type="business"))
print(phone.human_command("vibrate my phone for 8 seconds"))
print(phone.human_command("send message to Fahad Shakoor on whatsapp bussiness ok bawa gee"))
```

## MCP config example

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

## 🚀 What's New in v2.0

### ✅ **Complete Communication Suite**

- **Phone Calling**: Direct dialing with contact support
- **Multi-Platform Messaging**: WhatsApp, Instagram, Messenger, Google Messages
- **WhatsApp Business**: Prioritized Business app support
- **Advanced Automation**: Step-by-step WhatsApp control

### ✅ **Social Media Integration**

- **Instagram**: Search, profiles, post interactions
- **YouTube**: Video search, like/dislike/comment
- **X/Twitter**: Search, profiles, post interactions

### ✅ **Real-Time Notifications**

- **Live Monitoring**: All device notifications captured
- **Full Data**: Title, text, app, priority, visibility
- **Historical Access**: Search and filter notifications
- **Management**: Clear, status, counts

### ✅ **Enhanced App Ecosystem**

- **309+ Apps**: Up from 25 detected apps
- **Better Discovery**: Enhanced app detection method
- **Complete Coverage**: All launchable applications

## Roadmap

- **Camera capture tool**: Take photos and videos via MCP
- **Enhanced automation**: More accessibility-driven interactions
- **WebSocket support**: Real-time bidirectional communication
- **One-tap pairing**: QR code setup for easy connection
- **Voice commands**: Voice-activated phone control
- **SMS automation**: Direct SMS send/receive without app dependencies

## Support Development

If PocketMCP helps you, you can support continued development using the repository's **Sponsor** button.
Funding links are managed in `.github/FUNDING.yml`.

## License

MIT
