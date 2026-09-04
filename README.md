# Agent Notify

Agent Notify lets a macOS CLI send topic-grouped text or Markdown notifications to a single Android device through Firebase Cloud Messaging (FCM). There is no custom application server.

## Repository layout

- `android/` — Kotlin + Jetpack Compose Android receiver
- `cli/` — dependency-free Node.js macOS sender

## How it works

The Android app obtains an FCM registration token and displays it on the Settings screen. The CLI stores that token, the Firebase project ID, and the path to a Firebase service-account key. For each message, the CLI obtains a short-lived OAuth token and sends an FCM HTTP v1 data message. The Android app stores the message in its private SQLite database and posts a system notification.

FCM is the only hosted transport. Message history remains on the phone. The service-account key remains on the Mac.

## Firebase setup

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.agentnotify.app`.
3. Download `google-services.json` into `android/app/google-services.json`.
4. In Google Cloud Console, ensure the **Firebase Cloud Messaging API (V1)** is enabled.
5. Create a service account with permission to send FCM messages (the Firebase Admin SDK administrator role is convenient for a private project), download its JSON key, and keep it somewhere private on the Mac.

Do not commit either credential file. The Android Firebase config contains project identifiers rather than a server secret, but it is ignored to prevent accidentally coupling this source tree to one Firebase project. The service-account JSON is sensitive and must never be copied into this repository or onto the phone.

## Build and install the Android app

Requirements: current Android Studio, Android SDK 37, and an Android 6.0+ phone with Google Play services.

1. Open `android/` in Android Studio.
2. Allow Gradle sync to finish.
3. Connect the phone and run the `app` configuration.
4. Grant notification permission when prompted.
5. Open **Settings**, wait for the FCM token, and tap **Copy token**.

From a configured command line, `cd android && ./gradlew assembleDebug` produces the debug APK.

### Message interactions

- Unread messages show an unread icon; tap a message to mark it read.
- Long messages show a shortened preview; tap to expand or collapse them.
- Press and hold a message to copy its full body.
- Swipe right to request deletion, then confirm in the dialog.
- Swipe left to mark a message unread.

## Install and configure the macOS CLI

```sh
cd cli
npm link

agent-notify configure \
  --service-account "/absolute/path/to/service-account.json" \
  --token "token-copied-from-the-android-app"
```

Node.js 18 or newer is required. The Firebase project ID is read from the service-account file automatically; `--project-id` remains available as an override.

The config is written with owner-only permissions to `~/.config/agent-notify/config.json`. You may instead set `AGENT_NOTIFY_CONFIG` to another config path.

For sandboxed scheduled tasks, the CLI automatically falls back to the installed `com.agentnotify.sender` LaunchAgent. The task writes to a private per-user queue under `/tmp`; the background sender performs the Firebase request outside the task sandbox and returns Firebase's result before the CLI exits.

## Send messages

```sh
agent-notify --topic "daily brief" --message "Build finished successfully."

agent-notify --topic "review" --message $'## Review ready\n\n- 3 files changed\n- Tests pass'

agent-notify --topic "report" --message-file report.md

printf '**Done** at %s' "$(date)" | agent-notify --topic build --message-file -
```

Single-dash spellings such as `-topic` and `-message` are accepted as aliases. Run `agent-notify --help` for all options.

## Security notes

There is no user account or login. Possession of the service-account key authorizes sending, and possession of the device token identifies the destination. Keep both private. If the key is exposed, revoke it in Google Cloud immediately. If the phone's token changes, copy the new token and run `configure --token ...`; other existing settings are retained.
