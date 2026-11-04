# Sunlith v0.0.1-alpha.1

First public alpha of Sunlith, an open-source Android calendar.

## Install

Download **Sunlith-v0.0.1-alpha.1.apk** below and open it on Android 8.0 or newer. Allow installation from that source if Android asks. AI pairing is recommended at first launch, with a secondary option to continue without AI. Grant calendar access, then choose your existing calendars or create an on-device calendar. Add the agenda widget from your home screen's widget picker.

The APK is debug-signed for this initial test release. Use published release APKs consistently; a locally built or CI-built APK may use another signing key and require uninstalling the other build first. Production signing is planned before a stable release. Back up important calendars before testing imports or changes.

For computer control, **Sunlith-companion-v0.0.1-alpha.1.zip** contains the same local MCP companion for Windows, macOS, and Linux. It requires Node.js 22+; read `START-HERE.md` after extracting it. This is a command-line companion, not a desktop calendar or a browser setup wizard. SHA-256 checksums for both downloads are included in **SHA256SUMS.txt**.

## Included

- Dark and light themes, English and French, month/week/day agendas, search, and calendar filters.
- Personal event editing, dates/times, all-day events, repeat presets, reminders, notes, and location.
- A resizable, scrollable agenda widget with adjustable opacity and event/create shortcuts.
- Notifications with snooze and dismiss; reminders can be turned off if another app already handles them.
- Android's existing calendars and account sync, local calendar creation, and `.ics` preview/import/export.
- A standard MCP companion for shared Google calendars, with a recommended first-run AI setup and guides explaining support for T3 Code’s providers.

The APK has no Internet permission, advertising, analytics, or built-in AI chat. AI control runs through the companion on your computer; normal Android account sync carries changes to the phone.

## Existing DigiCal events

Select the same account/calendars in Sunlith first. Events in Android's shared calendar storage should appear without copying. Keep DigiCal installed until you have backed up and checked any local-only calendars. [Migration guide](https://github.com/SunSinD/Sunlith/blob/main/docs/MIGRATION.md).

## AI setup

The phone app works independently. For prompt-based control, the companion requires Node.js 22+, your own Google Desktop OAuth client, and authorization to the same Google account your phone syncs. No account credentials are bundled. [Setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md).

## Alpha limits

- Real Google authorization, account sync, Samsung battery behavior, and notification delivery on your particular phone still need real-device testing.
- Individual AI clients follow their documented MCP configuration; not every client/provider has been tested end to end.
- The editor blocks guest meetings and complex recurring exceptions. Use the existing calendar app for these cases. Recurring edits/deletes apply to the whole supported series.
- ICS imports skip unsupported exception-heavy series and report them. Distinct supplied UIDs are preserved; repeated UIDs and matching UID-less records are deduplicated. Imports do not invite guests.
- Recurring timed ICS durations expressed as nominal days/weeks are skipped when timezone clock changes could alter their meaning. Malformed provider records are reported and skipped during export; check the warning before relying on a backup.
- Some detailed import and provider error messages are currently English, including in the French interface.
- Calendar-default reminder offsets rely on the provider's alert broadcast. Background sync/widget updates and alarm delivery are subject to Android's scheduling and power rules.
- The MCP adapter is Google-only. CalDAV, Outlook, and phone-only calendars can be displayed through Android's provider, but are not controlled by this companion.

This release is an **alpha**, earlier than a beta. Please report reproducible issues with the Android version, device, app version, and steps, without including private calendar data or credentials.
