"""PocketMCP Python Client.

Connect to your Android phone's MCP server from Python scripts or agents.
"""

from __future__ import annotations

import json
from typing import Any, Optional

import requests


class PocketMCPClient:
    """Python client for PocketMCP JSON-RPC endpoints."""

    def __init__(self, base_url: str, api_key: Optional[str] = None, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.headers = {"Content-Type": "application/json"}
        if api_key:
            self.headers["X-API-Key"] = api_key
        self._id_counter = 0

    def _next_id(self) -> int:
        self._id_counter += 1
        return self._id_counter

    def _rpc(self, method: str, params: Optional[dict[str, Any]] = None) -> Any:
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        response = requests.post(
            f"{self.base_url}/mcp",
            json=payload,
            headers=self.headers,
            timeout=self.timeout,
        )
        response.raise_for_status()
        data = response.json()

        if "error" in data:
            error = data["error"]
            raise RuntimeError(f"MCP Error {error.get('code')}: {error.get('message')}")

        return data.get("result")

    @staticmethod
    def _decode_tool_result(result: Any) -> Any:
        if isinstance(result, dict) and result.get("isError"):
            tool_error = result.get("toolError") or "Unknown tool error"
            raise RuntimeError(tool_error)

        if isinstance(result, dict) and "content" in result:
            text_parts = [c.get("text", "") for c in result["content"] if c.get("type") == "text"]
            text = "\n".join(part for part in text_parts if part)
            if not text:
                return result
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return text

        return result

    def initialize(self) -> dict[str, Any]:
        return self._rpc(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "clientInfo": {"name": "PocketMCPPythonClient", "version": "0.1.0"},
                "capabilities": {},
            },
        )

    def list_tools(self) -> list[dict[str, Any]]:
        result = self._rpc("tools/list")
        if isinstance(result, dict):
            return result.get("tools", [])
        return []

    def call_tool(self, name: str, arguments: Optional[dict[str, Any]] = None) -> Any:
        result = self._rpc("tools/call", {"name": name, "arguments": arguments or {}})
        return self._decode_tool_result(result)

    def health(self) -> dict[str, Any]:
        response = requests.get(f"{self.base_url}/health", timeout=min(self.timeout, 10))
        response.raise_for_status()
        return response.json()

    def device_info(self) -> Any:
        return self.call_tool("device_info")

    def get_location(self) -> Any:
        return self.call_tool("get_location")

    def search_contacts(self, query: str, limit: int = 10) -> Any:
        return self.call_tool("search_contacts", {"query": query, "limit": limit})

    def make_call(self, phone_number: str, contact_name: Optional[str] = None) -> Any:
        args: dict[str, Any] = {"phone_number": phone_number}
        if contact_name:
            args["contact_name"] = contact_name
        return self.call_tool("make_call", args)

    def send_message(
        self,
        app: str,
        phone_number: Optional[str] = None,
        message: str = "",
        contact_name: Optional[str] = None,
        username: Optional[str] = None,
        whatsapp_type: Optional[str] = None,
        strict_contact_match: Optional[bool] = None,
        strict_screen_state: bool = True,
    ) -> Any:
        args: dict[str, Any] = {
            "app": app,
            "message": message,
            "strict_screen_state": strict_screen_state,
        }
        if phone_number:
            args["phone_number"] = phone_number
        if contact_name:
            args["contact_name"] = contact_name
        if username:
            args["username"] = username
        if whatsapp_type:
            args["whatsapp_type"] = whatsapp_type
        if strict_contact_match is not None:
            args["strict_contact_match"] = strict_contact_match
        return self.call_tool("send_message", args)

    def send_whatsapp_business_message(
        self,
        contact_name: str,
        message: str,
        phone_number: Optional[str] = None,
        strict_contact_match: bool = True,
        strict_screen_state: bool = True,
    ) -> Any:
        args: dict[str, Any] = {
            "contact_name": contact_name,
            "message": message,
            "strict_contact_match": strict_contact_match,
            "strict_screen_state": strict_screen_state,
        }
        if phone_number:
            args["phone_number"] = phone_number
        return self.call_tool("send_whatsapp_business_message", args)

    def send_whatsapp_business(
        self,
        contact_name: str,
        message: str,
        phone_number: Optional[str] = None,
        strict_contact_match: bool = True,
        strict_screen_state: bool = True,
    ) -> Any:
        return self.send_whatsapp_business_message(
            contact_name=contact_name,
            message=message,
            phone_number=phone_number,
            strict_contact_match=strict_contact_match,
            strict_screen_state=strict_screen_state,
        )

    def whatsapp_automation(
        self,
        action: str,
        contact_name: Optional[str] = None,
        message: Optional[str] = None,
        phone_number: Optional[str] = None,
        whatsapp_type: str = "business",
    ) -> Any:
        args: dict[str, Any] = {"action": action, "whatsapp_type": whatsapp_type}
        if contact_name:
            args["contact_name"] = contact_name
        if message:
            args["message"] = message
        if phone_number:
            args["phone_number"] = phone_number
        return self.call_tool("whatsapp_automation", args)

    def social_media(
        self,
        platform: str,
        action: str,
        query: Optional[str] = None,
        username: Optional[str] = None,
        content_url: Optional[str] = None,
        text: Optional[str] = None,
        strict_screen_state: bool = True,
    ) -> Any:
        args: dict[str, Any] = {
            "platform": platform,
            "action": action,
            "strict_screen_state": strict_screen_state,
        }
        if query:
            args["query"] = query
        if username:
            args["username"] = username
        if content_url:
            args["content_url"] = content_url
        if text:
            args["text"] = text
        return self.call_tool("social_media", args)

    def app_actions(self, app: str, action: str, **kwargs: Any) -> Any:
        args = {"app": app, "action": action, **kwargs}
        return self.call_tool("app_actions", args)

    def notifications(
        self,
        action: str = "list",
        limit: int = 20,
        query: Optional[str] = None,
        index: Optional[int] = None,
    ) -> Any:
        args: dict[str, Any] = {"action": action, "limit": limit}
        if query is not None:
            args["query"] = query
        if index is not None:
            args["index"] = index
        return self.call_tool("notifications", args)

    def shell(self, command: str, timeout_seconds: int = 10) -> Any:
        return self.call_tool("shell", {"command": command, "timeout_seconds": timeout_seconds})

    def flashlight(self, action: str = "status", camera_id: Optional[str] = None) -> Any:
        args: dict[str, Any] = {"action": action}
        if camera_id:
            args["camera_id"] = camera_id
        return self.call_tool("flashlight", args)

    def launch_app(self, package_name: Optional[str] = None, app_name: Optional[str] = None) -> Any:
        args: dict[str, Any] = {}
        if package_name:
            args["package_name"] = package_name
        if app_name:
            args["app_name"] = app_name
        return self.call_tool("launch_app", args)

    def close_app(self, package_name: Optional[str] = None, app_name: Optional[str] = None) -> Any:
        args: dict[str, Any] = {"action": "close"}
        if package_name:
            args["package_name"] = package_name
        if app_name:
            args["app_name"] = app_name
        return self.call_tool("launch_app", args)

    def list_apps(self, query: Optional[str] = None, limit: int = 50) -> Any:
        args: dict[str, Any] = {"limit": limit}
        if query:
            args["query"] = query
        return self.call_tool("list_apps", args)

    def global_action(self, action: str) -> Any:
        return self.call_tool("global_action", {"action": action})

    def scroll_screen(self, direction: str, distance_ratio: float = 0.55, duration_ms: int = 320) -> Any:
        return self.call_tool(
            "scroll_screen",
            {"direction": direction, "distance_ratio": distance_ratio, "duration_ms": duration_ms},
        )

    def tap(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        x: Optional[int] = None,
        y: Optional[int] = None,
        strict_screen_state: bool = True,
    ) -> Any:
        args: dict[str, Any] = {"strict_screen_state": strict_screen_state}
        if text is not None:
            args["text"] = text
        if content_description is not None:
            args["content_description"] = content_description
        if x is not None and y is not None:
            args["x"] = x
            args["y"] = y
        return self.call_tool("tap", args)

    def volume_control(
        self,
        action: str = "status",
        stream: str = "music",
        level: Optional[int] = None,
        steps: int = 1,
        show_ui: bool = False,
    ) -> Any:
        args: dict[str, Any] = {
            "action": action,
            "stream": stream,
            "steps": steps,
            "show_ui": show_ui,
        }
        if level is not None:
            args["level"] = level
        return self.call_tool("volume_control", args)

    def phone_alert(self, action: str = "both", duration_seconds: int = 8) -> Any:
        return self.call_tool("phone_alert", {"action": action, "duration_seconds": duration_seconds})

    def ring_phone(self, duration_seconds: int = 8) -> Any:
        return self.phone_alert(action="ring", duration_seconds=duration_seconds)

    def vibrate_phone(self, duration_seconds: int = 5) -> Any:
        return self.phone_alert(action="vibrate", duration_seconds=duration_seconds)

    def voice_record(
        self,
        action: str = "record",
        duration_seconds: int = 6,
        filename_prefix: Optional[str] = None,
    ) -> Any:
        args: dict[str, Any] = {"action": action, "duration_seconds": duration_seconds}
        if filename_prefix:
            args["filename_prefix"] = filename_prefix
        return self.call_tool("voice_record", args)

    def record_voice_note(self, duration_seconds: int = 6, filename_prefix: str = "voice") -> Any:
        return self.voice_record(
            action="record",
            duration_seconds=duration_seconds,
            filename_prefix=filename_prefix,
        )

    def transcribe_audio(
        self,
        action: str = "listen",
        duration_seconds: int = 8,
        language_tag: str = "en-US",
        prefer_offline: bool = True,
        audio_path: Optional[str] = None,
    ) -> Any:
        args: dict[str, Any] = {
            "action": action,
            "duration_seconds": duration_seconds,
            "language_tag": language_tag,
            "prefer_offline": prefer_offline,
        }
        if audio_path:
            args["audio_path"] = audio_path
        return self.call_tool("transcribe_audio", args)

    def listen_and_transcribe(
        self,
        duration_seconds: int = 8,
        language_tag: str = "en-US",
        prefer_offline: bool = True,
    ) -> Any:
        return self.transcribe_audio(
            action="listen",
            duration_seconds=duration_seconds,
            language_tag=language_tag,
            prefer_offline=prefer_offline,
        )

    def transcribe_file(self, audio_path: str, language_tag: str = "en-US", prefer_offline: bool = True) -> Any:
        return self.call_tool(
            "transcribe_file",
            {
                "audio_path": audio_path,
                "language_tag": language_tag,
                "prefer_offline": prefer_offline,
            },
        )

    def transcribe_whatsapp_audio(
        self,
        contact_name: Optional[str] = None,
        whatsapp_type: str = "business",
        language_tag: str = "en-US",
        prefer_offline: bool = True,
        raw_command: Optional[str] = None,
    ) -> Any:
        args: dict[str, Any] = {
            "whatsapp_type": whatsapp_type,
            "language_tag": language_tag,
            "prefer_offline": prefer_offline,
        }
        if contact_name:
            args["contact_name"] = contact_name
        if raw_command:
            args["raw_command"] = raw_command
        return self.call_tool("transcribe_whatsapp_audio", args)

    def human_command(self, command: str) -> Any:
        return self.call_tool("human_command", {"command": command})

    def http_request(
        self,
        url: str,
        method: str = "GET",
        headers: Optional[dict[str, str]] = None,
        body: Optional[str] = None,
        timeout_seconds: int = 20,
    ) -> Any:
        args: dict[str, Any] = {"url": url, "method": method, "timeout_seconds": timeout_seconds}
        if headers:
            args["headers"] = headers
        if body is not None:
            args["body"] = body
        return self.call_tool("http_request", args)

    def read_file(self, path: str, max_bytes: Optional[int] = None) -> Any:
        args: dict[str, Any] = {"path": path}
        if max_bytes is not None:
            args["max_bytes"] = max_bytes
        return self.call_tool("read_file", args)


class PocketMCPToolAdapter:
    """Adapter for frameworks expecting list-tools + call-tool hooks."""

    def __init__(self, client: PocketMCPClient):
        self.client = client

    def as_tool_schemas(self) -> list[dict[str, Any]]:
        tools = self.client.list_tools()
        schemas: list[dict[str, Any]] = []
        for tool in tools:
            schemas.append(
                {
                    "name": tool.get("name"),
                    "description": tool.get("description", ""),
                    "input_schema": tool.get("inputSchema", {"type": "object", "properties": {}}),
                }
            )
        return schemas

    def call(self, name: str, arguments: Optional[dict[str, Any]] = None) -> Any:
        return self.client.call_tool(name, arguments)


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage: python pocket_mcp_client.py <phone-ip[:port]> [api-key]")
        raise SystemExit(1)

    endpoint = sys.argv[1]
    if not endpoint.startswith("http"):
        endpoint = f"http://{endpoint}"
    api_key = sys.argv[2] if len(sys.argv) >= 3 else None

    client = PocketMCPClient(endpoint, api_key=api_key)
    print(json.dumps(client.health(), indent=2))
