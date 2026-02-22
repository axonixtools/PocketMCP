"""PocketMCP Python Client.

Connect to your Android phone's MCP server from Python scripts or agents.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from typing import Any, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import threading

try:
    from websockets.sync.client import connect as ws_connect
except ImportError:
    ws_connect = None

try:
    from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
except ImportError:
    Zeroconf = None
    ServiceBrowser = None
    ServiceListener = object

def discover_mcp_url(timeout: float = 5.0) -> Optional[str]:
    if Zeroconf is None:
        return None
    discovered_url = None
    event = threading.Event()

    class McpListener(ServiceListener):
        def add_service(self, zc, type_, name):
            nonlocal discovered_url
            info = zc.get_service_info(type_, name)
            if info:
                addresses = info.parsed_addresses()
                if addresses:
                    port = info.port
                    discovered_url = f"ws://{addresses[0]}:{port}/ws"
                    event.set()

        def update_service(self, zc, type_, name): pass
        def remove_service(self, zc, type_, name): pass

    zeroconf = Zeroconf()
    browser = ServiceBrowser(zeroconf, "_mcp._tcp.local.", McpListener())
    event.wait(timeout)
    zeroconf.close()
    return discovered_url


class PocketMCPError(RuntimeError):
    """Base exception for PocketMCP client errors."""


class PocketMCPConnectionError(PocketMCPError):
    """Transport-level connection or HTTP errors."""


class PocketMCPProtocolError(PocketMCPError):
    """Unexpected protocol payload shape errors."""


class PocketMCPRpcError(PocketMCPError):
    """JSON-RPC error returned by PocketMCP server."""

    def __init__(self, error: dict[str, Any]):
        self.code = error.get("code")
        self.message = error.get("message") or "Unknown MCP error"
        self.data = error.get("data")

        details = f"MCP Error {self.code}: {self.message}"
        if self.data is not None:
            details += f" ({self.data})"

        super().__init__(details)


class PocketMCPToolError(PocketMCPError):
    """Tool-level runtime failure reported by tool output."""


class PocketMCPValidationError(PocketMCPError):
    """Client-side validation error before RPC request is sent."""


class PocketMCPClient:
    """Python client for PocketMCP JSON-RPC endpoints."""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        timeout: int = 30,
        max_retries: int = 2,
        backoff_factor: float = 0.3,
        discover: bool = False
    ):
        if not base_url and discover:
            discovered = discover_mcp_url()
            if discovered:
                print(f"Discovered PocketMCP at {discovered}", file=sys.stderr)
                base_url = discovered
            else:
                base_url = "http://127.0.0.1:8080"
                print(f"Discovery failed, defaulting to {base_url}", file=sys.stderr)
        
        self.base_url = self._normalize_base_url(base_url or "http://127.0.0.1:8080")
        self.timeout = max(1, int(timeout))
        self.headers = {"Content-Type": "application/json"}
        if api_key:
            self.headers["X-API-Key"] = api_key

        self._id_counter = 0
        self._session = requests.Session()
        self._ws = None

        retries = Retry(
            total=max(0, int(max_retries)),
            connect=max(0, int(max_retries)),
            read=max(0, int(max_retries)),
            status=max(0, int(max_retries)),
            backoff_factor=max(0.0, float(backoff_factor)),
            allowed_methods=frozenset(["GET", "POST"]),
            status_forcelist=(408, 429, 500, 502, 503, 504),
            raise_on_status=False,
        )
        adapter = HTTPAdapter(max_retries=retries)
        self._session.mount("http://", adapter)
        self._session.mount("https://", adapter)

    @staticmethod
    def _normalize_base_url(base_url: str) -> str:
        normalized = (base_url or "").strip()
        if not normalized:
            raise PocketMCPValidationError("base_url is required")
        if not normalized.startswith(("http://", "https://", "ws://", "wss://")):
            normalized = f"http://{normalized}"
        return normalized.rstrip("/")

    def close(self) -> None:
        self._session.close()
        if self._ws:
            self._ws.close()
            self._ws = None

    def __enter__(self) -> "PocketMCPClient":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.close()

    def _next_id(self) -> int:
        self._id_counter += 1
        return self._id_counter

    def _request_json(
        self,
        method: str,
        path: str,
        *,
        json_body: Optional[dict[str, Any]] = None,
        timeout: Optional[int] = None,
    ) -> dict[str, Any]:
        url = f"{self.base_url}{path}"

        try:
            response = self._session.request(
                method=method,
                url=url,
                json=json_body,
                headers=self.headers,
                timeout=timeout if timeout is not None else self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException as exc:
            raise PocketMCPConnectionError(f"Request failed: {exc}") from exc

        try:
            payload = response.json()
        except ValueError as exc:
            snippet = response.text.strip().replace("\n", " ")
            if len(snippet) > 220:
                snippet = f"{snippet[:220]}..."
            raise PocketMCPProtocolError(f"Non-JSON response from {url}: {snippet}") from exc

        if not isinstance(payload, dict):
            raise PocketMCPProtocolError(f"Expected JSON object response from {url}")

        return payload

    def _rpc(self, method: str, params: Optional[dict[str, Any]] = None) -> Any:
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        if self.base_url.startswith("ws"):
            if self._ws is None:
                if ws_connect is None:
                    raise PocketMCPConnectionError("websockets package is not installed")
                headers = {}
                if "X-API-Key" in self.headers:
                    headers["X-API-Key"] = self.headers["X-API-Key"]
                ws_url = f"{self.base_url}/ws" if not self.base_url.endswith("/ws") else self.base_url
                try:
                    self._ws = ws_connect(ws_url, additional_headers=headers, open_timeout=self.timeout)
                except Exception as e:
                    raise PocketMCPConnectionError(f"WebSocket connection failed: {e}")

            try:
                self._ws.send(json.dumps(payload))
                while True:
                    response_str = self._ws.recv(timeout=self.timeout)
                    data = json.loads(response_str)
                    if data.get("id") == payload["id"]:
                        break
            except Exception as e:
                self._ws.close()
                self._ws = None
                raise PocketMCPConnectionError(f"WebSocket communication failed: {e}")
        else:
            data = self._request_json("POST", "/mcp", json_body=payload)

        if "error" in data:
            error = data["error"]
            if not isinstance(error, dict):
                raise PocketMCPProtocolError("Invalid JSON-RPC error payload")
            raise PocketMCPRpcError(error)

        return data.get("result")

    @staticmethod
    def _decode_tool_result(result: Any) -> Any:
        if isinstance(result, dict) and result.get("isError"):
            tool_error = result.get("toolError") or "Unknown tool error"
            raise PocketMCPToolError(str(tool_error))

        if isinstance(result, dict) and "content" in result:
            text_parts = [
                item.get("text", "")
                for item in result["content"]
                if isinstance(item, dict) and item.get("type") == "text"
            ]
            text = "\n".join(part for part in text_parts if part)
            if not text:
                return result
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return text

        return result

    @staticmethod
    def _require_non_empty(value: Optional[str], field_name: str) -> None:
        if value is None or not value.strip():
            raise PocketMCPValidationError(f"{field_name} is required")

    def initialize(self) -> dict[str, Any]:
        result = self._rpc(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "clientInfo": {"name": "PocketMCPPythonClient", "version": "0.2.0"},
                "capabilities": {},
            },
        )
        if isinstance(result, dict):
            return result
        raise PocketMCPProtocolError("initialize response did not return an object")

    def list_tools(self) -> list[dict[str, Any]]:
        result = self._rpc("tools/list")
        if isinstance(result, dict):
            tools = result.get("tools", [])
            if isinstance(tools, list):
                return tools
        raise PocketMCPProtocolError("tools/list response did not include a tools array")

    def list_tool_names(self) -> list[str]:
        return [tool.get("name", "") for tool in self.list_tools() if isinstance(tool, dict)]

    def call_tool(self, name: str, arguments: Optional[dict[str, Any]] = None) -> Any:
        self._require_non_empty(name, "name")
        args = arguments or {}
        if not isinstance(args, dict):
            raise PocketMCPValidationError("arguments must be a JSON object")

        result = self._rpc("tools/call", {"name": name, "arguments": args})
        return self._decode_tool_result(result)

    def health(self) -> dict[str, Any]:
        payload = self._request_json("GET", "/health", timeout=min(self.timeout, 10))
        return payload

    def wait_until_healthy(self, timeout_seconds: int = 30, poll_interval_seconds: float = 1.0) -> dict[str, Any]:
        deadline = time.monotonic() + max(1, int(timeout_seconds))
        poll_interval = max(0.1, float(poll_interval_seconds))
        last_error: Optional[Exception] = None

        while time.monotonic() < deadline:
            try:
                return self.health()
            except PocketMCPError as exc:
                last_error = exc
                time.sleep(poll_interval)

        raise PocketMCPConnectionError(
            f"Server did not become healthy within {timeout_seconds}s"
            + (f"; last error: {last_error}" if last_error else "")
        )

    def device_info(self) -> Any:
        return self.call_tool("device_info")

    def get_location(self) -> Any:
        return self.call_tool("get_location")

    def search_contacts(self, query: str, limit: int = 10) -> Any:
        self._require_non_empty(query, "query")
        return self.call_tool("search_contacts", {"query": query, "limit": limit})

    def make_call(self, phone_number: str, contact_name: Optional[str] = None) -> Any:
        self._require_non_empty(phone_number, "phone_number")
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
        self._require_non_empty(app, "app")
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
        self._require_non_empty(contact_name, "contact_name")
        self._require_non_empty(message, "message")
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
        self._require_non_empty(action, "action")
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
        self._require_non_empty(platform, "platform")
        self._require_non_empty(action, "action")
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
        self._require_non_empty(app, "app")
        self._require_non_empty(action, "action")
        args = {"app": app, "action": action, **kwargs}
        return self.call_tool("app_actions", args)

    def notifications(
        self,
        action: str = "list",
        limit: int = 20,
        query: Optional[str] = None,
        index: Optional[int] = None,
    ) -> Any:
        self._require_non_empty(action, "action")
        args: dict[str, Any] = {"action": action, "limit": limit}
        if query is not None:
            args["query"] = query
        if index is not None:
            args["index"] = index
        return self.call_tool("notifications", args)

    def shell(self, command: str, timeout_seconds: int = 10) -> Any:
        self._require_non_empty(command, "command")
        return self.call_tool("shell", {"command": command, "timeout_seconds": timeout_seconds})

    def flashlight(self, action: str = "status", camera_id: Optional[str] = None) -> Any:
        self._require_non_empty(action, "action")
        args: dict[str, Any] = {"action": action}
        if camera_id:
            args["camera_id"] = camera_id
        return self.call_tool("flashlight", args)

    def launch_app(self, package_name: Optional[str] = None, app_name: Optional[str] = None) -> Any:
        if not package_name and not app_name:
            raise PocketMCPValidationError("Provide package_name or app_name")
        args: dict[str, Any] = {}
        if package_name:
            args["package_name"] = package_name
        if app_name:
            args["app_name"] = app_name
        return self.call_tool("launch_app", args)

    def close_app(self, package_name: Optional[str] = None, app_name: Optional[str] = None) -> Any:
        if not package_name and not app_name:
            raise PocketMCPValidationError("Provide package_name or app_name")
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
        self._require_non_empty(action, "action")
        return self.call_tool("global_action", {"action": action})

    def scroll_screen(self, direction: str, distance_ratio: float = 0.55, duration_ms: int = 320) -> Any:
        self._require_non_empty(direction, "direction")
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
        has_text_selector = bool((text or "").strip()) or bool((content_description or "").strip())
        has_coordinates = x is not None or y is not None

        if has_coordinates and (x is None or y is None):
            raise PocketMCPValidationError("Both x and y are required when tapping by coordinates")

        if not has_text_selector and not (x is not None and y is not None):
            raise PocketMCPValidationError("Provide text/content_description or both x and y")

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
        self._require_non_empty(action, "action")
        self._require_non_empty(stream, "stream")
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
        self._require_non_empty(action, "action")
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
        self._require_non_empty(action, "action")
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
        self._require_non_empty(action, "action")
        self._require_non_empty(language_tag, "language_tag")
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
        self._require_non_empty(audio_path, "audio_path")
        self._require_non_empty(language_tag, "language_tag")
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
        self._require_non_empty(whatsapp_type, "whatsapp_type")
        self._require_non_empty(language_tag, "language_tag")
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
        self._require_non_empty(command, "command")
        return self.call_tool("human_command", {"command": command})

    def http_request(
        self,
        url: str,
        method: str = "GET",
        headers: Optional[dict[str, str]] = None,
        body: Optional[str] = None,
        timeout_seconds: int = 20,
    ) -> Any:
        self._require_non_empty(url, "url")
        if not url.startswith(("http://", "https://")):
            raise PocketMCPValidationError("url must start with http:// or https://")
        self._require_non_empty(method, "method")
        args: dict[str, Any] = {"url": url, "method": method, "timeout_seconds": timeout_seconds}
        if headers:
            args["headers"] = headers
        if body is not None:
            args["body"] = body
        return self.call_tool("http_request", args)

    def read_file(self, path: str, max_bytes: Optional[int] = None) -> Any:
        self._require_non_empty(path, "path")
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


def _print_output(value: Any) -> None:
    try:
        print(json.dumps(value, indent=2))
    except TypeError:
        print(str(value))


def _main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="PocketMCP Python client utility")
    parser.add_argument("endpoint", nargs="?", default=None, help="Phone endpoint host/ip or URL")
    parser.add_argument("--discover", action="store_true", help="Discover phone via mDNS")
    parser.add_argument("api_key", nargs="?", default=None, help="Optional API key")
    parser.add_argument("--timeout", type=int, default=30, help="Request timeout seconds")
    parser.add_argument("--max-retries", type=int, default=2, help="HTTP retry count for transient errors")
    parser.add_argument("--wait-healthy", type=int, metavar="SECONDS", help="Wait until /health is ready")
    parser.add_argument("--initialize", action="store_true", help="Call initialize")
    parser.add_argument("--list-tools", action="store_true", help="List available tool schemas")
    parser.add_argument("--call", metavar="TOOL_NAME", help="Call tool by name")
    parser.add_argument(
        "--args",
        default="{}",
        help="JSON object for --call tool arguments (default: {})",
    )

    args = parser.parse_args(argv)

    call_args: dict[str, Any] = {}
    if args.call:
        try:
            parsed_args = json.loads(args.args)
        except json.JSONDecodeError as exc:
            parser.error(f"--args is not valid JSON: {exc}")
            return 2

        if not isinstance(parsed_args, dict):
            parser.error("--args must decode to a JSON object")
            return 2
        call_args = parsed_args

    client = PocketMCPClient(
        args.endpoint,
        api_key=args.api_key,
        timeout=args.timeout,
        max_retries=args.max_retries,
        discover=args.discover
    )

    try:
        emitted = False

        if args.wait_healthy:
            _print_output(client.wait_until_healthy(timeout_seconds=args.wait_healthy))
            emitted = True

        if args.initialize:
            _print_output(client.initialize())
            emitted = True

        if args.list_tools:
            _print_output(client.list_tools())
            emitted = True

        if args.call:
            _print_output(client.call_tool(args.call, call_args))
            emitted = True

        if not emitted:
            _print_output(client.health())

        return 0
    except PocketMCPError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(_main())
