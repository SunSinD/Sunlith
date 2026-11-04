# Sunlith

A clean calendar app.

Keep your events, reminders, and home screen widget together. Add or change events directly, or connect an assistant on your computer to manage them with prompts.

## Download

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.0.1-alpha.1/Sunlith-v0.0.1-alpha.1.apk)

On your phone, you only need the APK. The ZIP is the computer companion, and SHA256SUMS.txt is for verifying downloads.

[Release notes](https://github.com/SunSinD/Sunlith/releases/tag/v0.0.1-alpha.1)

Requires Android 8.0 or newer. This is an early alpha, signed with a development key. Local and CI builds can use a different key, so use the published APK for updates. Production signing is planned before a stable release.

## Calendar

Sunlith has month, week, and day agendas, search, calendar filters, dark and light themes, and English and French support. Events support all-day dates, repeat presets, notes, locations, and reminders. The resizable agenda widget shows upcoming events and opens event details or the editor.

It uses the calendars already stored on your phone. Your installed account adapters handle syncing. You can also create an on-device calendar and import or export .ics files through the system file picker. See [existing calendars and imports](docs/MIGRATION.md).

The APK has no advertising, analytics, Internet permission, or built-in chat. It keeps no separate event database.

## Connect an assistant

[Download the computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.0.1-alpha.1/Sunlith-companion-v0.0.1-alpha.1.zip)

The companion runs on Windows, macOS, and Linux with Node.js 22 or newer. It connects a supported assistant to a Google calendar that your phone syncs. Extract the archive and read START-HERE.md, then follow the [setup guide](docs/AI-INTEGRATION.md).

Setup is recommended at first launch, and you can continue using the calendar without it. Sunlith has no subscription of its own. Your assistant may require its own account. This alpha includes a command-line companion, with no desktop calendar, browser setup wizard, or native desktop installer.

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

## Alpha limits

Real Google authorization, account sync, and reminder delivery still need testing on your phone. Companion checks pass on Windows, macOS, and Linux, but individual assistants have not all been tested with a real calendar account.

The companion currently controls Google Calendar. Other accounts exposed through Android can appear in Sunlith, but this companion cannot control Outlook, CalDAV, or phone-only calendars.

The editor blocks meetings with attendee records and complex recurring exceptions. Supported recurring edits and deletes apply to the whole series. Imports report unsupported records and exports can skip malformed provider data. Some detailed diagnostics remain English in the French interface. Back up important calendars before testing changes or imports.

See [testing](docs/TESTING.md), [architecture](docs/ARCHITECTURE.md), [calendar research](docs/RESEARCH-CALENDAR.md), and [versioning](docs/VERSIONING.md).

## License

Apache-2.0. See [LICENSE](LICENSE) and [third-party notices](docs/THIRD-PARTY.md).
