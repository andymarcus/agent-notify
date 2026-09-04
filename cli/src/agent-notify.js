import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { enqueueAndWait } from "./queue.js";

export const VERSION = "1.1.0";

const USAGE = `Agent Notify — send a notification to your Android device

USAGE
  agent-notify --topic <topic> --message <text>
  agent-notify --topic <topic> --message-file <path|->
  agent-notify configure --service-account <path> --token <token> [--project-id <id>]

OPTIONS
  --topic, -topic             Group the message under this topic
  --message, -message         Plain text or Markdown message body
  --message-file              Read the message from a UTF-8 file; use - for stdin
  --dry-run                   Validate and print without sending
  -h, --help                  Show help
  -v, --version               Show version

CONFIGURATION
  Defaults to ~/.config/agent-notify/config.json.
  Set AGENT_NOTIFY_CONFIG to use another path.
  Re-running configure updates only the supplied values.`;

export class CLIError extends Error {}

function takeValue(values, names) {
  for (const name of names) {
    const index = values.indexOf(name);
    if (index === -1) continue;
    if (index + 1 >= values.length) throw new CLIError(`Missing value after ${name}.`);
    const result = values[index + 1];
    values.splice(index, 2);
    return result;
  }
  return undefined;
}

function takeFlag(values, names) {
  for (const name of names) {
    const index = values.indexOf(name);
    if (index === -1) continue;
    values.splice(index, 1);
    return true;
  }
  return false;
}

export function parseArguments(arguments_, readFile = readMessageFile) {
  const values = [...arguments_];
  if (values.length === 0 || values.includes("--help") || values.includes("-h")) {
    return { command: "help" };
  }
  if (values.includes("--version") || values.includes("-v")) {
    return { command: "version" };
  }
  if (values[0] === "configure") {
    values.shift();
    const result = {
      command: "configure",
      projectID: takeValue(values, ["--project-id"]),
      serviceAccountPath: takeValue(values, ["--service-account"]),
      deviceToken: takeValue(values, ["--token"]),
    };
    if (values.length) throw new CLIError(`Unknown argument: ${values[0]}`);
    return result;
  }

  const topic = takeValue(values, ["--topic", "-topic"]);
  const message = takeValue(values, ["--message", "-message"]);
  const messageFile = takeValue(values, ["--message-file", "-message-file"]);
  const dryRun = takeFlag(values, ["--dry-run"]);
  if (values.length) throw new CLIError(`Unknown argument: ${values[0]}`);
  if (!topic?.trim()) throw new CLIError("A non-empty --topic is required.");
  if ((message === undefined) === (messageFile === undefined)) {
    throw new CLIError("Provide exactly one of --message or --message-file.");
  }
  const body = message ?? readFile(messageFile);
  if (!body) throw new CLIError("The message cannot be empty.");
  return { command: "send", topic: topic.trim(), message: body, dryRun };
}

function readMessageFile(filePath) {
  try {
    return fs.readFileSync(filePath === "-" ? 0 : expandHome(filePath), "utf8");
  } catch (error) {
    throw new CLIError(`Could not read message input: ${error.message}`);
  }
}

function expandHome(value) {
  if (value === "~") return os.homedir();
  if (value.startsWith("~/")) return path.join(os.homedir(), value.slice(2));
  return path.resolve(value);
}

export function defaultConfigPath(environment = process.env) {
  return environment.AGENT_NOTIFY_CONFIG
    ? expandHome(environment.AGENT_NOTIFY_CONFIG)
    : path.join(os.homedir(), ".config", "agent-notify", "config.json");
}

export function loadConfig(configPath = defaultConfigPath()) {
  try {
    return JSON.parse(fs.readFileSync(configPath, "utf8"));
  } catch (error) {
    throw new CLIError(`Could not load ${configPath}. Run \`agent-notify configure\` first. ${error.message}`);
  }
}

export function configure(options, configPath = defaultConfigPath()) {
  let existing = {};
  if (fs.existsSync(configPath)) existing = loadConfig(configPath);

  const serviceAccountPath = options.serviceAccountPath
    ? expandHome(options.serviceAccountPath)
    : existing.serviceAccountPath;
  let serviceAccount;
  if (serviceAccountPath) serviceAccount = loadServiceAccount(serviceAccountPath);

  const config = {
    projectID: options.projectID ?? existing.projectID ?? serviceAccount?.project_id,
    serviceAccountPath,
    deviceToken: options.deviceToken ?? existing.deviceToken,
  };
  validateConfig(config);

  fs.mkdirSync(path.dirname(configPath), { recursive: true, mode: 0o700 });
  const temporaryPath = `${configPath}.tmp`;
  fs.writeFileSync(temporaryPath, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(temporaryPath, configPath);
  fs.chmodSync(configPath, 0o600);
  return config;
}

function validateConfig(config) {
  if (!config.projectID || !config.serviceAccountPath || !config.deviceToken) {
    throw new CLIError("Configuration requires a service account and device token. The project ID is read from the service account when omitted.");
  }
}

function loadServiceAccount(filePath) {
  let account;
  try {
    account = JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (error) {
    throw new CLIError(`Could not read service account at ${filePath}: ${error.message}`);
  }
  for (const field of ["project_id", "client_email", "private_key", "token_uri"]) {
    if (!account[field]) throw new CLIError(`Service account is missing ${field}.`);
  }
  return account;
}

function encodeJSON(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

export function createJWT(account, now = new Date()) {
  const issuedAt = Math.floor(now.getTime() / 1000);
  const unsigned = `${encodeJSON({ alg: "RS256", typ: "JWT" })}.${encodeJSON({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: account.token_uri,
    iat: issuedAt,
    exp: issuedAt + 3600,
  })}`;
  const signature = crypto.sign("RSA-SHA256", Buffer.from(unsigned), account.private_key).toString("base64url");
  return `${unsigned}.${signature}`;
}

async function responseBody(response, operation) {
  const text = await response.text();
  if (!response.ok) throw new CLIError(`${operation} failed (HTTP ${response.status}): ${text}`);
  try {
    return JSON.parse(text);
  } catch {
    throw new CLIError(`${operation} returned malformed JSON.`);
  }
}

export async function fetchOrThrow(fetch_, url, options, operation) {
  try {
    return await fetch_(url, options);
  } catch (error) {
    const cause = error.cause;
    const details = [cause?.code, cause?.syscall, cause?.hostname].filter(Boolean).join(" ");
    const suffix = details ? ` (${details})` : "";
    const wrapped = new CLIError(`${operation} could not connect: ${error.message}${suffix}`);
    wrapped.code = cause?.code;
    throw wrapped;
  }
}

export async function sendMessage(config, topic, body, fetch_ = fetch, now = new Date()) {
  validateConfig(config);
  const account = loadServiceAccount(config.serviceAccountPath);
  if (account.project_id !== config.projectID) {
    throw new CLIError(`Configured project ${config.projectID} does not match service account project ${account.project_id}.`);
  }

  const oauthResponse = await fetchOrThrow(fetch_, account.token_uri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: createJWT(account, now),
    }),
  }, "OAuth token request");
  const oauth = await responseBody(oauthResponse, "OAuth token request");

  const payload = {
    message: {
      token: config.deviceToken,
      data: {
        topic,
        body,
        sent_at: String(now.getTime()),
      },
      android: { priority: "high", ttl: "86400s" },
    },
  };
  if (Buffer.byteLength(JSON.stringify(payload.message.data), "utf8") > 4000) {
    throw new CLIError("Message is too large for Firebase Cloud Messaging (maximum data payload is approximately 4 KB)." );
  }

  const fcmResponse = await fetchOrThrow(
    fetch_,
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(config.projectID)}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${oauth.access_token}`,
        "content-type": "application/json; charset=utf-8",
      },
      body: JSON.stringify(payload),
    },
    "FCM send",
  );
  return responseBody(fcmResponse, "FCM send");
}

export async function run(command, dependencies = {}) {
  const output = dependencies.output ?? console.log;
  const configPath = dependencies.configPath ?? defaultConfigPath();
  switch (command.command) {
    case "help":
      output(USAGE);
      return;
    case "version":
      output(VERSION);
      return;
    case "configure": {
      const config = configure(command, configPath);
      output(`Configured Firebase project ${config.projectID}.`);
      output(`Config saved to ${configPath}`);
      return;
    }
    case "send":
      if (command.dryRun) {
        output(`Topic: ${command.topic}`);
        output(command.message);
        return;
      }
      try {
        const result = await (dependencies.sendMessage ?? sendMessage)(
          loadConfig(configPath),
          command.topic,
          command.message,
          dependencies.fetch,
          dependencies.now,
        );
        output(`Sent: ${result.name}`);
      } catch (error) {
        if (!isConnectivityError(error) || dependencies.disableQueue) throw error;
        const result = await (dependencies.enqueueAndWait ?? enqueueAndWait)(command.topic, command.message);
        output(`Sent: ${result.name} (via background sender)`);
      }
  }
}

export function isConnectivityError(error) {
  return new Set([
    "ENOTFOUND",
    "EAI_AGAIN",
    "ECONNREFUSED",
    "ECONNRESET",
    "ETIMEDOUT",
    "ENETDOWN",
    "ENETUNREACH",
    "EHOSTUNREACH",
  ]).has(error?.code);
}

export async function main(arguments_) {
  try {
    await run(parseArguments(arguments_));
  } catch (error) {
    console.error(`agent-notify: ${error.message}`);
    process.exitCode = 1;
  }
}
