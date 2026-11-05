# Sunlith v0.1.2

A clean calendar app. Requires Android 8.0 or newer.

This update replaces the launcher and setup artwork. Calendar behavior is unchanged.

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.1.2/Sunlith-v0.1.2.apk)

Install over your current Sunlith app without uninstalling. The published APK keeps the same development signing key so app data is preserved. Production signing is planned before a stable release.

## Changes

- Uses the new gold sun and calendar artwork in the launcher and setup screen.
- Keeps the sun rays inside Android's adaptive icon safe area, with a matching transparent monochrome layer for themed icons.
- Uses a transparent in-app mark so the artwork works in both light and dark themes.

## Assistant connections

Phone assistants that create downloadable attachments can provide an `.ics` file containing event details. Open or share it with Sunlith, review the dates and destination calendar, then confirm Import. Compatible Android assistants can also open a draft for you to review and Save.

The [computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.1.2/Sunlith-companion-v0.1.2.zip) runs on Windows, macOS, and Linux with Node.js 22 or newer. It currently controls Google Calendar only. It does not connect a computer conversation to Samsung-account or phone-only calendars. There is no built-in chat or remote phone-calendar bridge in this update. See the [setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md).

Only the APK is needed on your phone. The ZIP is the computer companion, and SHA256SUMS.txt verifies both downloads.

## Testing

See [recorded checks and limits](https://github.com/SunSinD/Sunlith/blob/main/docs/TESTING.md). Physical Samsung launcher behavior, account sync, battery restrictions, and reminder delivery still need testing on your phone. Individual assistant integrations have not all been tested with a real calendar account.
