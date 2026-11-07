# Changes

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
