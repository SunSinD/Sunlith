# Sunlith v0.0.1-alpha.1

First public alpha of Sunlith, a clean calendar app.

## Install

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.0.1-alpha.1/Sunlith-v0.0.1-alpha.1.apk)

Download Sunlith-v0.0.1-alpha.1.apk and open it on Android 8.0 or newer. Allow installation from that source if Android asks, then grant calendar access and select your calendars. Add the agenda widget through your home screen widget picker.

On your phone, you only need the APK. The ZIP is for computers, and SHA256SUMS.txt is for verifying downloads.

This alpha is debug-signed. Keep using the published APK for updates, since local and CI builds can have different signing keys. Production signing is planned before a stable release. Back up important calendars before testing imports or changes.

## Included

Month, week, and day agendas, search, calendar filters, light and dark themes, English and French, personal events, repeat presets, notifications with snooze and dismiss, a resizable agenda widget, and .ics import and export.

Assistant setup is recommended at first launch, with a secondary option to use the calendar alone. The APK has no advertising, analytics, Internet permission, or built-in chat.

## Computer companion

Sunlith-companion-v0.0.1-alpha.1.zip contains the local MCP companion for Windows, macOS, and Linux. It requires Node.js 22 or newer. Extract it and read START-HERE.md. This is a command-line companion, with no desktop calendar, browser setup wizard, or native installer.

Computer control requires your own Google Desktop OAuth client and authorization to a calendar that your phone syncs. [Setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md).

SHA-256 checksums for both downloads are in SHA256SUMS.txt.

## Alpha limits

Emulator and companion checks pass. Real Google authorization, account sync, and reminder delivery still need testing on your phone. [Test details](https://github.com/SunSinD/Sunlith/blob/main/docs/TESTING.md).

The editor blocks events with attendee records and complex recurring exceptions. Supported recurring edits and deletes apply to the whole series. Imports report unsupported records, including unknown Windows timezone names, and exports can skip malformed provider data. Some detailed diagnostics remain English in the French interface.

The companion controls Google Calendar. Other calendars exposed through Android can appear in the phone app, but this companion cannot control Outlook, CalDAV, or phone-only calendars. Individual client integrations have not all been tested with a real account.

Please report reproducible issues with the app version, Android version, device, and steps, without private calendar content or credentials.
