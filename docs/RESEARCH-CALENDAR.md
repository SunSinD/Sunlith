# Calendar research and decisions

Sources checked on November 4, 2026. This is a feature survey, not a market-share ranking. Selected public reviews illustrate needs; they do not establish how common an issue is.

The dated sections below record decisions through v0.5.0. For the current two presets,
theme controls, launcher artwork and navigation, see the [v0.6.0 refinement](RESEARCH-REFINEMENT.md).

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

## Interface decisions

The app uses a fixed six-row month grid with centered dates to keep its size predictable. A clear Today action and date buttons move between dates. The default agenda includes empty days so the sequence remains visible, with compact and event-days-only modes available. Settings are grouped; the system theme is the default, with a manual dark override and a startup-view choice.

The widget defaults to seven days, includes empty days and day dividers, and hides calendar names to reduce clutter. Its range is configurable from 1 to 31 days. Opacity defaults to 100%, adjustable in 5% steps. These are product decisions, not findings that one layout suits every user.

- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) recommends minimum 48dp interactive targets. Material controls provide useful defaults, but custom arrangements still need checking.
- [Android dark theme](https://developer.android.com/develop/ui/views/theming/darktheme) supports following the system theme, which Sunlith uses by default.
- [Compose drag, swipe and fling](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling) describes higher-level gesture APIs and state/animation handling used to guide navigation work.

The [Material 3 date-picker page](https://m3.material.io/components/date-pickers/overview) returned only a JavaScript-required shell when fetched. Its full guidance was not read; the Android documentation above was used instead. Device and accessibility testing remain necessary.

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

Computer control is a core Sunlith workflow: ask an assistant on your computer and let the MCP companion update the paired phone directly. The Android app and widget read those changes from the same on-device calendar provider. No separate calendar app or account sync is required for this connection. The interface stays focused on the calendar, and first-run setup offers computer pairing without requiring it.

Sunlith includes its own desktop stdio MCP companion in [`integrations/mcp`](../integrations/mcp), built with the official MCP SDK. Direct mode uses pinned HTTPS to manage chosen calendars through the phone's Android calendar provider, including phone-only calendars. The private listener runs only while the user enables Computer connection. A separate Google adapter uses Google's authentication library and Calendar REST API. The companion has no standalone Outlook or CalDAV account adapter.

MCP support depends on the client and transport; Sunlith does not claim every CLI supports it or that every listed provider has been tested with a real Google account. See [AI integration](AI-INTEGRATION.md) for provider-specific configuration and [AI research](RESEARCH-AI.md) for sources and verification limits. OAuth credentials and tokens belong outside the APK and repository.

The [open-source Google Calendar MCP server](https://github.com/nspady/google-calendar-mcp), published as `@cocal/google-calendar-mcp` when studied, informed the comparison with existing implementations. It is not a Sunlith dependency. OAuth apps left in Testing can have seven-day refresh-token lifetimes when requesting Calendar scopes; see [Google's token-expiration documentation](https://developers.google.com/identity/protocols/oauth2#expiration).

[Google's official Workspace MCP servers](https://developers.google.com/workspace/guides/configure-mcp-servers) are another option but currently require Developer Preview Program access, a Cloud project and OAuth configuration. Their [security setup](https://developers.google.com/workspace/guides/configure-mcp-security) is separate. They are not Sunlith's default dependency.

## Current limits

Existing device calendars and their recurring events are displayed through the provider. The editor handles whole simple recurring series. It refuses edits to series with exceptions or exception rows rather than damaging them; use the existing calendar app or service for those edits.

Unchanged reminder settings leave the existing provider reminder rows and methods intact. When reminder times change, unrelated email/SMS/default-offset rows are preserved. Editing or deleting meetings with attendee records is refused in this release; use the existing calendar app to review invitation or cancellation messages before sending them. Guest, read-only, exception-series and malformed-provider restrictions are exposed to the interface as typed edit-block reasons.

ICS import accepts files up to 5 MB and 2,000 events. It supports ordinary timed/all-day events, basic recurrence, text and start-relative minute reminders. It skips entire recurrence groups containing exceptions, explicit recurrence dates or exception rules and reports this. Unsupported alarms are reported. Guests are not invited; imported meetings become personal copies. Duplicate source UIDs are skipped. Files without UIDs use matching event details for deduplication; distinct supplied UIDs remain separate events. Import is atomic per event, not across the entire file; the report identifies partial results.

Timezone identifiers must be recognized by Android's IANA timezone database. Windows-style identifiers such as `Eastern Standard Time` are not mapped in this release; affected events are reported and skipped instead of being assigned a guessed timezone. Timed recurring events with nominal day/week durations in a timezone with clock changes are also reported and skipped when their duration cannot be preserved safely.

Imported reminders are trimmed to the destination calendar's supported count, methods and offset range, with a message; an unsupported reminder does not by itself discard the event. Google's maximum reminder offset is 40,320 minutes according to the [event reference](https://developers.google.com/workspace/calendar/api/v3/reference/events).

Export reads source events, not expanded occurrences. Timezones use IANA global identifiers without a runtime network request for timezone definitions. Receiving software must understand those identifiers. Malformed provider durations and affected recurring groups are skipped with export warnings; they are not silently changed into zero-duration events. Some unusual provider recurrence-date encodings are refused before writing. Proprietary metadata, task objects, attachments, guest lists and account credentials are outside the portable fields supported in this release.

Physical-device behavior, Google authorization, real account sync, Samsung battery behavior and individual launchers must be checked on the user's device. Emulator tests cannot prove those conditions.

## Dux Calendar

Added after a user recommendation. Dux Calendar is a Linux/FreeBSD desktop calendar from Dux Labs, built with Quickshell/QML and Go, rather than an Android application. Its documented strengths include a unified agenda across local, Google, Microsoft, CalDAV and iCloud calendars; background sync; keyboard navigation; quick search; configurable reminders; and account credentials stored in the system keyring.

The month and agenda source files show clear selected/today states, restrained event cards with a calendar-color stripe, readable time/title hierarchy, and condensed empty states. Search is debounced and separates loading, error and no-result states. Calendar-file import offers a destination picker, duplicate skipping and an advisory overlap preview. Those are useful patterns for Sunlith: readable agenda rows, clear selection, explicit import destinations and accurate error feedback. The Android app does not claim Dux's direct multi-provider sync, invitation RSVP, subscription support or import overlap preview.

Reviewed the README plus these source/documentation files, without copying code or assets:

- https://github.com/duxlabs-dev/dux-calendar
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modules/views/MonthView.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modules/views/AgendaView.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/quickshell/Modals/SearchModal.qml
- https://github.com/duxlabs-dev/dux-calendar/blob/master/docs/calendar-file-import.md

Dux Calendar's README screenshot/demo section was still a placeholder when checked. Visual observations above come from its source layout, not a running desktop app or a screenshot review.


## Customizable views, November 5, 2026

This review used official help pages, source repositories and issue reports. It is a feature comparison, not evidence that one layout suits everyone. Public issue reports describe individual experiences.

- [Google Calendar Android views](https://support.google.com/calendar/answer/6110849?hl=en&co=GENIE.Platform%3DAndroid) describes Schedule, Month and viewing multiple days. Sunlith offers a continuous schedule and hourly grids with its own implementation and appearance.
- [Business Calendar views](https://appgenix.uservoice.com/knowledgebase/articles/428420-introducing-the-different-views) and [week settings](https://appgenix.uservoice.com/knowledgebase/articles/430132-using-the-week-view) demonstrate why month, agenda and multi-day choices belong in separate settings.
- [Etar swipe feedback](https://github.com/Etar-Group/Etar-Calendar/issues/1535) illustrates accidental month changes when a curved vertical scroll is mistaken for a horizontal gesture. Swipes need direction lock, a clear threshold and an off switch.
- [Fossify month/agenda feedback](https://github.com/FossifyOrg/Calendar/issues/1111) and [day-grid request](https://github.com/FossifyOrg/Calendar/issues/374) show the tradeoff between readable lists and visible free-time gaps. Neither replaces the other.
- [Compose pager guidance](https://developer.android.com/develop/ui/compose/layouts/pager) distinguishes motion from a settled page. Sunlith commits the new date after a deliberate horizontal gesture, with visible movement and an animation-off option.
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) documents dynamic wallpaper colors on Android 12 and newer and fallback schemes for older phones. Dynamic colors must also feed Sunlith's custom palette, rather than only Material controls.
- [Scalable content](https://developer.android.com/develop/ui/compose/accessibility/scalable-content) and [accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) guide 200% text support and minimum interaction sizes.

Three starting presets expose a small choice first. Separate month, week and day settings allow mixing layouts afterward. Displayed calendars and the computer's access scope remain distinct choices. A floating Today action appears when the user has moved away; it is a product choice to keep the toolbar quiet, not a claim that research proves one universal placement.

The continuous agenda loads date chunks and keeps a bounded sliding cache. Its fixed month grid retains separate data, so scrolling far ahead does not empty the grid. A failed page load keeps the current rows and offers retry. A month, week or day range can replace the continuous agenda.

Official Maven metadata and POMs were checked. The existing BOM `2025.11.01` already includes stable Material3 `1.4.0` and Compose UI `1.9.5`. A trial of BOM `2026.09.00` failed Android metadata checks because its UI `1.12.1` requires compile SDK 37 and Android Gradle Plugin 9.1. The existing compatible BOM was retained; dynamic color needs no prerelease or toolchain migration. This corrects an initial secondary research claim that the existing BOM used Material3 `1.3.2`. The actual dependency POM is the source of truth.

## README and companion language

The [ncr README](https://github.com/greyxp1/ncr), [Fossify Calendar](https://github.com/FossifyOrg/Calendar) and [Etar](https://github.com/Etar-Group/Etar-Calendar) informed the short introduction, useful feature list and links to setup/testing. The repository keeps downloads in Releases and licensing in LICENSE.

The roughly 625-line companion remains JavaScript. [TypeScript](https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes.html) adds static checking; it does not by itself speed up the runtime. [Node's TypeScript support](https://nodejs.org/api/typescript.html) strips supported types without checking them. The companion already validates inputs with Zod, and conversion would add compiler/runtime-version and packaging work without an established performance benefit. Runtime validation and the integration tests remain necessary in either language.

## Vector icons and launcher styles, November 6, 2026

The sun/calendar reference is redrawn as SVG, with matching Android vectors for Light, Dark and Neutral styles. Android's [adaptive-icon guidance](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) specifies 108dp layers, a central 66dp safe zone and a monochrome layer for themed icons. The colour artwork fits inside that safe zone; the monochrome layer is slightly smaller to allow for its outline stroke.

The [PackageManager API](https://developer.android.com/reference/android/content/pm/PackageManager) supports changing enabled launcher aliases. API 33 and newer update the aliases in one batch; older versions enable the replacement before disabling the previous entry, with rollback on failure. A short launcher activity forwards to the stable main activity, keeping an open calendar independent of the alias being changed. It also preserves an open document picker when returning from the home screen. Startup reconciliation repairs an interrupted switch. The Settings previews use Android's adaptive icon drawable and the phone's icon mask.

Some launchers cache icon changes or remove the old home-screen shortcut. Emulator checks cover alias state, persistence, recreation and launching; Samsung shortcut migration, splash animations and physical Fold behavior need phone testing. Icon style is independent of the app's existing system, light and dark appearance options.
