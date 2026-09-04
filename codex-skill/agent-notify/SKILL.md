---
name: agent-notify
description: Send topic-grouped text or Markdown notifications to the user's Android phone with the installed agent-notify CLI. Use when the user explicitly asks a Codex task, scheduled task, automation, or agent workflow to notify them through agent-notify. Do not invoke merely because a task completes.
---

# Agent Notify

Use the globally installed executable at `/opt/homebrew/bin/agent-notify`. It is already configured for the user's Android device and Firebase project; do not read, print, move, or modify its device token, configuration, or service-account key.

Run the CLI normally inside scheduled tasks. If their sandbox blocks DNS for Google OAuth or FCM, the CLI automatically delegates delivery through the installed `com.agentnotify.sender` macOS LaunchAgent and waits for Firebase's result. Do not request elevated sandbox permissions and do not bypass or reconfigure the background sender.

## Send a notification

Send only when the user's request authorizes a notification. For a scheduled or long-running task, send at the requested event—normally after the outcome is known—not when the task starts. Do not send duplicate progress notifications unless requested.

```sh
/opt/homebrew/bin/agent-notify --topic "daily brief" --message "The daily brief is ready."
```

For complex multiline Markdown, piping content to `--message-file -` is supported. A UTF-8 file with `--message-file` is also appropriate when the content already exists as an artifact.

Both plain text and Markdown are supported. Keep phone notifications concise and useful. A good completion message states the outcome first and may add a short Markdown list of essential results. Firebase data payloads are limited to roughly 4 KB, so link to or name a larger artifact instead of embedding it.

Use the user's exact topic when supplied. Otherwise choose a short, stable topic derived from the recurring task or workflow name so related messages group together; do not invent a new timestamped topic for every run.

For content already stored in a UTF-8 Markdown file, use:

```sh
/opt/homebrew/bin/agent-notify --topic "review" --message-file "/absolute/path/to/summary.md"
```

Use `--dry-run` only for validation because it does not deliver anything.

## Handle outcomes

Treat exit status 0 and the printed `Sent:` Firebase message name as successful acceptance by FCM. Do not claim delivery if the command fails. Report the concise error in the task result; retry at most once only when the failure is clearly transient. Do not attempt to regenerate credentials or reconfigure the CLI unless the user explicitly asks.

When notifying about failure or partial completion, say so truthfully in the notification rather than sending a generic success message. Sending the notification does not replace the normal task result: still leave enough detail in the Codex task for later inspection.
