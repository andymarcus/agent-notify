import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { enqueueAndWait } from "./queue.js";

export const VERSION = "1.2.1";
export const MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
const SIGNED_URL_SECONDS = 7 * 24 * 60 * 60;
const GOOGLE_SCOPES = [
  "https://www.googleapis.com/auth/firebase.messaging",
  "https://www.googleapis.com/auth/devstorage.read_write",
].join(" ");

const USAGE = `Agent Notify — send a notification to your Android device

USAGE
  agent-notify --topic <topic> --message <text> [--file <path>]
  agent-notify --topic <topic> --message-file <path|-> [--file <path>]
  agent-notify --topic <topic> --file <path>
  agent-notify configure --service-account <path> --token <token> [options]

OPTIONS
  --topic, -topic             Group the message under this topic
  --message, -message         Plain text or Markdown message body
  --message-file              Read the message from a UTF-8 file; use - for stdin
  --file                      Attach one file (maximum 50 MiB)
  --dry-run                   Validate and print without sending
  -h, --help                  Show help
  -v, --version               Show version

CONFIGURE OPTIONS
  --project-id <id>           Firebase project ID
  --service-account <path>    Firebase Admin service-account JSON
  --token <token>             Android FCM device token
  --storage-bucket <bucket>   Firebase Storage bucket (derived by default)
  --public-key <base64>       Android attachment public key copied from the app

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
  if (values.length === 0 || values.includes("--help") || values.includes("-h")) return { command: "help" };
  if (values.includes("--version") || values.includes("-v")) return { command: "version" };
  if (values[0] === "configure") {
    values.shift();
    const result = {
      command: "configure",
      projectID: takeValue(values, ["--project-id"]),
      serviceAccountPath: takeValue(values, ["--service-account"]),
      deviceToken: takeValue(values, ["--token"]),
      storageBucket: takeValue(values, ["--storage-bucket"]),
      publicKey: takeValue(values, ["--public-key"]),
    };
    if (values.length) throw new CLIError(`Unknown argument: ${values[0]}`);
    return result;
  }

  const topic = takeValue(values, ["--topic", "-topic"]);
  const message = takeValue(values, ["--message", "-message"]);
  const messageFile = takeValue(values, ["--message-file", "-message-file"]);
  const attachmentPath = takeValue(values, ["--file"]);
  const dryRun = takeFlag(values, ["--dry-run"]);
  if (values.length) throw new CLIError(`Unknown argument: ${values[0]}`);
  if (!topic?.trim()) throw new CLIError("A non-empty --topic is required.");
  if (message !== undefined && messageFile !== undefined) throw new CLIError("Provide only one of --message or --message-file.");
  if (message === undefined && messageFile === undefined && attachmentPath === undefined) {
    throw new CLIError("Provide --message, --message-file, or --file.");
  }
  const resolvedAttachmentPath = attachmentPath ? expandHome(attachmentPath) : undefined;
  if (resolvedAttachmentPath) validateAttachmentFile(resolvedAttachmentPath);
  const body = message ?? (messageFile !== undefined ? readFile(messageFile) : `File: ${path.basename(resolvedAttachmentPath)}`);
  if (!body) throw new CLIError("The message cannot be empty.");
  return { command: "send", topic: topic.trim(), message: body, attachmentPath: resolvedAttachmentPath, dryRun };
}

function readMessageFile(filePath) {
  try { return fs.readFileSync(filePath === "-" ? 0 : expandHome(filePath), "utf8"); }
  catch (error) { throw new CLIError(`Could not read message input: ${error.message}`); }
}

function expandHome(value) {
  if (value === "~") return os.homedir();
  if (value.startsWith("~/")) return path.join(os.homedir(), value.slice(2));
  return path.resolve(value);
}

function validateAttachmentFile(filePath) {
  let stats;
  try { stats = fs.statSync(filePath); }
  catch (error) { throw new CLIError(`Could not read attachment at ${filePath}: ${error.message}`); }
  if (!stats.isFile()) throw new CLIError(`Attachment is not a regular file: ${filePath}`);
  if (stats.size > MAX_ATTACHMENT_BYTES) throw new CLIError("Attachment exceeds the 50 MiB limit.");
}

export function defaultConfigPath(environment = process.env) {
  return environment.AGENT_NOTIFY_CONFIG ? expandHome(environment.AGENT_NOTIFY_CONFIG) : path.join(os.homedir(), ".config", "agent-notify", "config.json");
}

export function loadConfig(configPath = defaultConfigPath()) {
  try { return JSON.parse(fs.readFileSync(configPath, "utf8")); }
  catch (error) { throw new CLIError(`Could not load ${configPath}. Run \`agent-notify configure\` first. ${error.message}`); }
}

export function configure(options, configPath = defaultConfigPath()) {
  let existing = {};
  if (fs.existsSync(configPath)) existing = loadConfig(configPath);
  const serviceAccountPath = options.serviceAccountPath ? expandHome(options.serviceAccountPath) : existing.serviceAccountPath;
  let serviceAccount;
  if (serviceAccountPath) serviceAccount = loadServiceAccount(serviceAccountPath);
  const projectID = options.projectID ?? existing.projectID ?? serviceAccount?.project_id;
  const config = {
    projectID,
    serviceAccountPath,
    deviceToken: options.deviceToken ?? existing.deviceToken,
    storageBucket: options.storageBucket ?? existing.storageBucket ?? (projectID ? `${projectID}.firebasestorage.app` : undefined),
    publicKey: options.publicKey ?? existing.publicKey,
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
  try { account = JSON.parse(fs.readFileSync(filePath, "utf8")); }
  catch (error) { throw new CLIError(`Could not read service account at ${filePath}: ${error.message}`); }
  for (const field of ["project_id", "client_email", "private_key", "token_uri"]) {
    if (!account[field]) throw new CLIError(`Service account is missing ${field}.`);
  }
  return account;
}

function encodeJSON(value) { return Buffer.from(JSON.stringify(value)).toString("base64url"); }

export function createJWT(account, now = new Date(), scope = GOOGLE_SCOPES) {
  const issuedAt = Math.floor(now.getTime() / 1000);
  const unsigned = `${encodeJSON({ alg: "RS256", typ: "JWT" })}.${encodeJSON({
    iss: account.client_email, scope, aud: account.token_uri, iat: issuedAt, exp: issuedAt + 3600,
  })}`;
  const signature = crypto.sign("RSA-SHA256", Buffer.from(unsigned), account.private_key).toString("base64url");
  return `${unsigned}.${signature}`;
}

async function responseBody(response, operation) {
  const text = await response.text();
  if (!response.ok) throw new CLIError(`${operation} failed (HTTP ${response.status}): ${text}`);
  try { return JSON.parse(text); }
  catch { throw new CLIError(`${operation} returned malformed JSON.`); }
}

export async function fetchOrThrow(fetch_, url, options, operation) {
  try { return await fetch_(url, options); }
  catch (error) {
    const cause = error.cause;
    const details = [cause?.code, cause?.syscall, cause?.hostname].filter(Boolean).join(" ");
    const wrapped = new CLIError(`${operation} could not connect: ${error.message}${details ? ` (${details})` : ""}`);
    wrapped.code = cause?.code;
    throw wrapped;
  }
}

async function accessToken(account, fetch_, now) {
  const response = await fetchOrThrow(fetch_, account.token_uri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: createJWT(account, now) }),
  }, "OAuth token request");
  return (await responseBody(response, "OAuth token request")).access_token;
}

function mimeType(fileName) {
  const types = { ".pdf":"application/pdf", ".png":"image/png", ".jpg":"image/jpeg", ".jpeg":"image/jpeg", ".gif":"image/gif", ".webp":"image/webp", ".txt":"text/plain", ".md":"text/markdown", ".json":"application/json", ".csv":"text/csv", ".zip":"application/zip" };
  return types[path.extname(fileName).toLowerCase()] ?? "application/octet-stream";
}

export function encryptAttachment(filePath, publicKeyBase64) {
  validateAttachmentFile(filePath);
  if (!publicKeyBase64) throw new CLIError("File sending is not paired. Copy the file key from Android Settings, then run `agent-notify configure --public-key <key>`." );
  const plaintext = fs.readFileSync(filePath);
  const key = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
  let wrappedKey;
  try {
    const publicKey = crypto.createPublicKey({
      key: Buffer.from(publicKeyBase64.replace(/\s/g, ""), "base64"),
      format: "der",
      type: "spki",
    });
    wrappedKey = wrapKeyForAndroid(publicKey, key);
  } catch (error) { throw new CLIError(`Invalid Android file key: ${error.message}`); }
  return {
    ciphertext,
    key: wrappedKey.toString("base64"),
    iv: iv.toString("base64"),
    sha256: crypto.createHash("sha256").update(plaintext).digest("hex"),
    size: plaintext.length,
  };
}

// Android Keystore uses SHA-1 for OAEP's MGF1 digest unless a key created on
// API 35+ opts into another digest. Node/OpenSSL does not expose a separate
// MGF1 selector, so encode OAEP explicitly and perform only the raw RSA step.
export function wrapKeyForAndroid(publicKey, message, randomBytes = crypto.randomBytes) {
  const details = publicKey.asymmetricKeyDetails;
  const modulusBytes = Math.ceil((details?.modulusLength ?? 2048) / 8);
  const hashLength = 32;
  if (message.length > modulusBytes - (2 * hashLength) - 2) throw new CLIError("Encryption key is too large for the Android RSA key.");
  const labelHash = crypto.createHash("sha256").update(Buffer.alloc(0)).digest();
  const padding = Buffer.alloc(modulusBytes - message.length - (2 * hashLength) - 2);
  const dataBlock = Buffer.concat([labelHash, padding, Buffer.from([1]), message]);
  const seed = randomBytes(hashLength);
  const maskedDataBlock = xorBuffers(dataBlock, mgf1(seed, dataBlock.length, "sha1"));
  const maskedSeed = xorBuffers(seed, mgf1(maskedDataBlock, hashLength, "sha1"));
  const encoded = Buffer.concat([Buffer.from([0]), maskedSeed, maskedDataBlock]);
  return crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_NO_PADDING }, encoded);
}

function mgf1(seed, length, algorithm) {
  const chunks = [];
  for (let counter = 0; Buffer.concat(chunks).length < length; counter += 1) {
    const value = Buffer.alloc(4);
    value.writeUInt32BE(counter);
    chunks.push(crypto.createHash(algorithm).update(seed).update(value).digest());
  }
  return Buffer.concat(chunks).subarray(0, length);
}

function xorBuffers(left, right) {
  const output = Buffer.alloc(left.length);
  for (let index = 0; index < left.length; index += 1) output[index] = left[index] ^ right[index];
  return output;
}

function rfc3986(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`);
}

export function createSignedURL(account, bucket, objectName, now = new Date()) {
  const timestamp = now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
  const date = timestamp.slice(0, 8);
  const credential = `${account.client_email}/${date}/auto/storage/goog4_request`;
  const parameters = {
    "X-Goog-Algorithm": "GOOG4-RSA-SHA256", "X-Goog-Credential": credential,
    "X-Goog-Date": timestamp, "X-Goog-Expires": String(SIGNED_URL_SECONDS), "X-Goog-SignedHeaders": "host",
  };
  const query = Object.entries(parameters).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join("&");
  const uri = `/${rfc3986(bucket)}/${objectName.split("/").map(rfc3986).join("/")}`;
  const canonical = `GET\n${uri}\n${query}\nhost:storage.googleapis.com\n\nhost\nUNSIGNED-PAYLOAD`;
  const stringToSign = `GOOG4-RSA-SHA256\n${timestamp}\n${date}/auto/storage/goog4_request\n${crypto.createHash("sha256").update(canonical).digest("hex")}`;
  const signature = crypto.sign("RSA-SHA256", Buffer.from(stringToSign), account.private_key).toString("hex");
  return `https://storage.googleapis.com${uri}?${query}&X-Goog-Signature=${signature}`;
}

async function uploadAttachment(config, account, token, filePath, fetch_, now) {
  if (!config.storageBucket) throw new CLIError("File sending requires a configured Firebase Storage bucket.");
  const encrypted = encryptAttachment(filePath, config.publicKey);
  const id = crypto.randomUUID();
  const objectName = `agent-notify/${id}.bin`;
  const uploadURL = `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(config.storageBucket)}/o?uploadType=media&name=${encodeURIComponent(objectName)}`;
  const response = await fetchOrThrow(fetch_, uploadURL, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/octet-stream" }, body: encrypted.ciphertext,
  }, "Attachment upload");
  await responseBody(response, "Attachment upload");
  return {
    id, name: path.basename(filePath), mime: mimeType(filePath), size: encrypted.size,
    url: createSignedURL(account, config.storageBucket, objectName, now), key: encrypted.key, iv: encrypted.iv, sha256: encrypted.sha256,
  };
}

export async function sendMessage(config, topic, body, fetch_ = fetch, now = new Date(), attachmentPath) {
  validateConfig(config);
  const account = loadServiceAccount(config.serviceAccountPath);
  if (account.project_id !== config.projectID) throw new CLIError(`Configured project ${config.projectID} does not match service account project ${account.project_id}.`);
  const token = await accessToken(account, fetch_, now);
  const attachment = attachmentPath ? await uploadAttachment(config, account, token, attachmentPath, fetch_, now) : undefined;
  const data = { topic, body, sent_at: String(now.getTime()) };
  if (attachment) Object.assign(data, {
    attachment_id: attachment.id, attachment_name: attachment.name, attachment_mime: attachment.mime,
      attachment_size: String(attachment.size), attachment_url: attachment.url, attachment_key: attachment.key,
      attachment_key_alg: "RSA-OAEP-256-MGF1-SHA1", attachment_iv: attachment.iv, attachment_sha256: attachment.sha256,
  });
  if (Buffer.byteLength(JSON.stringify(data), "utf8") > 4000) throw new CLIError("Message metadata is too large for Firebase Cloud Messaging.");
  const payload = { message: { token: config.deviceToken, data, android: { priority: "high", ttl: "604800s" } } };
  const response = await fetchOrThrow(fetch_, `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(config.projectID)}/messages:send`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json; charset=utf-8" }, body: JSON.stringify(payload),
  }, "FCM send");
  return responseBody(response, "FCM send");
}

export async function run(command, dependencies = {}) {
  const output = dependencies.output ?? console.log;
  const configPath = dependencies.configPath ?? defaultConfigPath();
  switch (command.command) {
    case "help": output(USAGE); return;
    case "version": output(VERSION); return;
    case "configure": {
      const config = configure(command, configPath);
      output(`Configured Firebase project ${config.projectID}.`); output(`Config saved to ${configPath}`); return;
    }
    case "send":
      if (command.dryRun) {
        output(`Topic: ${command.topic}`); output(command.message);
        if (command.attachmentPath) output(`File: ${command.attachmentPath}`);
        return;
      }
      try {
        const result = await (dependencies.sendMessage ?? sendMessage)(loadConfig(configPath), command.topic, command.message, dependencies.fetch, dependencies.now, command.attachmentPath);
        output(`Sent: ${result.name}`);
      } catch (error) {
        if (!isConnectivityError(error) || dependencies.disableQueue) throw error;
        const result = await (dependencies.enqueueAndWait ?? enqueueAndWait)(command.topic, command.message, { attachmentPath: command.attachmentPath });
        output(`Sent: ${result.name} (via background sender)`);
      }
  }
}

export function isConnectivityError(error) {
  return new Set(["ENOTFOUND", "EAI_AGAIN", "ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "ENETDOWN", "ENETUNREACH", "EHOSTUNREACH"]).has(error?.code);
}

export async function main(arguments_) {
  try { await run(parseArguments(arguments_)); }
  catch (error) { console.error(`agent-notify: ${error.message}`); process.exitCode = 1; }
}
