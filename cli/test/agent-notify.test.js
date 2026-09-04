import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { configure, fetchOrThrow, isConnectivityError, loadConfig, parseArguments, run } from "../src/agent-notify.js";

test("parses documented long flags", () => {
  assert.deepEqual(
    parseArguments(["--topic", "daily brief", "--message", "**Done**"]),
    { command: "send", topic: "daily brief", message: "**Done**", dryRun: false },
  );
});

test("parses single-dash aliases and dry run", () => {
  assert.deepEqual(
    parseArguments(["-topic", "build", "-message", "Passed", "--dry-run"]),
    { command: "send", topic: "build", message: "Passed", dryRun: true },
  );
});

test("reads a message file", () => {
  assert.deepEqual(
    parseArguments(["--topic", "review", "--message-file", "note.md"], (file) => {
      assert.equal(file, "note.md");
      return "# Ready";
    }),
    { command: "send", topic: "review", message: "# Ready", dryRun: false },
  );
});

test("rejects missing topic and ambiguous message sources", () => {
  assert.throws(() => parseArguments(["--message", "hello"]), /topic/);
  assert.throws(
    () => parseArguments(["--topic", "x", "--message", "hello", "--message-file", "x.md"]),
    /exactly one/,
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
  });
  assert.equal(fs.statSync(configPath).mode & 0o777, 0o600);
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
