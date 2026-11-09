# Changes

## 0.6.6

- Show overlapping events side by side in narrow timeline columns, such as Week with larger text, instead of hiding them behind one count. Only crowded overlaps use a chooser; the first event stays visible, and a single hidden event opens directly and is announced by name.

## 0.6.5

- Add Continuous timeline in Week, with horizontal date scrolling and no week-page boundaries.
- Keep five normal-text date columns fitted on narrow cover-screen layouts. Large text can still scroll sideways.
- Add optional Group empty hours in Week, off by default, with labeled boundaries shared by all visible days.
- Center event labels vertically, give Day more room, and open full titles and times from a shortcut for short events, including tiny adjacent slots that cannot fit text.
- Point Today toward earlier or later periods horizontally, with mirrored arrows in right-to-left layouts.
- Keep the same visible hour while paging grouped timelines and use a responsive spring when a page settles.

## 0.6.4

- Give school courses consistent colors, with readable text and the same colors across calendar views.
- Give personal events consistent colors based on their titles; event blocks use these instead of provider calendar or event colors.
- Widen narrow timeline columns with sideways scrolling and enlarge all-day labels.
- Scale timeline space with large text, let list titles and room lists wrap, and retain event labels in the large-text month grid.
- Keep time ranges in start-to-end order when the surrounding layout is mirrored.
- Return to today's agenda after revisiting a month while preserving manual scroll during ordinary month navigation.
- Add a rolling five-day timeline under Appearance, alongside List, three days and seven days.
- Combine consecutive periods of the same verified school class while keeping each period's times and rooms in Details.
- Show teacher absences on the affected class, including the exact missed period when only part of a combined class is affected.
- Keep the original calendar rows, reminders and external event IDs unchanged when grouping the display.

## 0.6.3

- Add optional Col.NET school schedules to an existing calendar, alongside personal events.
- Choose Collège Montmorency to configure its public portal address and time zone automatically. Other Col.NET schools require manual setup and are untested.
- Include dated teacher absences, school days and source-provided deadlines, with separate notification choices for nine categories.
- Check for updates when connected, with a five-minute foreground throttle and best-effort fifteen-minute background scheduling. Instant push is not supported.
- Review existing imported class links and large removals before applying them; preserve personal events and keep events by default when disconnecting.
- Correct Today directions across sparse agendas and month grids, and let timeline gestures interrupt Today scrolling.
- Default new Week layouts to List while preserving existing choices; clarify the Month layout heading.

## 0.6.2

- Open Day with all hours by default; choose Events only or Compact schedule in General.
- Switch old Event cards preferences to All hours once; later explicit choices persist.
- Put General first and group settings by behavior, appearance and purpose.
- Restore the calendar outline in launcher icons and onboarding artwork.
- Remove the month grid's extra-event count and preview popup; every date opens Day directly.
- Share one time-column label between event cards starting at the same instant while keeping each event separate.
- Fit Computer connection controls at large text sizes.

## 0.6.1

- Distribute a nondebuggable, optimized APK with the existing signing certificate.
- Retain incoming calendar pages while swiping and prepare nearby event data away from the main thread.
- Start an opened day at the top, then scroll gently to its first timed event in every Day layout.
- Simplify Settings with compact choices, no root descriptions or separator rules, and separate About and Import/export pages.
- Keep agenda range controls near the top of General, including continuous and bounded ranges.
- Add the option to retain the selected date when changing views; the default returns to today.
- Remove the horizontal stripe from the launcher artwork and reopen the calendar after a confirmed icon change.
- Hide the redundant view picker when opening a date from the calendar.
- Preserve independent Week/Day choices when changing the Month layout, and fit setting values at large text sizes.
- Avoid an unnecessary Today scroll animation that could block an immediate Week swipe.

## 0.6.0

### Calendar

- Fit view controls and optional navigation arrows into the toolbar, with a menu on narrow screens.
- Add vertical lines and solid event colors to the full month grid.
- Preview full event titles and times by holding a date or tapping its extra-event count.
- Open a day at its relevant event with a brief highlight; Back returns to the parent calendar.
- Show both start and end times in week blocks and draw the current-time line across the week.
- Keep weekday labels consistent and hide the return-to-today shortcut in Day view.
- Use a compact up-arrow shortcut in Month and Week, easier swipes, and a simple fade between screens.

### Settings and setup

- Offer Agenda and Grid starting layouts with independent view choices.
- Simplify theme and wallpaper-color controls, consolidate settings, and keep About at the bottom.
- Keep Light and Dark icon choices, make the calendar page fill the launcher shape without a separate surround, and confirm before applying a style.
- Fix layout-chooser text contrast on dark backgrounds.
- Share a computer connection file directly through Android, retain scoped calendar permission, and add Open calendar.
- Add native widget configuration through supported launchers, without an extra widget button.

### Documentation

- Explain the three release assets and keep testing records in the testing document.
- Clarify one-time computer setup and later use over Wi-Fi or Tailscale.

## 0.5.0

- Replace the launcher artwork with a vector sun/calendar icon and selectable Light, Dark and Neutral styles.
- Refresh Today when the date changes and return to its row in a scrolling full-month grid.
- Keep the three-day week timeline loaded when selecting a date across a month boundary.
- Refresh the visible date range when changing the Week layout or number of days.
- Add regression coverage for both Monday-first and Sunday-first calendars.

## 0.4.0

- Choose Sunlith, Schedule or Planner layouts at first launch, then customize each view.
- Add a full month grid, hourly day and week timelines, and a continuous daily schedule.
- Restore animated swipes, hide navigation arrows by default, and show Today when needed.
- Make the Month agenda continuous by default, with optional month, week and day limits.
- Add Material You wallpaper colors and retain neutral light and dark themes.
- Open Settings directly from the toolbar and separate calendar display from computer access.
- Simplify setup, repository documentation and release descriptions.

## 0.3.0

- Align Day view event cards across different time labels.
- Keep widget clock times on one line, including noon in 12-hour format.
- Lead first-run setup with Computer connection and move it to the top of Settings.
- Add local computer connection activity history and recent contact information.

## 0.2.0

- Connect computer tools directly to calendars on the paired phone over Wi-Fi or Tailscale.
- Choose which calendars the computer can access and stop or revoke access at any time.
- Support direct event reads, creation, edits, deletion and calendar imports.
