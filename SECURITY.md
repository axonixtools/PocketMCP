# Security Policy

PocketMCP turns an Android phone into a local [MCP](https://modelcontextprotocol.io) server, so its attack surface is mostly "what happens over your LAN." This document covers supported versions, how to report a vulnerability, and the security model you should understand before deploying the server.

## Supported Versions

PocketMCP is in active pre-release development. Only the **latest beta build** receives fixes.

| Version | Supported |
| ------- | --------- |
| 1.0.0-beta.x (latest) | :white_check_mark: |
| Older beta builds | :x: |

## Reporting a Vulnerability

Please report vulnerabilities **privately** — do not open a public issue.

1. Use GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability) (repository **Security** tab → *Report a vulnerability*).
2. Include the affected build (`versionName` shown in the app), reproduction steps, impact, and a suggested fix if you have one.

What to expect:

- Acknowledgement within a few days.
- A fix or mitigation plan before public disclosure (coordinated disclosure).
- Credit in the release notes if you would like it.

## Security Model

### Authentication

- Every endpoint except `GET /` and `GET /health` requires the API key: `X-API-Key` header, `Authorization: Bearer <key>`, or `?key=` query parameter (for WebSocket/SSE handshakes that cannot set headers).
- The key is generated in the app, stored encrypted via Android Keystore-backed `EncryptedSharedPreferences`, and can be rotated at any time.
- Failed authentication attempts are throttled per client host (HTTP 429 after 10 failures per minute).

### Network posture

- The server is designed for trusted local networks. For remote access, put the phone and the client on a private overlay network (e.g., Tailscale) — **never expose the server port to the public internet**.
- `http_request` blocks loopback URLs.

### Tool-level guardrails

- The shell tool applies a best-effort blocklist (destructive commands), a max command length, and time/output limits. This is a **speed bump, not a sandbox**.
- File reads are restricted to app storage and external storage roots.
- Messaging and automation tools verify that the expected app actually reached the foreground before acting (screen-state guards), reducing wrong-recipient risk.
- Accessibility and Notification access are high-trust capabilities: they are granted manually by the user in system settings and can be revoked at any time.

### Update mechanism

- The app ships with **no hardcoded update URL**. You point it at your own JSON manifest
  (`{"versionName", "versionCode", "apkUrl", "notes"}`) in Settings → App Updates.
- Treat the update URL as a trust input: whoever serves the manifest decides which APK is offered. Self-host it on your own machine or private network, and only configure sources you control.
- APK installs go through the Android package installer, so normal Android signature rules apply (updates must be signed with the same key).
- Debug builds in this repository are **debug-signed**. If you publish builds, sign your own releases and keep the keystore private.

## Out of Scope

- Device-owner/MDM policy interference (for example, Samsung Auto Blocker blocking activation of sideloaded accessibility services — that is an OS policy, not a vulnerability).
- Rooted-device scenarios where an attacker can already read app data or inject input.
- Physical-access attacks (someone holding an unlocked phone).
- Denial of service against the phone itself (for example, flooding its LAN port).

## License

PocketMCP is released under the [MIT License](LICENSE).
