# Sunlith

A clean calendar app.

Keep your events, reminders, and home screen widget together. Add or change events directly, or connect an assistant on your computer to manage them with prompts.

## Download

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.1.1/Sunlith-v0.1.1.apk)

On your phone, you only need the APK. The ZIP is the computer companion, and SHA256SUMS.txt is for verifying downloads.

[Release notes](https://github.com/SunSinD/Sunlith/releases/tag/v0.1.1)

Requires Android 8.0 or newer. The published APK is signed with a development key. Local and CI builds can use a different key, so use the published APK for updates. Installing a newer APK with the same signing key preserves app data. Production signing is planned before a stable release.

## Calendar

Sunlith has month, week, and day views, search, calendar filters, and English and French support. The month view pairs a calendar grid with an agenda for that month, including empty days by default. The grid stays visible while its agenda scrolls, with a setting to enable collapse. Week view lists exactly seven days, and pressing Day opens today. Use the date buttons or Today to move around. Month view can also group empty days or show only days with events.

Events support all-day dates, repeat presets, notes, locations, and reminders. The app follows your system theme by default, with a manual override. Choose a startup view or reopen the last view. The resizable agenda widget shows 1-31 days, defaults to seven, and includes day dividers and empty days. Its opacity is adjustable in 5% steps; calendar names are hidden by default.

It uses the calendars already stored on your phone. Your installed account adapters handle syncing. You can also create an on-device calendar and import or export .ics files through the system file picker. See [existing calendars and imports](docs/MIGRATION.md).

The APK has no advertising, analytics, Internet permission, or built-in chat. It keeps no separate event database.

## Connect an assistant

For phone-only use, ask a mobile assistant that can create files for a downloadable `.ics` attachment containing your event details. Open or share it with Sunlith, choose a destination calendar, then confirm Import. Compatible Android assistants can also open an event draft for you to review and Save. These handoffs need no computer or Google account. See [phone assistant setup](docs/AI-INTEGRATION.md#on-your-phone-without-a-computer).

[Download the computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.1.1/Sunlith-companion-v0.1.1.zip)

The companion runs on Windows, macOS, and Linux with Node.js 22 or newer. It connects a supported assistant to a Google calendar that your phone syncs. Extract the archive and read START-HERE.md, then follow the [setup guide](docs/AI-INTEGRATION.md).

First-run setup offers eight assistant guides: Codex, Claude, Cursor, Grok, OpenCode, Antigravity, Muse Code, and Pi. The setup guide also covers Gemini CLI. You can use the calendar without connecting an assistant. Sunlith has no subscription of its own; your assistant may require its own account. This release includes a command-line companion, with no desktop calendar, browser setup wizard, native desktop installer, or HTTP MCP gateway.

## Build

Install JDK 17 and Android SDK platform 36 with build tools 36.0.0. Set ANDROID_HOME or create an untracked local.properties containing your SDK path.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS or Linux, use ./gradlew with the same tasks. The APK is written to app/build/outputs/apk/debug/app-debug.apk.

Companion checks, from integrations/mcp:

```sh
npm ci
npm run check
npm test
```

## Current limits

Real Google authorization, account sync, and reminder delivery still need testing on your phone. Individual assistants have not all been tested with a real calendar account. See [testing](docs/TESTING.md) for the recorded checks and their limits.

The companion currently controls Google Calendar. Other accounts exposed through Android can appear in Sunlith, but this companion cannot control Outlook, CalDAV, or phone-only calendars.

The editor blocks meetings with attendee records and complex recurring exceptions. Supported recurring edits and deletes apply to the whole series. Imports report unsupported records and exports can skip malformed provider data. Some detailed diagnostics remain English in the French interface. Back up important calendars before testing changes or imports.

See [testing](docs/TESTING.md), [architecture](docs/ARCHITECTURE.md), [calendar research](docs/RESEARCH-CALENDAR.md), and [versioning](docs/VERSIONING.md).

## License

Apache-2.0. See [LICENSE](LICENSE) and [third-party notices](docs/THIRD-PARTY.md).
