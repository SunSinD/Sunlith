# Calendar usability refinement

## 0.6.7: direct selection and timeline scale

Day and Week start with List. The preset-owned Day timeline from earlier versions
returns to List once; custom and retired Schedule layouts retain their choices.
Appearance exposes Day layout and separate Day/Week timeline zoom levels from
50% to 200%. The compact 100% scale is 64dp per hour for both timeline implementations.
Zoom changes time spacing while retaining the system font size. The viewed minute is
captured before measurement can clamp the old pixel offset to a shorter grid. The
new axis restores that minute before revealing its events; explicit navigation keeps
priority over an older remap. Narrow columns still
shorten text; wide blocks have larger inner padding. Day omits the whole-column tint.

Explicit date selection lands directly at its destination. Finger paging and flings
retain their motion. Compose's
[requestScrollToPage](https://developer.android.com/reference/kotlin/androidx/compose/foundation/pager/PagerState)
and [requestScrollToItem](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState)
apply selection during measurement, keeping the date header and content aligned.
Initial focus is positioned before revealing event content rather than scrolling past
unrelated hours. Loading errors release the visibility gate and retain retry access.
The older animated-focus implementation described below is superseded.

Device checks also reproduced sideways diagonals scrolling the list vertically instead
of changing its period. Bounded Month agendas, Week lists and Day lists use the existing
Initial-pass direction handler: a clear sideways drag wins before the vertical child,
while a vertical-first gesture keeps its ownership. Neighbors prepare ahead of a drag
with motion enabled or disabled, and external date changes clear an unfinished offset
before drawing. List paging settles with the same spring as the native grid pager;
timelines retain their native pager so their readable columns can scroll sideways.
Each list page retains its saveable state by absolute period, including after a far
jump removes its composition, so returning restores the user's scroll position.

Computer connection reports whether this phone has a VPN address in the range
Tailscale uses. It refreshes while visible and pauses in the background using
[repeatOnLifecycle](https://developer.android.com/topic/libraries/architecture/coroutines#repeatonlifecycle).
[100.64.0.0/10](https://tailscale.com/docs/concepts/tailscale-ip-addresses) is shared
address space, so the UI says VPN address detected rather than promising Tailscale
identity, a reachable computer, or a working end-to-end connection.
Emulator frame measurements cannot establish zero stutter on a physical Fold5.

## Earlier design record

Research and design decisions through 0.6.4, checked 2026-11-08. These are product decisions
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
Day has no floating return-to-today control. Month and Week point toward an offscreen
period horizontally, or an offscreen time vertically, with an explicit accessible Today
label. A week-wide current-time line and restrained shading
identify the current day without changing event colors.
Bounded agendas save their last handled Today request alongside their scroll position.
Returning to today's page after a new request moves to today; ordinary navigation and
visits to other months retain the user's saved position.

From 0.6.4, school courses retain saved palette colors, and personal events use consistent
colors based on their titles. This follows feedback asking to distinguish events within
one calendar. Event blocks use these colors instead of provider calendar or event colors;
the provider values remain unchanged, and the editor's calendar dot still identifies
the destination calendar. Light and dark palettes keep text contrast above 4.5:1.
Week blocks give course titles priority over compact time labels and grow the hour height
to leave room for wrapped text. A narrow block shows the full time range when it fits,
otherwise just its start; multi-day labels are omitted rather than replaced with an
incorrect start from another date. The complete times and rooms remain in Details.

Large-text dimensions convert the base font or line height to dp before multiplying,
because Android text scaling is nonlinear. List titles, absence ranges and room lists
can wrap instead of losing information. Month chip capacity retains fractional pixel
spacing with a half-pixel layout tolerance, so rounding cannot hide the only event label.
Text direction follows the first strong character with a left-to-right fallback;
this keeps numeric time ranges chronological inside mirrored controls while retaining
the direction of Arabic or Hebrew event text.

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


## 0.6.5: continuous dates and compact time

Continuous timeline is a separate Week choice. A single LazyRow owns horizontal
motion; fixed date and all-day bands project its item positions during placement.
This follows [Compose lazy-list guidance](https://developer.android.com/develop/ui/compose/lists)
and [deferred state reads](https://developer.android.com/develop/ui/compose/performance/bestpractices).
Epoch-day keys keep dates stable. Scrolling reports the viewport without selecting
a date; explicit date navigation and Today move the strip. Queries run at rest and
retain a bounded event window. Unloaded dates show a loading label. This is an
implementation choice based on the reported two-stage week swipe, not a prescribed
Android calendar design. Continuous columns prioritize readable width and can show
partial dates; the fitted five-day option retains five at normal text sizes.

Group empty hours is off by default and applies only to Week timelines. Empty runs
of at least three hours are shown as labeled bands, with an hour of context around
occupied hours. An entirely empty grouped date range keeps the ordinary 24-hour axis.
Paged views use their displayed dates. Continuous views group hours shared by the
bounded loaded window, so entering dates cannot fall into a compressed band and
swipes do not repeatedly expand and compact the grid. An event on a loaded date
outside the viewport can therefore keep an hour expanded. Loading a new window or
changing the setting preserves the minute at the top after layout. Paged timelines share a minute anchor rather than a pixel offset. The
continuous all-day strip reserves a compact stable height to avoid shifting the
time grid when a new date enters. Tiny-event shortcuts reserve another row only
when relevant short events exist in the loaded window.
Width changes preserve a saved date and fractional column offset. During an explicit
navigation request, resize compensation waits for navigation to finish or be interrupted
by the user, then records the final position rather than cancelling that navigation.
Paged timelines keep Today request ownership in the ViewModel outside individual
pages, so a restored or newly loaded page can finish the request. The request and its
handled counter reset together with a fresh ViewModel. New date selections and Day
focus supersede an older Today request; a newer Today request also cancels older
Day focus. A changed time axis restarts an unfinished
Today or event-focus scroll; a deliberate touch interruption still ends the request.

Event text is vertically centered. Day uses more space per hour and a larger title
style; room codes remain whole and secondary fields are shown only in full. Short
labels may use free time below their exact start, stopping at the next event or
midnight. Adjacent tiny events retain their geometry and have a separate readable
shortcut opening full titles and times. Narrow overviews still shorten titles;
Details and list views retain full content. These choices reflect
[Android paragraph layout](https://developer.android.com/develop/ui/compose/text/style-paragraph)
and the practical limits described by
[WCAG text resizing](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html).
No claim of zero stutter or physical Fold5 behavior follows from source review.
Runtime and image evidence belong in TESTING.md.
