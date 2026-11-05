# Sunlith v0.2.0

A clean calendar app. Requires Android 8.0 or newer.

Manage events directly on your phone from your computer over Wi-Fi or Tailscale. No Google account or second calendar app is needed.

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.2.0/Sunlith-v0.2.0.apk)

Install over your current Sunlith app without uninstalling. The published APK keeps the same development signing key and preserves app data.

## Changes

- Adds Settings > Computer connection with calendar selection, private file pairing, Start, Stop and Unpair.
- Lets a connected assistant list, create, edit and delete events stored on the phone, including on-device calendars.
- Adds computer import of private ICS backups, with imported, duplicate and skipped counts.
- Uses encrypted connections with a pinned phone certificate. A notification shows when access is on.
- Updates calendar lists even when loading events fails, and clarifies account labels in export selection.

## Setup

Open Settings > Computer connection, choose the calendars to share, save the pairing file and send it privately to your computer. Tap Start connection. Keep both devices on the same Wi-Fi or private Tailscale network.

The [computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.2.0/Sunlith-companion-v0.2.0.zip) works on Windows, macOS and Linux with Node.js 22 or newer. Extract it and read START-HERE.md. See the [setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md#direct-phone-connection).

Only the APK is needed on your phone. The ZIP is the computer companion, and SHA256SUMS.txt verifies both downloads.

## Testing and limits

Desktop checks and real Android emulator tests cover pairing, certificate checks, calendar access, event changes, recurrence safeguards, imports and duplicate detection. See [recorded results](https://github.com/SunSinD/Sunlith/blob/main/docs/TESTING.md#version-020-direct-connection-verification).

The connection must stay on while your computer uses it. Phone battery restrictions can interrupt background access; allow background battery use if needed. It does not start automatically after reboot. Real-device Tailscale connectivity and Samsung battery behavior still need phone testing.

Repeating edits and deletions apply to the entire series. Meetings with guests and series with exceptions are blocked. Imports report unsupported records and reminder changes, and are not one transaction. The direct import tool accepts files up to 256 KiB; larger files can use the phone picker.
