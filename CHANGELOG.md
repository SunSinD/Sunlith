# Changes

## 0.6.10

Event details keep school status visible while data refreshes. Screen transitions keep their backgrounds covered, and Search and To do no longer briefly show a loading line under the toolbar. Appearance descriptions are shorter.

Week cards keep time labels on one line and separate the event count into a badge. The event chooser has outlined rows with arrows and less empty space.

Timeline focus and page changes position events before drawing. Pinch zoom returns gently to its limits. Day leaves a little more space above midnight and less space below an empty evening, while late events can still scroll above the floating buttons.

Opening a date from the Month grid shows only that date, with no week strip or floating Today arrow. A toolbar button switches between List and All hours and remembers that choice separately from Day. Timelines still show all hours by default, and grouping empty hours in Week stays optional.

College messages supports the login form Col.NET sends to Android. Empty portal links open Col.NET directly, and connection failures show their reason. Verified cancellation notices can mark affected regular classes without changing their times or event IDs. Corrections restore them, and uncertain or limited notices stay separate.

## 0.6.9

Five day timelines now show Monday to Friday by default. Rolling days remain available, and earlier choices are preserved. Week headers highlight the focused date as you page. Selecting those headers is optional and off by default. Seven day timelines fit the week at normal text sizes.

Events at the same time sit side by side in separate rounded cards with space between them. When they would be too narrow to read, they are grouped into one card with a count that opens their list. Narrow cards beside each other center their titles. Titles wrap between words instead of breaking into fragments. In Day, short events too close together for their titles share one compact card at the first start time. It is one title line tall and shows a count of the other events, and tapping it lists each event with its exact times. They no longer look like events at the same time, and later events keep their own cards.

Pinch Week timelines to zoom around the time under your fingers. A deliberate change in finger spacing starts the gesture, and Week saves its zoom. Day uses a fixed time scale. Rounded hour tiles, a stronger today tint and a current time line kept within today improve the timeline. Hold a Month, Week or Day tab, or its compact menu entry, to choose a layout.

Settings screens keep the calendar covered during transitions. Large school imports use bounded steps that can recover after an interruption, preserving linked class IDs.

Messages opens directly to Col.NET administration notices, with an encrypted copy on your phone. Titles and message text keep their original language. School alerts can notify you about new messages and corrections. Recognized dated notices appear in the school calendar. Confirmations and corrections update the notice for that day, with its source and exceptions. Class events stay intact. Other deadlines open in the editor for review before saving.

Administration checks run every five minutes while the app is open and about every fifteen minutes in the background when Android allows them. Delivery is not instant.

## 0.6.8

- Add an optional Monday to Friday workweek for the five day Week timeline. Rolling days remain the default, and List remains the default layout.
- Keep the Month Today arrow steady during the return, and add directional Today arrows in Day.
- Show short Day timeline titles directly, using separate label lanes and room below midnight when needed.
- Tag events as To-do in the editor or Details. They enter the To-do list when they start, with completion and undo saved on this phone.
- Show a small To-do symbol beside tagged events across Month, Week and Day.
- Let school sync use a working direct phone network when a VPN is active and Android permits it, with a fallback if that route fails. School sync does not need a computer connection.

## 0.6.7

- Restore List as the default Day layout alongside Week. Existing custom layouts stay selected.
- Add separate Day and Week timeline zoom settings in Appearance, from 50% to 200%, with a smaller default time scale.
- Switch selected dates directly to their events, keeping the header, page and initial scroll position together.
- Keep sideways diagonal swipes paging Month, Week and Day lists while vertical-first gestures keep scrolling the list.
- Keep each list page's saved scroll position when returning after a distant date or Today jump.
- Preserve the viewed time when zooming, reveal compact grouped weeks promptly, and retain Retry after a failed Day List load.
- Give wider timeline blocks more text padding and remove the whole-column highlight in Day.
- Show the phone’s local Tailscale-range VPN status in Computer connection, refresh it while the screen is open, and shorten connection help.

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
