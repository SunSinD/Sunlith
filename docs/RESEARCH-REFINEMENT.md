# Calendar usability refinement

Research and design decisions through 0.6.2, checked 2026-11-07. These are product decisions
informed by documentation and the reported screenshots, not results of a user study.

## Calendar navigation and event visibility

[Google Calendar's Android help](https://support.google.com/calendar/answer/6110849?hl=en&co=GENIE.Platform%3DAndroid)
explicitly names Schedule and Month, describes a list of each day's events, and supports
changing view and jumping to today. It does not enumerate every view or specify the
visual treatment of overflow. Sunlith keeps two distinct starting layouts: Agenda for a
month above an event list, and Grid for event titles in the month and hourly timelines.
Month and Week choices remain in Appearance; the Day view choice lives in General.

A fixed-height month cannot display arbitrarily long titles or unlimited events without
losing its overview. Growing rows makes the month jump and scrolling every cell is hard
to discover. Every date now opens Day directly, including empty and crowded dates.
The former extra-event count, long-press preview and bottom sheet were removed after
feedback that their behavior was inconsistent and hard to discover. Every event
remains available in Day; the month is a bounded overview rather than a complete list.

Opening Day or explicitly picking a date requests its first timed event, including
today, with a brief focus ring. The top renders before the gentle scroll begins;
empty and all-day-only dates stay at the top. Reloads, swipes and recreation do not
repeat consumed focus requests. Back restores the parent calendar.
Continuous agenda loading waits for user scrolling: programmatic positioning must not
extend the date range and restart its own focus request. A distant-date regression
covers this interaction with many all-day entries above the timed event.
Day has no floating return-to-today control. Month and Week retain an up arrow with an
explicit accessible Today label. A week-wide current-time line and restrained shading
identify the current day without changing event colors.

Calendar event fills use provider event colors, with calendar colors as the provider's
fallback. Text selects black or white by contrast. This avoids a shared surface making
different calendar colors appear identical. The time range gets wrapping space before
extra title lines in week blocks. Blocks have a readable minimum height; when the time
range uses that height, the full title remains available by opening the event.

## Gestures, space and motion

[Compose paging](https://developer.android.com/develop/ui/compose/layouts/pager)
provides the standard horizontal pager used for the full grid, bounded Agenda,
Week list and hourly/card pages.
Absolute period keys retain the incoming composition when its date is committed after
settling. The former relative slots rebuilt the arriving grid at this point. Adjacent
pages and their data are buffered; event merging and date indexing run off the UI
thread, following [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance/bestpractices).
Continuous Agenda retains a shared horizontal drag with absolute period keys.
Their drag detector uses the Initial pointer pass, keeps excess movement beyond touch
slop and yields after vertical scrolling wins. See
[gesture propagation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures#event-propagation).

The first explicit Day scroll uses measured pixel movement rather than a long-distance
`animateScrollToItem`, whose implementation can skip distant content. Timeline motion
also scales with distance. Reduced motion remains immediate. These mechanisms need
runtime checks of both content position and rendered frames; stable composition alone
does not prove smooth animation on a physical phone.

View controls share the toolbar with the date, search, settings and optional arrows.
Narrow screens and large text use a view menu to preserve touch targets. With arrows
enabled on a narrow screen, search moves into that menu so the date remains readable.
The title uses a localized numeric month/day when the written date cannot fit, while
its spoken label retains the full date. Weekday labels
share typography across views. Screen navigation uses a brief fade without size or
scale effects; Android's reduced-motion preference and the app's animation switch apply.
These choices require emulator and device checks; no smoothness benchmark is implied.

## Appearance and launcher icons

System chooses light or dark. Wallpaper colors independently select Android 12+
[dynamic color](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color).
Earlier Android versions use the app palette. This separation avoids implying that
Material You is synonymous with dark mode. The duplicate Neutral choice is removed.
Legacy Schedule layouts keep their individual settings.

[Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)
use separate 108dp layers and a central safe area, then let the launcher choose its mask.
A transparent layer is not a reliable cutout: AOSP's
[Android 16 AdaptiveIconDrawable.draw](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java)
paints black before the layers (also verified in Android 11). Transparent corners
therefore become black inside the mask. The
[Launcher3 icon factory](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/heads/main/iconloaderlib/src/com/android/launcher3/icons/BaseIconFactory.java)
wraps legacy icons with an opaque white background instead. Neither route reliably
provides a background-free silhouette.

Light and Dark now use the calendar paper itself as the adaptive surface, cream and
charcoal respectively. A rounded calendar outline now connects the pins, sun and
date dots into a recognizable calendar; the earlier outline-free symbol appeared
to float. The launcher still masks the opaque paper surface. The calendar border
does not restore the removed horizontal header stripe or a separate card shadow.
The SVG references use the same paper colors. Themed icons still use a launcher-chosen
filled shape; an app cannot require an unmasked icon on every home screen.
The monochrome layer remains available for themed icons. A short confirmation applies
the new style and reopens the app task. PackageManager requests the component change
immediately; third-party launcher cache timing remains outside the app's control.

### 0.6.1 feedback: icon and settings

The reported horizontal mark was the calendar header path in the artwork, not a
launcher rendering defect. It is removed from both styles, the legacy reference
and the themed layer. Pins, sun and date dots remain.

The previous `recreate()` rebuilt the same activity in place, which did not match
the notice promising a reopen. Applying a confirmed icon now uses Android's
[makeRestartActivityTask](https://developer.android.com/reference/android/content/Intent#makeRestartActivityTask(android.content.ComponentName))
to open a fresh calendar task without killing the process. The ViewModel owns
application of the icon and persists a pending reopen request, consumed by a resumed
activity, so leaving Appearance or recreation cannot cancel a successful reopen.

Settings uses seven single-line entries without separator rules or calendar-count
subtitles. About and Import and export are separate pages. The primary Agenda/Grid
choice appears only in Appearance and changes only Month after setup, preserving
custom Week and Day choices. Week alternatives stay in Appearance; the Day view
menu is in General. General keeps startup, date retention, week start, agenda range,
agenda days, navigation and reminders. The agenda range is one visible menu with
Continuous, Month, Week and Day, rather than four permanently expanded choices.
These choices follow the Android
[settings pattern](https://developer.android.com/develop/ui/views/components/settings)
and Material [menus](https://m3.material.io/components/menus/guidelines).

### 0.6.2 feedback: settings order, hours and simultaneous events

The root order is General, Appearance, Calendars, Widget, Computer connection,
Import and export, About. General begins with startup, Day view and week start,
then groups Agenda, Navigation and Reminders. Appearance groups Layout, Colors
and App icon. Calendar selection, widget options, connection permissions, transfer
and About each retain their own page. This follows the
[AOSP recommendation to place frequent settings first](https://source.android.com/docs/core/settings/settings-guidelines)
and Android's guidance to [group related settings](https://developer.android.com/develop/ui/views/components/settings/organize-your-settings).
Neither source prescribes this exact order; it reflects the reported app usage.

Day view offers All hours, Events only and Compact schedule in one menu. All hours
is the default and keeps the complete hourly rail, including midnight endpoints.
Old Cards preferences migrate once to Timeline because older versions did not
record whether Cards was a default or an explicit choice. Existing Timeline and
Schedule are retained; choosing Events only after the upgrade persists normally.
Changing the Month layout continues to preserve independent Week and Day choices.

Events only shows one left-hand time label for consecutive events with exactly
the same start instant. The event cards remain separate, aligned and independently
tappable, with their own full durations. Different instants stay separate even
when clock formatting produces the same text, including repeated daylight-saving
hours. Stable per-event list keys and row counts preserve focus and restoration,
following [Compose list guidance](https://developer.android.com/develop/ui/compose/lists#item-keys).
The hourly timeline continues to place overlapping events in separate columns.

## Connection and widgets

See [connection research](RESEARCH-CONNECTION-UX.md) for Tailscale identity versus app
authorization, scoped one-time sharing, and native widget configuration. Setup retains
calendar consent while removing the manual save-file step. Open calendar is an explicit
exit. Native widget reconfiguration uses the launcher's existing long-press menu;
the settings apply to all Sunlith widgets and the screen states this.

## Accessibility and verification scope

[Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
recommends 48dp targets and [WCAG text contrast](https://www.w3.org/TR/UNDERSTANDING-WCAG20/visual-audio-contrast-contrast.html)
requires 4.5:1 for ordinary text. Toolbar controls retain 48dp targets and full spoken
dates. Month cells expose a direct Open day action and event counts. Dense timeline blocks
can be smaller; readable-list fallbacks handle narrow columns and large fonts.
TalkBack descriptions retain full titles and times. Emulator checks do not establish
comfort on every phone, launcher behavior, or TalkBack interaction quality.

Build, regression and integration results belong in [TESTING.md](TESTING.md).
