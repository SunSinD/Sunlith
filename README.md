# Sunlith

A clean calendar app with direct computer control.

Plan on your phone, or ask your computer to add, edit and remove events. Sunlith works with on-device calendars and the calendars already on your phone. No second calendar app or Google account is needed for the direct connection.

## Make it yours

- Month, week and day views, with a scrolling schedule and time-grid layouts.
- Agenda and Grid layouts, with separate settings for each view.
- Wallpaper colors, light and dark themes and icons, and English and French.
- Search, repeating events, multiple reminders, and calendar import and export.
- A resizable agenda widget with day dividers and adjustable transparency.

## Connect your computer

Open Settings > Computer connection, choose the calendars your computer can access, and pair over Wi-Fi or Tailscale. Start or stop access from your phone, and see recent changes in Activity history.

Follow the [connection setup](docs/AI-INTEGRATION.md#direct-phone-connection). The companion runs on Windows, macOS and Linux with Node.js 22 or newer.

## School schedules

Open Settings > Calendars > School schedule to connect Col.NET. Choose your school, then enter your Col.NET username and password. Selecting Collège Montmorency supplies its public portal address and time zone automatically; no browser login or copied link is needed. Only Montmorency has been tested. Another Col.NET school can be configured manually using its sign-in page address; other school portal systems are not supported.

School events go into an existing calendar beside personal events. Choose notifications for teacher absences, times, rooms, teachers, added or removed classes, assessments, school days and connection problems. Updates check every five minutes while open and use best-effort fifteen-minute background jobs; instant push is not available. Coverage depends on what the school publishes. Credentials are encrypted on the connected phone and are not included in releases. Google calendar full-resync behavior remains unverified.

## Build and checks

Requires Android 8.0 or newer. No advertising or analytics.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See [testing](docs/TESTING.md), [architecture](docs/ARCHITECTURE.md), [calendar imports](docs/MIGRATION.md) and [changes](CHANGELOG.md) for setup, checks and current limits.
