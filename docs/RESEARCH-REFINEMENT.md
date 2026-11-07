# Calendar usability refinement

Research and design decisions for 0.6.0, checked 2026-11-06. These are product decisions
informed by documentation and the reported screenshots, not results of a user study.

## Calendar navigation and event visibility

[Google Calendar's Android help](https://support.google.com/calendar/answer/6110849?hl=en&co=GENIE.Platform%3DAndroid)
explicitly names Schedule and Month, describes a list of each day's events, and supports
changing view and jumping to today. It does not enumerate every view or specify the
visual treatment of overflow. Sunlith keeps two distinct starting layouts: Agenda for a
month above an event list, and Grid for event titles in the month and hourly timelines.
Individual Month, Week and Day choices remain available.

A fixed-height month cannot display arbitrarily long titles or unlimited events without
losing its overview. Growing rows makes the month jump and scrolling every cell is hard
to discover. Holding a date or tapping its extra-event count opens a scrollable sheet
with full titles and time ranges. Date tapping still opens Day. This uses the
[Material bottom-sheet pattern](https://m3.material.io/components/bottom-sheets/guidelines);
it is Sunlith's choice, not a claim about Google's implementation.

Opening Day or explicitly picking a date requests its first timed event, or today's
next/current event, with a brief focus ring. Reloads, swipes and recreation do not
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

[Compose gesture propagation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures#event-propagation)
explains Initial, Main and Final pointer passes. The calendar claims deliberate sideways
movement on Initial, at a 1.4 horizontal-to-vertical ratio. Once vertical scrolling wins,
curving sideways cannot change the period. The first movement retains its excess beyond
touch slop, matching the framework's drag detector. Full-month neighbors are prepared
after the first frame to avoid composing them at gesture recognition. Timelines keep
on-demand neighbors so they inherit the current hour position.

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
Legacy Schedule layouts keep their individual settings and appear as Custom.

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
charcoal respectively. The separate card shadow and outline are removed; the launcher
mask becomes the page outline. Pins, sun and date dots retain their positions. This
removes the separate white/black surround without depending on broken transparency.
The SVG references use the same paper colors. Themed icons still use a launcher-chosen
filled shape; an app cannot require an unmasked icon on every home screen.
The monochrome layer remains available for themed icons. A short confirmation applies
the new style and refreshes the activity. PackageManager requests the component change
immediately; third-party launcher cache timing remains outside the app's control.

## Connection and widgets

See [connection research](RESEARCH-CONNECTION-UX.md) for Tailscale identity versus app
authorization, scoped one-time sharing, and native widget configuration. Setup retains
calendar consent while removing the manual save-file step. Open calendar is an explicit
exit. Native widget reconfiguration uses the launcher's existing long-press menu;
the settings apply to all Sunlith widgets and the screen states this.

## Accessibility and verification scope

[Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
recommends 48dp targets and [WCAG text contrast](https://www.w3.org/TR/UNDERSTANDING-WCAG20/visual-audio-contrast-contrast.html)
requires 4.5:1 for ordinary text. Toolbar controls retain 48dp targets and the full date
provides a long-press alternative to its small overflow count. Dense timeline blocks
can be smaller; readable-list fallbacks handle narrow columns and large fonts.
TalkBack descriptions retain full titles and times. Emulator checks do not establish
comfort on every phone, launcher behavior, or TalkBack interaction quality.

Build, regression and integration results belong in [TESTING.md](TESTING.md).
