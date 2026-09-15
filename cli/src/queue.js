import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const DEFAULT_TIMEOUT_MS = 30_000;

export function queueDirectory() {
  const uid = typeof process.getuid === "function" ? process.getuid() : os.userInfo().uid;
  return path.join("/tmp", `agent-notify-${uid}`);
}

export function ensureQueueDirectory(directory = queueDirectory()) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  fs.chmodSync(directory, 0o700);
  return directory;
}

export async function enqueueAndWait(topic, body, options = {}) {
  const directory = ensureQueueDirectory(options.directory);
  const id = `${Date.now()}-${crypto.randomUUID()}`;
  const requestPath = path.join(directory, `${id}.request.json`);
  const temporaryPath = `${requestPath}.tmp`;
  const resultPath = path.join(directory, `${id}.result.json`);
  let queuedAttachmentPath;
  if (options.attachmentPath) {
    queuedAttachmentPath = path.join(directory, `${id}-${path.basename(options.attachmentPath)}`);
    fs.copyFileSync(options.attachmentPath, queuedAttachmentPath);
    fs.chmodSync(queuedAttachmentPath, 0o600);
  }
  fs.writeFileSync(
    temporaryPath,
    `${JSON.stringify({
      id,
      topic,
      body,
      attachmentPath: queuedAttachmentPath,
      deleteAttachmentAfterSend: Boolean(queuedAttachmentPath),
      createdAt: Date.now(),
    })}\n`,
    { mode: 0o600 },
  );
  fs.renameSync(temporaryPath, requestPath);

  const deadline = Date.now() + (options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  while (Date.now() < deadline) {
    if (fs.existsSync(resultPath)) {
      const result = JSON.parse(fs.readFileSync(resultPath, "utf8"));
      fs.unlinkSync(resultPath);
      if (!result.ok) {
        const error = new Error(`Background sender failed: ${result.error}`);
        error.code = result.code;
        throw error;
      }
      return result;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Background sender did not respond within 30 seconds. Check ~/.config/agent-notify/worker.log.");
}

export function listRequests(directory = queueDirectory()) {
  ensureQueueDirectory(directory);
  return fs.readdirSync(directory)
    .filter((name) => name.endsWith(".request.json"))
    .sort()
    .map((name) => path.join(directory, name));
}

export function writeResult(requestPath, result) {
  const resultPath = requestPath.replace(/\.request\.json$/, ".result.json");
  const temporaryPath = `${resultPath}.tmp`;
  fs.writeFileSync(temporaryPath, `${JSON.stringify(result)}\n`, { mode: 0o600 });
  fs.renameSync(temporaryPath, resultPath);
}
