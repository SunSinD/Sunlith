# Sunlith v0.1.0-beta.1

A clean calendar app. This is a **beta testing build** for Android 8.0 or newer. The GitHub release is marked Latest so the download is easy to find; it is still beta software.

## Install or update

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.1.0-beta.1/Sunlith-v0.1.0-beta.1.apk)

Open Sunlith-v0.1.0-beta.1.apk on your phone. Allow installation from that source if Android asks, then grant calendar access and select your calendars. Add the agenda widget through your home screen widget picker.

Only the APK is needed on your phone. The companion ZIP is for computers; SHA256SUMS.txt verifies both downloads.

This beta is signed with a development key. Use the published APK for updates, since local and CI builds can have different keys. Updating the previous test build with the same-signed APK preserves app data; do not uninstall first. Production signing is planned before a stable release. Back up important calendars before testing imports or changes.

## Calendar and widget

- Month, week, and day views, search, calendar filters, and English and French support.
- A fixed six-row month grid with centered dates, animated swipe navigation, and a clear Today action.
- A scrollable agenda that includes all days by default, plus options to group empty days or show only days with events.
- Grouped settings, system theme by default, a manual dark toggle, and a startup choice of Month, Week, Day, or last used.
- A resizable agenda widget with day dividers and empty days. Choose 1-31 days, default seven; opacity defaults to 100% and changes in 5% steps. Calendar names are off by default.
- Personal events, repeat presets, all-day dates, notes, locations, notifications with snooze and dismiss, and .ics import/export.
- A minimal sun icon and first-run setup with eight optional assistant guides.
- Phone assistant handoffs: open/share a supported `.ics` file for import preview, or review and Save a draft from a compatible Android calendar intent. No Google account or computer is needed for these local handoffs.

The APK has no advertising, analytics, Internet permission, or built-in chat. It uses Android's shared calendar provider and installed account sync adapters.

## Computer companion

[Download the computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.1.0-beta.1/Sunlith-companion-v0.1.0-beta.1.zip)

The local stdio MCP companion runs on Windows, macOS, and Linux with Node.js 22 or newer. Extract the archive and read START-HERE.md. It requires your own Google Desktop OAuth client and authorization to a Google calendar that your phone syncs. [Setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md).

The app provides guides for Codex, Claude, Cursor, Grok, OpenCode, Antigravity, Muse Code, and Pi; Gemini CLI is also documented. Client setup is optional. The companion has no desktop calendar, native desktop installer, hosted setup wizard, or HTTP MCP gateway.

## Testing and limits

See [test details](https://github.com/SunSinD/Sunlith/blob/main/docs/TESTING.md) for recorded checks. Real Google authorization, account sync, reminder delivery, battery restrictions, and launcher behavior still need testing on your phone. Individual assistant integrations have not all been tested with a real account.

The companion controls Google Calendar. Other calendars exposed through Android can appear in the phone app, but this companion cannot control Outlook, CalDAV, or phone-only calendars.

The editor blocks events with attendee records and complex recurring exceptions. Supported recurring edits and deletes apply to the whole series. Imports report unsupported records, including unknown Windows timezone names, and exports can skip malformed provider data. Some detailed diagnostics remain English in the French interface.

Report reproducible issues with the app version, Android version, device, and steps. Leave private calendar content and credentials out of reports.
