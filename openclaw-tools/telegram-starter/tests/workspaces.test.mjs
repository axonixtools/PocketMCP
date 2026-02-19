import test from "node:test";
import assert from "node:assert/strict";
import { authenticateWorkspace, checkTelegramPermissions, hashToken } from "../src/workspaces.mjs";
import { validateTelegramSendMessageInput } from "../src/schemas.mjs";

test("validateTelegramSendMessageInput sets local-first defaults", () => {
  const parsed = validateTelegramSendMessageInput({
    workspace_id: "demo",
    workspace_token: "secret",
    chat_id: "123",
    text: "hello"
  });

  assert.equal(parsed.ok, true);
  assert.equal(parsed.value.dry_run, true);
  assert.equal(parsed.value.parse_mode, "None");
});

test("authenticateWorkspace accepts matching hash and rejects mismatch", () => {
  const registry = {
    workspaces: {
      demo: {
        token_sha256: hashToken("secret")
      }
    }
  };

  const workspace = authenticateWorkspace({
    registry,
    workspaceId: "demo",
    workspaceToken: "secret"
  });
  assert.equal(Boolean(workspace), true);

  assert.throws(() => authenticateWorkspace({
    registry,
    workspaceId: "demo",
    workspaceToken: "nope"
  }));
});

test("checkTelegramPermissions enforces allowed chat ids and max text length", () => {
  const workspace = {
    permissions: {
      telegram_send_message: {
        allowed_chat_ids: ["123"],
        max_text_length: 5
      }
    }
  };

  assert.doesNotThrow(() => checkTelegramPermissions({
    workspace,
    chatId: "123",
    text: "hello"
  }));

  assert.throws(() => checkTelegramPermissions({
    workspace,
    chatId: "999",
    text: "hello"
  }));

  assert.throws(() => checkTelegramPermissions({
    workspace,
    chatId: "123",
    text: "hello world"
  }));
});
