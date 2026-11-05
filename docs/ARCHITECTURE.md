# Architecture

Sunlith is a native Kotlin/Jetpack Compose Android application with one app module and an optional standalone Node.js MCP companion.

## Android

- `data/`: CalendarContract access, date rules, and biweekly-based ICS transfer.
- `ui/`: Compose screens, presentation state, permission flows, and localized text.
- `system/`: local settings, reminder delivery, snooze, and background refresh jobs.
- `widget/`: a native RemoteViews collection widget backed by the same calendar provider.

CalendarContract is the event source of truth. There is no secondary event database, proprietary sync backend, AI model, advertising SDK, or Internet permission in the APK. Google, Exchange, CalDAV, and other account sync depend on the sync adapter installed on the phone. Merely displaying a calendar does not make Sunlith its sync provider.

Per-app calendar selection, appearance, and notification choices are stored locally. Visibility choices do not change other calendar apps' settings. Provider reads and writes run off the UI thread. Instances queries expand recurrence in bounded date ranges; editing uses the master event record.

The month view uses a fixed six-row grid and an agenda limited to that month. Week view shows exactly seven days. Empty days are included by default, with compact and event-days-only modes available for month view. The calendar header stays visible while the agenda scrolls unless the collapse setting is enabled. Navigation uses the date buttons and Today. Grouped settings offer the system theme by default, an optional dark override, and a startup view of Month, Week, Day, or last used. These choices change presentation, not provider data.

All-day dates use UTC-midnight boundaries with an exclusive end. Timed events preserve their timezone. Device-specific calendar selections and notification state are excluded from application backup. Recurring provider writes use duration instead of an ordinary end field. Android expands rules and exceptions for display; unsafe edits are rejected with an explanation.

The agenda widget uses standard Android RemoteViews with a list service, rather than embedding an activity or polling through a foreground service. Its configurable range is 1–31 days, default seven. Empty days and day dividers are shown by default; calendar names are hidden. Opacity defaults to 100% and changes in 5% steps. It updates after app edits, content-URI changes, supported locale/time-zone broadcasts, and Android's periodic widget refresh. Manifest delivery of `DATE_CHANGED` is not relied upon for an immediate midnight update; periodic widget refresh and the six-hour maintenance job provide fallback updates. Android battery and process restrictions still apply.

Reminder scheduling reads actual reminder offsets from the provider. Sunlith schedules its next upcoming reminder, recomputes it after changes and reboot, and listens for the provider's reminder broadcast. Notification history prevents the two paths from displaying the same occurrence twice. Snooze uses a separate alarm and persists its target time so it can be restored after reboot. Denied notification access and unavailable exact alarms are handled without requiring a persistent service. Calendar-default reminder offsets depend on the calendar provider's due-alert broadcast, because Android exposes no standard API for resolving another provider's default offset. The app does not consume or dismiss other calendar apps' alert rows. Users can turn Sunlith reminders off to avoid multiple calendar apps notifying for the same event.

`system/CalendarHandoff.kt` validates incoming calendar attachments and standard `ACTION_INSERT` events. `MainActivity` waits for setup and calendar permission before opening the existing import preview or an editable personal-event draft. Incoming requests do not write provider data until the user confirms Import or Save. File handoff accepts content URIs with supported calendar MIME types; insertion requires explicit valid start/end times and rejects recurrence and guest extras. There is no external edit/delete intent or remote phone-calendar API.

## MCP

The companion in `integrations/mcp` is an optional stdio server running on the user's computer. It uses the official MCP SDK and Google authentication library, with Google Calendar REST endpoints. Authentication is separate from Android's calendar permission.

Desktop OAuth uses PKCE, a random state, and a temporary loopback callback. Credentials and tokens stay outside the repository and APK. The companion requests event access and read-only calendar-list access. No public network endpoint is exposed.

The tools use explicit calendar/event IDs, validated date ranges, optimistic concurrency through etags, and stable operation IDs for retried creation. Guest-event mutation is blocked. Google remains the shared backend; the phone's usual account sync transfers the result into CalendarContract.

## Design scope

This release distributes the companion as one portable source archive for Windows, macOS, and Linux, using the same Node.js runtime required by MCP clients. Separate native installers would introduce signing, notarization, updates, and platform packaging for a setup utility. They are not part of this release.

A small, temporary local browser setup wizard can be considered after real-phone testing. It would reuse the existing Google authorization flow, remain bound to loopback, and provide copyable client configuration. It would not be a hosted service, phone server, or desktop calendar. This is a possible follow-up, not an implemented feature.

The interface uses familiar month-and-agenda patterns without copying another application's source or visual assets. The priorities are readability, fast access to events, a useful home-screen widget, and portable calendar data. See the research documents for sources and limits.
