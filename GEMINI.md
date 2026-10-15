# PocketMCP — Gemini CLI Context

This project turns an Android device into a [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server, exposing phone capabilities (sensors, notifications, app automation) to desktop AI agents.

## Project Overview

- **Type**: Android Application (Kotlin) + MCP Server.
- **Key Technologies**:
    - **Language**: Kotlin 1.9.20.
    - **Server**: Ktor 2.3.7 (Netty engine) supporting HTTP POST, SSE, and WebSockets.
    - **UI**: Jetpack Compose (Material 3) + Legacy XML ViewBinding.
    - **Automation**: Android `AccessibilityService` for UI interaction and `NotificationListenerService` for monitoring.
    - **Protocol**: MCP Protocol version `2024-11-05`.

### Core Architecture

1.  **Server Layer (`com.pocketmcp.server`)**: The Ktor-based `McpServer` handles JSON-RPC requests, transport management (SSE/WS), and tool dispatching.
2.  **Service Layer (`com.pocketmcp.service`)**: `McpServerService` runs as an Android foreground service to ensure the server remains active. It also handles mDNS discovery.
3.  **Tool Layer (`com.pocketmcp.tools`)**: Individual MCP tools (35+) implementing the `McpToolHandler` interface.
4.  **Agent Layer (`com.pocketmcp.agent`)**: Experimental orchestration layer for multi-step "humanoid" automation tasks.
5.  **Accessibility (`com.pocketmcp.accessibility`)**: `PhoneAccessibilityService` provides the "eyes and hands" for UI automation.

## Building and Running

### Prerequisites
- Android SDK (API 34).
- Java 17+ (for Gradle).

### Key Commands
- **Build Debug APK**: `./gradlew assembleDebug`
- **Install APK**: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Clean Project**: `./gradlew clean`
- **Emulator Setup**: `powershell -ExecutionPolicy Bypass -File .\scripts\setup-emulator-mcp.ps1` (for port forwarding).
- **Run Bridge (Node.js)**: 
  ```bash
  cd mcp-bridge && npm install && node bridge.mjs --url http://<IP>:8080/mcp --api-key <KEY>
  ```

## Development Conventions

### Adding a Tool
1.  **Implementation**: Create a new class in `app/src/main/java/com/pocketmcp/tools/` implementing `McpToolHandler`.
2.  **Schema**: Use `kotlinx.serialization.json.buildJsonObject` to define the input schema in JSON Schema format.
3.  **Registration**: Add your tool instance to the registry in `McpServer.init`.
4.  **Results**: Use `ToolResultUtils.kt` helpers (`resultText`, `resultJson`, `resultError`).

### Code Style & Safety
- **Strings**: Always escape backslashes in non-raw strings (e.g., `\\s` instead of `\s`) for Regex.
- **Logging**: Use `Log.d("PocketMCP", ...)` for debugging.
- **Permissions**: Check if the tool requires runtime permissions (Location, Contacts, etc.) or special services (Accessibility).
- **Security**: The `ShellCommandTool` has a strict safety filter; never bypass it without explicit user instruction.

### Testing
- Currently, the project has minimal automated tests. 
- **TODO**: Add unit tests in `src/test/java/` and instrumented tests in `src/androidTest/java/`.

## Key Files & Directories
- `app/src/main/java/com/pocketmcp/server/McpServer.kt`: The heartbeat of the MCP server.
- `app/src/main/java/com/pocketmcp/tools/McpToolRegistry.kt`: Central tool repository.
- `app/src/main/java/com/pocketmcp/accessibility/PhoneAccessibilityService.kt`: Core automation logic.
- `AGENTS.md`: An exhaustive technical guide specifically for AI agents.
- `README.md`: General project documentation and user guide.
- `mcp-bridge/`: Node.js bridge for stdio-only MCP clients.

## Important Notes for Gemini CLI
- **Emulator Connectivity**: When running on an emulator, use `127.0.0.1:8080` with ADB port forwarding, not the emulator's internal IP (`10.0.2.15`).
- **Foreground Service**: The server's lifecycle is tied to `McpServerService`.
- **Accessibility**: Most automation tools (`tap`, `scroll`, `whatsapp_automation`) require the Accessibility Service to be manually enabled in Android Settings.
