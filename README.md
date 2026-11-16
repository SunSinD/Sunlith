# Sunlith

A clean calendar app for your phone, with direct computer control.

Plan with the calendars already on your phone. You can also ask your computer to add, edit and remove events without a second calendar app or a Google account.

<p>
  <img src="docs/images/month.png" alt="Month view in dark mode with sample classes" width="32%" />
  <img src="docs/images/week.png" alt="Week timeline in dark mode with three visible days" width="32%" />
  <img src="docs/images/day.png" alt="Day cards in dark mode with class times and rooms" width="32%" />
</p>

Month, Week and Day in dark mode. Screenshots use sample events.

## Make it yours

Choose month, week or day views, with lists and timelines. Hold a view tab to change its layout. Pinch a Week timeline to zoom.

Timelines show every hour by default. Group empty hours in Week is optional in Appearance. Opening a date from the Month grid shows that date alone as a list, with a toolbar button to switch to All hours.

Week is one continuous timeline that you scroll day by day. Choose 3, 5 or 7 days, and every swipe settles on whole days. At normal text sizes the chosen days fit the screen. Selectable week headers are optional. Events at the same time sit side by side in separate rounded cards when there is room. When they would be too narrow to read, they are grouped into one card with a count, and tapping it shows them. Short events too close together for their titles share one compact card with a count, and tapping it lists each event with its times.

Absent and cancelled classes get soft diagonal stripes. A partial absence stripes only the missed time. Background tint in Appearance sets how strong the empty hour tiles look.

Use wallpaper colors, light or dark themes, English or French, repeating events, reminders, search and calendar import or export. Mark events as tasks and check them off when they start. Add the agenda widget to your home screen.

## Connect your computer

Open Settings > Computer connection, choose which calendars to share, and pair over WiFi or Tailscale. Start or stop access from your phone and see changes in Activity history.

Follow the [connection setup](docs/AI-INTEGRATION.md#direct-phone-connection). The companion works on Windows, macOS and Linux with Node.js 22 or newer.

## School schedules

Open Settings > Calendars > School schedule to connect Col.NET. Select Collège Montmorency and enter your Col.NET username and password. Its portal address and time zone are filled in for you. Other Col.NET schools need manual setup and have not been tested.

School events appear beside your personal events. Choose alerts for cancelled classes, schedule changes, assessments, school days and connection problems. Sync checks every five minutes while open. Background checks run about every fifteen minutes when Android allows them. Updates depend on what the school publishes.

School sync connects directly from your phone. With a VPN active, it tries a direct network when Android permits it, then falls back to the default route if the connection fails. Network restrictions and portal outages can still prevent a connection.

Open College messages in School schedule for messages from the Col.NET administration. Their titles and text stay in the original language. Recent messages and your credentials are encrypted on your phone. If the administration page is not recognized, the screen offers a link to Col.NET. The administration list and a message detail have been checked against the Montmorency portal.

If a full message cannot be saved within the phone cache limit, its title stays visible with a link to Col.NET. Source text is never shortened to fit the cache.

Recognized dated notices appear as calendar events. A confirmation or correction updates the notice for that day. Each notice includes its source and exceptions. Confirmed notices that cover regular classes can mark them as cancelled while keeping their times and event IDs. Limited or unclear notices stay as notices for you to check. Other deadlines open in the editor for you to review before saving.

Enable Administration messages in School schedule alerts for new messages and corrections. Checks run every five minutes while Sunlith is open and about every fifteen minutes in the background when Android allows them. Opening College messages also checks for updates. These are periodic checks, so delivery is not instant. Google Calendar sync after a full reset has not been verified.

## Build and checks

Requires Android 8.0 or newer. No ads or analytics.

Open Settings > About > Check for updates to get the latest GitHub release from the app. Android asks you to confirm installation. Sunlith only checks for app updates when you press the button.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See [testing](docs/TESTING.md), [architecture](docs/ARCHITECTURE.md), [calendar imports](docs/MIGRATION.md) and [changes](CHANGELOG.md).
