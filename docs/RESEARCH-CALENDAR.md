# Calendar research and decisions

Sources checked on November 4, 2026. This is a feature survey, not a market-share ranking. Selected public reviews illustrate needs; they do not establish how common an issue is.

## What Sunlith borrows

| App | Useful ideas | Primary source |
| --- | --- | --- |
| Google Calendar | Account sync, multiple calendars, clear schedule views, event colors | [Google Calendar](https://calendar.google.com/calendar/about/) |
| Samsung Calendar | Transparent widgets, adjustable transparency, recurrence and multiple reminders | [Google Play](https://play.google.com/store/apps/details?id=com.samsung.android.calendar) |
| DigiCal | Month with agenda, customizable widgets, snooze, local calendars | [DigiCal](https://digibites.nl/) |
| Business Calendar 2 | Flexible week view, bulk editing, widget configuration | [Appgenix](https://www.appgenix-software.com/) |
| aCalendar | Fast navigation, native Android backend, flexible repeats, ICS export | [Google Play](https://play.google.com/store/apps/details?id=org.withouthat.acalendar) |
| Outlook | Consolidated accounts and calendars, schedule/reminder integration | [Google Play](https://play.google.com/store/apps/details?id=com.microsoft.office.outlook) |
| Fantastical | Readable event hierarchy, calendar sets, templates and timezone clarity | [Fantastical](https://flexibits.com/fantastical) |
| Any.do | Glanceable daily agenda and home widget | [Any.do](https://www.any.do/calendar) |
| TimeTree | Calendar filters and shared group calendars | [TimeTree](https://timetreeapp.com/intl/en/) |
| Proton Calendar | Privacy, recurring events, multiple reminders | [Google Play](https://play.google.com/store/apps/details?id=me.proton.android.calendar) |
| Fossify | Offline use, no ads, portable data, configurable views/widgets | [Fossify](https://www.fossify.org/apps/calendar/) |
| Etar | Shared Android calendars, offline support and agenda widget | [Repository](https://github.com/Etar-Group/Etar-Calendar) |

Fantastical's site currently advertises Android phone support for 2027; it is design inspiration, not a verified Android phone option today. Direct Samsung Calendar and DigiCal subpages returned 404; the linked official Play listings and DigiCal homepage were readable. DigiCal's Zendesk help center returned 403, so an exact DigiCal export menu could not be verified. Outlook's direct marketing page timed out; its official Play listing was readable. Proton's marketing page yielded no usable text; its official Play listing was readable.

Public Play review snapshots praise aCalendar's navigation, colors, all-day visibility and syncing. DigiCal reviews report delayed or missing reminders and sync difficulties. Proton reviews request search and dependable, readable widgets. These support prioritizing reliability, readable widgets, search and speed. They are individual reports, not independently reproduced product defects.

## Architecture

Sunlith uses Kotlin and native Android UI. Its data layer reads and writes the shared Android Calendar Provider. Sync belongs to installed account adapters, including Google or a CalDAV adapter; Sunlith does not implement its own cloud service.

- [Calendar Provider documentation](https://developer.android.com/identity/providers/calendar-provider): read/write calendar permissions, read-only calendar access, `Instances` recurrence expansion, recurrence `DURATION`, all-day UTC and local calendars.
- [Widget overview](https://developer.android.com/develop/ui/views/appwidgets/overview): resizable layouts, collection widgets and interaction constraints.
- [Widget updates](https://developer.android.com/develop/ui/views/appwidgets/advanced): `updatePeriodMillis` has a 30-minute minimum; background restrictions still apply.
- [Glance](https://developer.android.com/develop/ui/compose/glance): Compose-style widget toolkit; classic RemoteViews also remain valid.
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): Android 13+ runtime permission.
- [Alarms](https://developer.android.com/develop/background-work/services/alarms) and [Android 14 changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms): exact alarm access, calendar-app permission eligibility and rescheduling.
- [CalendarAlerts](https://developer.android.com/reference/android/provider/CalendarContract.CalendarAlerts): reminder broadcasts and avoiding interference with another calendar app.
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files): system picker for ICS files without broad file permissions.
- [RFC 5545](https://datatracker.ietf.org/doc/html/rfc5545): ICS dates, escaping, line folding, recurrence and alarms.
- [biweekly](https://github.com/mangstadt/biweekly): Android-compatible ICS parser/writer, FreeBSD/BSD license; version 0.6.8 verified on Maven Central.

Database operations run off the main thread. Edits to synced calendars use normal application writes, allowing their installed sync adapter to propagate changes. Only creating Sunlith's local calendar uses the documented local sync-adapter URI pattern.

Android's provider interprets duration days as fixed 24-hour periods, as shown by [`Duration.getMillis()` in AOSP](https://android.googlesource.com/platform/frameworks/opt/calendar/+/refs/heads/main/src/com/android/calendarcommon2/Duration.java). RFC iCalendar nominal days can follow timezone transitions. The data layer keeps these operations separate and preserves an existing provider duration when an edit does not change event timing.

## AI control

AI control is a core Sunlith workflow: ask an assistant on your computer, let the MCP companion update the shared calendar service, and receive the result in the Android app and widget through normal account sync. The interface remains a calendar rather than an embedded chatbot. First-run guidance recommends pairing an assistant while keeping the calendar usable before account authorization is complete.

Sunlith includes its own desktop stdio MCP companion in [`integrations/mcp`](../integrations/mcp), built with the official MCP SDK and Google authentication library. Its first calendar-service adapter supports Google Calendar. Android calendars exposed by Outlook or CalDAV account adapters can appear in the native app, but the companion does not yet control those services or phone-only calendars. T3 Code mobile access operates through a configured host/provider; the APK does not host a network server.

MCP support depends on the client and transport; Sunlith does not claim every CLI supports it or that every listed provider has been tested with a real Google account. See [AI integration](AI-INTEGRATION.md) for provider-specific configuration and [AI research](RESEARCH-AI.md) for sources and verification limits. OAuth credentials and tokens belong outside the APK and repository.

The [open-source Google Calendar MCP server](https://github.com/nspady/google-calendar-mcp), published as `@cocal/google-calendar-mcp` when studied, informed the comparison with existing implementations. It is not a Sunlith dependency. OAuth apps left in Testing can have seven-day refresh-token lifetimes when requesting Calendar scopes; see [Google's token-expiration documentation](https://developers.google.com/identity/protocols/oauth2#expiration).

[Google's official Workspace MCP servers](https://developers.google.com/workspace/guides/configure-mcp-servers) are another option but currently require Developer Preview Program access, a Cloud project and OAuth configuration. Their [security setup](https://developers.google.com/workspace/guides/configure-mcp-security) is separate. They are not Sunlith's default dependency.

## First alpha limits

Existing device calendars and their recurring events are displayed through the provider. The editor handles whole simple recurring series. It refuses edits to series with exceptions or exception rows rather than damaging them; use the existing calendar app or service for those edits.

Unchanged reminder settings leave the existing provider reminder rows and methods intact. When reminder times change, unrelated email/SMS/default-offset rows are preserved. Editing or deleting meetings with attendee records is refused in this alpha; use the existing calendar app to review invitation or cancellation messages before sending them. Guest, read-only, exception-series and malformed-provider restrictions are exposed to the interface as typed edit-block reasons.

ICS import accepts files up to 5 MB and 2,000 events. It supports ordinary timed/all-day events, basic recurrence, text and start-relative minute reminders. It skips entire recurrence groups containing exceptions, explicit recurrence dates or exception rules and reports this. Unsupported alarms are reported. Guests are not invited; imported meetings become personal copies. Duplicate source UIDs are skipped. Files without UIDs use matching event details for deduplication; distinct supplied UIDs remain separate events. Import is atomic per event, not across the entire file; the report identifies partial results.

Imported reminders are trimmed to the destination calendar's supported count, methods and offset range, with a message; an unsupported reminder does not by itself discard the event. Google's maximum reminder offset is 40,320 minutes according to the [event reference](https://developers.google.com/workspace/calendar/api/v3/reference/events).

Export reads source events, not expanded occurrences. Timezones use IANA global identifiers without a runtime network request for timezone definitions. Receiving software must understand those identifiers. Malformed provider durations and affected recurring groups are skipped with export warnings; they are not silently changed into zero-duration events. Some unusual provider recurrence-date encodings are refused before writing. Proprietary metadata, task objects, attachments, guest lists and account credentials are outside the portable fields supported in this alpha.

Physical-device behavior, Google authorization, real account sync, Samsung battery behavior and individual launchers must be checked on the user's device. Emulator tests cannot prove those conditions.

## Dux Calendar

Added after a user recommendation. Dux Calendar is a Linux/FreeBSD desktop calendar from Dux Labs, built with Quickshell/QML and Go, rather than an Android application. Its documented strengths include a unified agenda across local, Google, Microsoft, CalDAV and iCloud calendars; background sync; keyboard navigation; quick search; configurable reminders; and account credentials stored in the system keyring.

The month and agenda source files show clear selected/today states, restrained event cards with a calendar-color stripe, readable time/title hierarchy, and condensed empty states. Search is debounced and separates loading, error and no-result states. Calendar-file import offers a destination picker, duplicate skipping and an advisory overlap preview. Those are useful patterns for Sunlith: readable agenda rows, clear selection, explicit import destinations and accurate error feedback. The initial Android alpha does not claim Dux's direct multi-provider sync, invitation RSVP, subscription support or import overlap preview.

Reviewed the README plus these source/documentation files, without copying code or assets:

- https://github.com/duxlabs-dev/dux-calendar
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modules/views/MonthView.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modules/views/AgendaView.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modals/SearchModal.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/docs/calendar-file-import.md

The README's screenshot/demo section was still a placeholder when checked. Visual observations above come from its source layout, not a running desktop app or a screenshot review.
