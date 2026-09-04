import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { configure, createSignedURL, encryptAttachment, fetchOrThrow, isConnectivityError, loadConfig, parseArguments, run } from "../src/agent-notify.js";

test("parses documented long flags", () => {
  assert.deepEqual(
    parseArguments(["--topic", "daily brief", "--message", "**Done**"]),
    { command: "send", topic: "daily brief", message: "**Done**", attachmentPath: undefined, dryRun: false },
  );
});

test("parses single-dash aliases and dry run", () => {
  assert.deepEqual(
    parseArguments(["-topic", "build", "-message", "Passed", "--dry-run"]),
    { command: "send", topic: "build", message: "Passed", attachmentPath: undefined, dryRun: true },
  );
});

test("reads a message file", () => {
  assert.deepEqual(
    parseArguments(["--topic", "review", "--message-file", "note.md"], (file) => {
      assert.equal(file, "note.md");
      return "# Ready";
    }),
    { command: "send", topic: "review", message: "# Ready", attachmentPath: undefined, dryRun: false },
  );
});

test("rejects missing topic and ambiguous message sources", () => {
  assert.throws(() => parseArguments(["--message", "hello"]), /topic/);
  assert.throws(
    () => parseArguments(["--topic", "x", "--message", "hello", "--message-file", "x.md"]),
    /only one/,
  );
});

test("configuration derives the project ID and uses owner-only permissions", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "agent-notify-test-"));
  const serviceAccountPath = path.join(directory, "service.json");
  const configPath = path.join(directory, "config", "config.json");
  fs.writeFileSync(serviceAccountPath, JSON.stringify({
    project_id: "example-project",
    client_email: "sender@example.invalid",
    private_key: "not-used-by-this-test",
    token_uri: "https://example.invalid/token",
  }));

  configure({ serviceAccountPath, deviceToken: "device-token" }, configPath);
  assert.deepEqual(loadConfig(configPath), {
    projectID: "example-project",
    serviceAccountPath,
    deviceToken: "device-token",
    storageBucket: "example-project.firebasestorage.app",
  });
  assert.equal(fs.statSync(configPath).mode & 0o777, 0o600);
});

test("parses a file-only notification", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "agent-notify-file-"));
  const file = path.join(directory, "report.pdf");
  fs.writeFileSync(file, "report");
  assert.deepEqual(parseArguments(["--topic", "reports", "--file", file]), {
    command: "send",
    topic: "reports",
    message: "File: report.pdf",
    attachmentPath: file,
    dryRun: false,
  });
});

test("encrypts attachments for the Android RSA key", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "agent-notify-crypto-"));
  const file = path.join(directory, "hello.txt");
  fs.writeFileSync(file, "hello attachment");
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).toString("base64");
  const encrypted = encryptAttachment(file, publicKeyBase64);
  const encodedKey = crypto.privateDecrypt(
    { key: privateKey, padding: crypto.constants.RSA_NO_PADDING },
    Buffer.from(encrypted.key, "base64"),
  );
  const key = decodeAndroidOaep(encodedKey);
  const bytes = encrypted.ciphertext.subarray(0, -16);
  const tag = encrypted.ciphertext.subarray(-16);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(encrypted.iv, "base64"));
  decipher.setAuthTag(tag);
  assert.equal(Buffer.concat([decipher.update(bytes), decipher.final()]).toString(), "hello attachment");
});

function decodeAndroidOaep(encoded) {
  const hashLength = 32;
  assert.equal(encoded[0], 0);
  const maskedSeed = encoded.subarray(1, 1 + hashLength);
  const maskedData = encoded.subarray(1 + hashLength);
  const seed = xor(maskedSeed, mgf(maskedData, hashLength));
  const data = xor(maskedData, mgf(seed, maskedData.length));
  const expectedLabel = crypto.createHash("sha256").update(Buffer.alloc(0)).digest();
  assert.deepEqual(data.subarray(0, hashLength), expectedLabel);
  const separator = data.indexOf(1, hashLength);
  assert.ok(separator > hashLength);
  assert.ok(data.subarray(hashLength, separator).every((byte) => byte === 0));
  return data.subarray(separator + 1);
}

function mgf(seed, length) {
  const chunks = [];
  for (let counter = 0; Buffer.concat(chunks).length < length; counter += 1) {
    const value = Buffer.alloc(4);
    value.writeUInt32BE(counter);
    chunks.push(crypto.createHash("sha1").update(seed).update(value).digest());
  }
  return Buffer.concat(chunks).subarray(0, length);
}

function xor(left, right) {
  return Buffer.from(left.map((byte, index) => byte ^ right[index]));
}

test("creates a seven-day V4 signed URL", () => {
  const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const url = createSignedURL({
    client_email: "sender@example.invalid",
    private_key: privateKey.export({ format: "pem", type: "pkcs8" }),
  }, "bucket.example", "agent-notify/id.bin", new Date("2026-09-04T01:02:03Z"));
  assert.match(url, /^https:\/\/storage\.googleapis\.com\/bucket\.example\/agent-notify\/id\.bin\?/);
  assert.match(url, /X-Goog-Expires=604800/);
  assert.match(url, /X-Goog-Signature=/);
});

test("network failures expose the underlying DNS cause", async () => {
  const networkError = new TypeError("fetch failed", {
    cause: Object.assign(new Error("not found"), {
      code: "ENOTFOUND",
      syscall: "getaddrinfo",
      hostname: "oauth2.googleapis.com",
    }),
  });
  await assert.rejects(
    fetchOrThrow(async () => { throw networkError; }, "https://example.invalid", {}, "OAuth token request"),
    /OAuth token request could not connect: fetch failed \(ENOTFOUND getaddrinfo oauth2\.googleapis\.com\)/,
  );
});

test("network failures fall back to the background sender", async () => {
  const output = [];
  const configPath = path.join(os.tmpdir(), `agent-notify-config-${Date.now()}.json`);
  fs.writeFileSync(configPath, JSON.stringify({
    projectID: "unused",
    serviceAccountPath: "/missing",
    deviceToken: "unused",
  }));
  const error = Object.assign(new Error("blocked"), { code: "ENOTFOUND" });
  await run(
    { command: "send", topic: "test", message: "hello", dryRun: false },
    {
      configPath,
      sendMessage: async () => { throw error; },
      enqueueAndWait: async (topic, message) => {
        assert.equal(topic, "test");
        assert.equal(message, "hello");
        return { name: "projects/test/messages/123" };
      },
      output: (line) => output.push(line),
    },
  );
  assert.match(output[0], /via background sender/);
});

test("recognizes sandbox connectivity errors", () => {
  assert.equal(isConnectivityError({ code: "ENOTFOUND" }), true);
  assert.equal(isConnectivityError({ code: "EACCES" }), false);
});
