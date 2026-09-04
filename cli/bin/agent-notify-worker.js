#!/usr/bin/env node

import fs from "node:fs";

import { defaultConfigPath, isConnectivityError, loadConfig, sendMessage } from "../src/agent-notify.js";
import { ensureQueueDirectory, listRequests, writeResult } from "../src/queue.js";

const directory = ensureQueueDirectory();
let processing = false;
console.log(`agent-notify worker started; queue=${directory}`);

async function processQueue() {
  if (processing) return;
  processing = true;
  try {
    const requests = listRequests(directory);
    if (requests.length) console.log(`agent-notify worker found ${requests.length} queued request(s)`);
    for (const requestPath of requests) {
      let request;
      try {
        request = JSON.parse(fs.readFileSync(requestPath, "utf8"));
        if (!request.id || typeof request.topic !== "string" || typeof request.body !== "string") {
          throw new Error("Invalid queue request.");
        }

        let result;
        let lastError;
        for (let attempt = 1; attempt <= 2; attempt += 1) {
          try {
            result = await sendMessage(loadConfig(defaultConfigPath()), request.topic, request.body, fetch, new Date(), request.attachmentPath);
            break;
          } catch (error) {
            lastError = error;
            if (!isConnectivityError(error) || attempt === 2) break;
            await new Promise((resolve) => setTimeout(resolve, 1_000));
          }
        }
        if (!result) throw lastError;
        writeResult(requestPath, { ok: true, name: result.name });
      } catch (error) {
        console.error(`agent-notify worker failed ${requestPath}: ${error?.stack ?? error}`);
        writeResult(requestPath, {
          ok: false,
          error: error?.message ?? String(error),
          code: error?.code,
        });
      } finally {
        try { fs.unlinkSync(requestPath); } catch {}
      }
    }
  } catch (error) {
    console.error(`agent-notify worker queue scan failed: ${error?.stack ?? error}`);
  } finally {
    processing = false;
  }
}

await processQueue();
setInterval(processQueue, 250);
