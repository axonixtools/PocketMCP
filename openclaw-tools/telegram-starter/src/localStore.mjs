import path from "node:path";
import { appendFile, mkdir } from "node:fs/promises";

async function appendNdjson(filePath, entry) {
  const line = `${JSON.stringify(entry)}\n`;
  await appendFile(filePath, line, "utf8");
}

export async function ensureDataDir(dataDir) {
  await mkdir(dataDir, { recursive: true });
}

export async function writeAuditLog(dataDir, event) {
  const record = {
    timestamp: new Date().toISOString(),
    ...event
  };
  await ensureDataDir(dataDir);
  await appendNdjson(path.join(dataDir, "audit.ndjson"), record);

  const level = record.level ? String(record.level).toUpperCase() : "INFO";
  const requestId = record.request_id ? ` request_id=${record.request_id}` : "";
  process.stderr.write(`[telegram-starter] ${level} ${record.event || "log"}${requestId}\n`);
}

export async function queueOutbox(dataDir, item) {
  const record = {
    queued_at: new Date().toISOString(),
    ...item
  };
  await ensureDataDir(dataDir);
  await appendNdjson(path.join(dataDir, "outbox.ndjson"), record);
}
