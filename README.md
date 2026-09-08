# Agent Notify

Agent Notify sends topic-grouped text, Markdown, and encrypted file attachments from a macOS CLI to a single Android phone. It uses Firebase Cloud Messaging (FCM) for notifications and Firebase Storage for temporary encrypted attachment storage. There is no custom application server or user-account system.

## Features

### macOS CLI

- Sends plain text and Markdown.
- Groups messages with a required topic.
- Attaches one file up to 50 MiB.
- Encrypts attachments before they leave the Mac.
- Supports stdin and message files for multiline Markdown.
- Falls back to a macOS background sender when a scheduled task cannot reach Firebase directly.

### Android app

- Displays system notifications for incoming messages.
- Keeps message history in a private SQLite database.
- Filters messages by topic.
- Tracks read and unread messages.
- Expands and collapses long messages.
- Copies a message with a long press.
- Deletes a message after a right swipe and confirmation.
- Marks a message unread with a left swipe.
- Downloads, verifies, stores, and opens encrypted attachments.

## Repository layout

- `android/` — Kotlin and Jetpack Compose Android receiver.
- `cli/` — dependency-free Node.js macOS sender.
- `cli/skills/agent-notify/` — packaged agent skill for sending notifications from Codex and Claude tasks and automations.

## Architecture

For a text notification, the CLI obtains a short-lived Google OAuth token from its Firebase service-account credential and calls the FCM HTTP v1 API. Android stores the message locally and displays a system notification.

For an attachment:

1. Android generates an RSA key pair in Android Keystore. Its private key never leaves the phone.
2. The CLI receives the public key during one-time pairing.
3. The CLI generates a fresh AES-256-GCM key and encrypts the file locally.
4. The AES key is wrapped to the Android public key with RSA-OAEP.
5. Only ciphertext is uploaded to the private Firebase Storage bucket.
6. FCM carries the message, attachment metadata, wrapped key, checksum, and a seven-day signed download URL.
7. Android downloads only after **Download** is tapped, decrypts locally, verifies SHA-256 and file size, and stores the result in private app storage.
8. Deleting the Android message also deletes its downloaded file.

The configured bucket lifecycle deletes objects under `agent-notify/` after seven days. Downloaded Android copies remain until their message is deleted or the app is removed.

## Requirements

- A Firebase project on the Blaze plan.
- An Android 6.0 or newer phone with Google Play services.
- Current Android Studio and Android SDK 37.
- Node.js 18 or newer on macOS.
- A Firebase Admin service-account JSON key stored outside the repository.

## 1. Configure Firebase

### Register the Android app

1. Open the [Firebase console](https://console.firebase.google.com/).
2. Create or select a Firebase project.
3. Add an Android app using package name `com.agentnotify.app`.
4. Download `google-services.json`.
5. Place it at `android/app/google-services.json`.

The file is ignored by Git. Do not force-add it.

### Enable Cloud Messaging

In Google Cloud Console, confirm that **Firebase Cloud Messaging API (V1)** is enabled for the project.

### Create the Storage bucket

1. Upgrade the Firebase project to the Blaze plan and attach a billing account.
2. Open **Build → Storage** in Firebase Console.
3. Select **Get started**.
4. Choose **Production mode** so the bucket is not publicly readable.
5. Select the bucket region carefully; it cannot be changed later.

The default bucket for new projects is normally named `PROJECT_ID.firebasestorage.app`.

### Create the sender credential

1. Open **Project settings → Service accounts** in Firebase Console.
2. Generate a new private key for the Firebase Admin SDK service account.
3. Save the downloaded JSON outside this repository, for example:

```text
~/.config/agent-notify/service-account.json
```

Protect it with owner-only permissions:

```sh
chmod 600 ~/.config/agent-notify/service-account.json
```

The service account must be able to send FCM messages and create Storage objects. If uploads return HTTP 403, grant it **Storage Object Admin** on this bucket. Avoid granting broader project permissions unless required.

### Configure seven-day cleanup

In Google Cloud Console:

1. Open **Cloud Storage → Buckets** and select the Firebase bucket.
2. Open **Lifecycle** and add a rule.
3. Choose the **Delete object** action.
4. Set object age to **7 days**.
5. Restrict the rule to the prefix `agent-notify/`.

This rule permanently deletes only the encrypted cloud attachment objects created by Agent Notify. Signed links also expire after seven days.

## 2. Build and install Android

### Android Studio

1. Open the `android/` directory in Android Studio.
2. Allow Gradle sync to complete.
3. Connect the phone with USB debugging enabled.
4. Run the `app` configuration.
5. Grant notification permission when Android requests it.

### Command line

Android Studio includes a compatible JDK. On macOS:

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install or upgrade it with ADB:

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
```

Using `-r` upgrades the existing debug installation and preserves its messages and Android Keystore key when the signing key is unchanged.

### Copy the pairing values

Open the app and tap the Settings icon:

1. Tap **Copy token** to copy the FCM device token.
2. Tap **Copy file key** to copy the attachment public key.

The FCM token is sensitive because it identifies the destination. The file key is public; its private counterpart remains in Android Keystore.

## 3. Install the macOS CLI

Clone the private repository and link the executable globally:

```sh
gh repo clone andymarcus/agent-notify
cd agent-notify/cli
npm link
```

Verify installation:

```sh
agent-notify --version
agent-notify --help
```

On Apple Silicon with Homebrew, the executable normally resolves to `/opt/homebrew/bin/agent-notify`.

## 4. Configure the CLI

Run this once on each sending Mac:

```sh
agent-notify configure \
  --service-account "$HOME/.config/agent-notify/service-account.json" \
  --token "FCM_TOKEN_COPIED_FROM_ANDROID" \
  --public-key "FILE_KEY_COPIED_FROM_ANDROID"
```

The Firebase project ID and default Storage bucket are derived automatically. Override them only when necessary:

```sh
agent-notify configure \
  --project-id "your-project-id" \
  --storage-bucket "your-project-id.firebasestorage.app"
```

Re-running `configure` changes only supplied values. This is useful when the FCM token rotates:

```sh
agent-notify configure --token "NEW_TOKEN"
```

Configuration is stored with mode `0600` at:

```text
~/.config/agent-notify/config.json
```

Set `AGENT_NOTIFY_CONFIG` to use a different config file. Never commit the config or service-account JSON.

## 5. Send notifications

### Plain text

```sh
agent-notify \
  --topic "builds" \
  --message "Build finished successfully."
```

### Markdown

```sh
agent-notify \
  --topic "review" \
  --message $'## Review ready\n\n- 3 files changed\n- Tests pass'
```

### Markdown from a file

`--message-file` reads the file as the message body; it does not attach that file:

```sh
agent-notify --topic "daily brief" --message-file "/absolute/path/to/brief.md"
```

Use `-` to read from stdin without losing Markdown formatting:

```sh
cat <<'EOF' | agent-notify --topic "daily brief" --message-file -
## Morning brief

- Calendar reviewed
- No urgent unread email
EOF
```

### File attachment with a message

```sh
agent-notify \
  --topic "reports" \
  --message "Invoice report attached." \
  --file "/absolute/path/to/invoice-report.pdf"
```

### File attachment without a separate message

```sh
agent-notify \
  --topic "exports" \
  --file "/absolute/path/to/export.zip"
```

Only one attachment is accepted per message, with a maximum size of 50 MiB. The Android app shows the offer immediately but does not download it until **Download** is tapped.

### Validate without sending

```sh
agent-notify --topic "test" --message "Not delivered" --dry-run
```

Single-dash `-topic`, `-message`, and `-message-file` aliases are supported for compatibility.

## 6. Install the background sender for scheduled tasks

Some Codex scheduled-task sandboxes cannot resolve Google OAuth or FCM hosts. The CLI handles recognized connectivity errors by placing the request in a private per-user queue and waiting for a LaunchAgent to send it outside that sandbox.

The background runtime is copied outside `Documents` to avoid macOS privacy restrictions:

```sh
cd /absolute/path/to/agent-notify

RUNTIME="$HOME/.local/share/agent-notify-cli"
mkdir -p "$RUNTIME/src" "$RUNTIME/bin" "$RUNTIME/launchd"
cp cli/package.json "$RUNTIME/package.json"
cp cli/src/agent-notify.js cli/src/queue.js "$RUNTIME/src/"
cp cli/bin/agent-notify.js cli/bin/agent-notify-worker.js "$RUNTIME/bin/"
cp cli/launchd/com.agentnotify.sender.plist "$RUNTIME/launchd/"
chmod 755 "$RUNTIME/bin/agent-notify.js" "$RUNTIME/bin/agent-notify-worker.js"
```

Install and adapt the LaunchAgent to the current Mac:

```sh
PLIST="$HOME/Library/LaunchAgents/com.agentnotify.sender.plist"
mkdir -p "$HOME/Library/LaunchAgents"
cp "$RUNTIME/launchd/com.agentnotify.sender.plist" "$PLIST"

/usr/libexec/PlistBuddy -c "Set :ProgramArguments:0 $(command -v node)" "$PLIST"
/usr/libexec/PlistBuddy -c "Set :ProgramArguments:1 $RUNTIME/bin/agent-notify-worker.js" "$PLIST"
/usr/libexec/PlistBuddy -c "Set :WorkingDirectory $RUNTIME" "$PLIST"
/usr/libexec/PlistBuddy -c "Set :StandardOutPath $HOME/.config/agent-notify/worker.log" "$PLIST"
/usr/libexec/PlistBuddy -c "Set :StandardErrorPath $HOME/.config/agent-notify/worker.log" "$PLIST"

launchctl bootout "gui/$(id -u)/com.agentnotify.sender" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
```

Verify it:

```sh
launchctl print "gui/$(id -u)/com.agentnotify.sender"
tail -n 50 "$HOME/.config/agent-notify/worker.log"
```

Queued attachments are copied into the private queue before handoff, so the LaunchAgent does not need permission to reopen the original file. Queue files use owner-only permissions and are removed after processing.

Whenever CLI code is updated, repeat the runtime copy commands and restart the agent:

```sh
launchctl kickstart -k "gui/$(id -u)/com.agentnotify.sender"
```

## 7. Agent skills

Installing or linking the CLI automatically installs its bundled skill at user scope for both supported agents:

- Codex: `${CODEX_HOME:-$HOME/.codex}/skills/agent-notify/`
- Claude: `$HOME/.claude/skills/agent-notify/`

These locations make the skill available across all projects. If npm lifecycle scripts were disabled during installation, install or refresh both copies manually:

```sh
cd cli
npm run install-skills
```

Codex can invoke the skill as `$agent-notify`; Claude Code can invoke it as `/agent-notify`. Both agents may also select it automatically when the request matches its description, but the skill sends a notification only when the user explicitly asks for one.

## 8. Use another Mac

On each additional Mac:

1. Clone the private GitHub repository.
2. Install Node.js 18 or newer and run `npm link` from `cli/`.
3. Securely place a copy of the Firebase Admin service-account JSON outside the repository.
4. Run `agent-notify configure` with the current phone token and public file key.
5. Install the LaunchAgent if sandboxed scheduled tasks will send notifications.
6. Confirm that the automatically installed skill appears in Codex or Claude Code.

The service-account JSON, FCM token, and local CLI config are intentionally absent from GitHub.

## Android usage

- Tap a message to mark it read.
- Tap a long message to expand or collapse it.
- Press and hold a message to copy the full body.
- Swipe right and confirm to delete the message and any downloaded attachment.
- Swipe left to mark the message unread.
- Tap **Download** to retrieve and decrypt an attachment.
- Tap **Retry** after a recoverable failure.
- Tap **Open** to use an Android app associated with the attachment MIME type.

Messages and downloaded attachments remain available offline. Attachment offers whose signed links have expired cannot be downloaded again; resend the attachment from the CLI.

## Testing

Run CLI tests:

```sh
cd cli
npm test
```

Build Android:

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Check the CLI end to end with a harmless small file:

```sh
agent-notify \
  --topic "test channel" \
  --message "Encrypted attachment test" \
  --file "/absolute/path/to/test.txt"
```

Success requires FCM acceptance, tapping **Download** on Android, successful decryption and checksum verification, and opening the resulting local file.

## Troubleshooting

### `fetch failed` or `ENOTFOUND oauth2.googleapis.com`

Install and start the background sender described above. Check:

```sh
launchctl print "gui/$(id -u)/com.agentnotify.sender"
tail -n 100 "$HOME/.config/agent-notify/worker.log"
```

### `Background sender did not respond`

Confirm that the LaunchAgent paths match the current username, Node executable, and runtime location. Then restart it with `launchctl kickstart -k`.

### Attachment upload returns HTTP 403

Confirm that Firebase Storage exists, the configured bucket name is correct, and the service account has permission to create objects in that bucket.

### Download returns HTTP 403

The signed link may have expired. Signed attachment links last seven days; resend the file.

### `Keystore operation failed`

Use Android app 1.2.1 or newer and CLI 1.2.1 or newer. Older attachment offers used an incompatible OAEP MGF1 digest and cannot be recovered; send a fresh attachment after upgrading.

### The phone stops receiving messages

Open Android Settings in the app, copy the latest FCM token, and update only that setting:

```sh
agent-notify configure --token "NEW_TOKEN"
```

### The app was reinstalled or its data was cleared

A new Android Keystore key may have been generated. Copy the new file key and update the CLI:

```sh
agent-notify configure --public-key "NEW_FILE_KEY"
```

Attachments sent for an older private key cannot be decrypted and must be resent.

## Security

- Never commit the Firebase service-account JSON, CLI config, or device token.
- Firebase Storage remains private; downloads use narrowly scoped, expiring signed URLs.
- Each attachment uses a new random AES-256-GCM key and IV.
- The AES key is RSA-OAEP wrapped for the phone before transmission.
- Android keeps its RSA private key in Android Keystore.
- SHA-256 and expected size are checked before a download becomes available.
- Downloaded files live in app-private storage and are exposed to another Android app only through a temporary `FileProvider` grant when opened.
- The Storage lifecycle rule deletes encrypted cloud objects after seven days.
- A leaked signed URL exposes only ciphertext, but service-account credentials and FCM tokens must still be protected.

If the service-account key is exposed, revoke it immediately in Google Cloud Console and configure each sending Mac with a replacement key.

## License

This is a private, unlicensed personal project.
