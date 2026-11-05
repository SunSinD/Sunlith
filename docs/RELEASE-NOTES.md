# Sunlith v0.1.1

A clean calendar app. Requires Android 8.0 or newer.

This build uses plain version numbers. Calendar behavior is unchanged from the previous build.

[Download the APK](https://github.com/SunSinD/Sunlith/releases/download/v0.1.1/Sunlith-v0.1.1.apk)

Install over your current Sunlith app without uninstalling. The published APK keeps the same development signing key so app data is preserved. Production signing is planned before a stable release.

## Changes

- The calendar stays visible while the agenda scrolls. Settings > Calendar can enable header collapse.
- Month view lists only that month's days. Week view always lists its seven days, including empty days.
- Pressing Day opens today. The date picker and arrow buttons can then choose another day.
- Sideways swipes no longer change dates. The growing agenda window and unrelated month labels have been removed.
- The calendar has its own bounded scrolling area on small screens and at large font sizes.
- A roomier launcher icon combines a calendar outline with a gold sun. The themed icon and in-app mark match.

## Assistant connections

Phone assistants that create downloadable attachments can provide an `.ics` file containing event details. Open or share it with Sunlith, review the dates and destination calendar, then confirm Import. Compatible Android assistants can also open a draft for you to review and Save.

The [computer companion](https://github.com/SunSinD/Sunlith/releases/download/v0.1.1/Sunlith-companion-v0.1.1.zip) runs on Windows, macOS, and Linux with Node.js 22 or newer. It currently controls Google Calendar only. It does not connect a computer conversation to Samsung-account or phone-only calendars. There is no built-in chat or remote phone-calendar bridge in this update. See the [setup guide](https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md).

Only the APK is needed on your phone. The ZIP is the computer companion, and SHA256SUMS.txt verifies both downloads.

## Testing

See [recorded checks and limits](https://github.com/SunSinD/Sunlith/blob/main/docs/TESTING.md). Physical Samsung launcher behavior, account sync, battery restrictions, and reminder delivery still need testing on your phone. Individual assistant integrations have not all been tested with a real calendar account.
