# Testing Sunlith

The provider tests run against Android's real calendar provider. They create isolated local calendars and delete only calendars they own. They refuse to run on a physical phone.

Use an emulator with API 26 or newer. The local test setup includes API 30 and API 36 Pixel 5 emulators. Run both to cover older provider behavior and newer notification/PendingIntent rules. A system image being installed does not establish that tests have passed on it.

## Build and run

With JDK 17 and an Android SDK configured:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
```

If using the ignored portable toolchain prepared in this checkout, load its process-local environment first:

```powershell
. .\.toolchain\env.ps1
adb devices
```

To run only the provider and import/export tests on an installed test APK:

```powershell
adb -s emulator-5554 shell am instrument -w -e class io.github.sunsind.sunlith.CalendarRepositoryTest io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

The tests cover local calendar creation, event CRUD, reminder persistence, all-day UTC dates, recurrence across a daylight-saving transition, series edits/deletion, ICS round-trip preservation, duplicate imports, and safe handling of unsupported recurrence exceptions.

`CalendarUiTest` drives the actual Compose UI through Android accessibility: create an event, find it through search, edit it, and delete it. It also checks the rendered light/dark appearance and the persisted theme setting. On API 33 and newer, a separate case switches the app locale to French and checks translated calendar/editor/settings controls. These UI tests use and clean up a dedicated emulator-only calendar.

It also checks that the agenda remains reachable in landscape at 200% font size. `CalendarExperienceTest` checks month-grid geometry, centered selected dates, launch views, grouped settings and returning from the calendar selector, first/last and immediately outside month/week boundaries, Month agenda display modes, and all seven Week rows regardless of the Month preference. It also checks that the Day tab returns to today, a fixed calendar stays visible by default, the collapse switch restores it, Today gives visible feedback, and a sixth-row date can be selected in landscape at 200% font size before Today brings its selected cell back into view. Separate cases confirm deliberate horizontal swipes change periods, the off switch prevents navigation, optional arrows move each period, and continuous agenda paging keeps the fixed grid populated. Stable accessibility resource IDs identify the calendar viewport and agenda separately. Font size, rotation, and Android animation settings are restored afterward.

`SettingsPreferencesTest` checks defaults, limits, and persisted values in isolated preferences. `WidgetExperienceTest` uses Android's real widget host to check empty days, the date window, optional calendar names, background opacity, native dividers, multi-day events, and opening a draft from an empty date. `CalendarHandoffUiTest` checks Android event-insert intents and calendar-file sharing, editable drafts, import previews, rejection of unsupported input, and deferral through onboarding/recreation/an existing draft. The content provider used for shared-file fixtures exists only in the test APK.

`AiOnboardingTest` checks the computer-focused introduction, the Agenda/Grid chooser, recreation, the skip route, deferred Computer connection and existing-user migration. The app no longer has an assistant-guide picker. `CalendarCustomizationTest` checks preset views, date selection, timelines, provider event colors, wallpaper colors, event focus across loading and recreation, full-title previews, and localized toolbar bounds.

`CalendarRefinementTest` checks horizontal and curved gestures, cancellation, disabled navigation, queued arrows, Day/Back behavior, and live changes to Android's motion preference. `LauncherIconTest` checks launcher aliases, migration, persistence, interrupted switches, document-picker continuity, and actual adaptive artwork rendering. `ComputerConnectionUiTest`, `DeviceLinkTlsTest` and `DeviceLinkToolsTest` cover direct sharing, cached-file and credential lifetime, scoped calendar access, protocol handling, and pinned TLS. The external desktop fixture runs separately with explicit opt-in.

`ReminderNotificationsTest` additionally checks actual posted notification content, actions, occurrence deduplication, disabled reminders, and delivery through a scheduled Android alarm. A separate test inserts an isolated due provider alert for a `MINUTES_DEFAULT` reminder, sends an explicit calendar-reminder broadcast, and verifies notification delivery without changing the provider alert state. That test verifies broadcast handling, not how a real account chooses its default reminder time. Tests request notification permission on API 33 and newer. For one specific emulator, use `adb -s <serial> shell am instrument ...` or set `ANDROID_SERIAL` before a Gradle connected-test command.

To test notification permission denial and recovery on API 33 or newer, run this separately after the other tests. Revoking a runtime permission can terminate the app process, so it must happen before instrumentation starts:

```powershell
adb -s emulator-5556 shell pm revoke io.github.sunsind.sunlith android.permission.POST_NOTIFICATIONS
adb -s emulator-5556 shell pm clear-permission-flags io.github.sunsind.sunlith android.permission.POST_NOTIFICATIONS user-set user-fixed
adb -s emulator-5556 shell am instrument -w -e class io.github.sunsind.sunlith.NotificationPermissionRecoveryTest -e permissionRecovery true io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

This case checks that denied notifications stay silent, grants permission through the real Android permission dialog, then verifies a posted notification. It is skipped during a normal suite run unless the explicit `permissionRecovery` argument is supplied.

## Screenshots with sample events

```powershell
.\tools\capture-demo.ps1
```

This creates a dedicated **Sunlith demo** local calendar on the emulator, selects it, and adds clearly identified sample events relative to the emulator's current date. Demo events have no reminders, so another installed calendar cannot interrupt captures with fixture alerts. It never seeds events automatically in the production app. Repeated captures replace only that dedicated demo calendar.

Images are saved to `.toolchain/screenshots/month-agenda.png` and `.toolchain/screenshots/widget.png`. The calendar image shows the running app. The widget image shows the real Android RemoteViews widget and collection service in an instrumentation-only AppWidgetHost, rather than a launcher screenshot. The widget test also checks that tapping an event opens its details and editor, and that its add button opens a new event editor. The test temporarily grants widget-binding access to the app and revokes it afterward.

Use `-SkipInstall` if both debug APKs are already installed, or `-OutputDirectory <path>` to choose a different image folder.

## Before relying on reminders

On the intended phone, test notification permission, the selected calendars, an event due in a few minutes, reminder edits, reboot behavior, and any manufacturer battery restrictions. Check whether another calendar app also posts the same reminders. Google account syncing and real-device delivery require separate account/device testing; emulator provider tests do not establish those results.

These are test instructions, not a claim that every check has passed. Record the actual results with the build being tested.

## Verified behavior build

For source commit `44a57c8`, verified against the APK hash below. The `0.1.1` naming update changes version metadata and documentation only; its package verification is recorded separately below.

| Check | Result |
| --- | --- |
| Android JVM unit tests | 20 passed |
| Selected API 30 emulator suite | 43 passed in 303.634 seconds |
| Selected API 36 emulator suite | 44 passed in 333.709 seconds |
| Separate API 36 notification permission recovery | 1 passed in 7.027 seconds |
| Android lint | 0 errors, 70 warnings, 4 hints |
| Desktop MCP tests | 31 passed |
| Desktop MCP syntax check | Passed |
| Desktop MCP dependency audit | 0 vulnerabilities |

Each selected emulator suite completed as a single run without failures. Android JVM tests comprise 13 ICS codec cases, 5 incoming-intent parser cases, and 2 editor-state cases. Notification permission recovery ran separately after revoking permission before instrumentation started.

Tested debug APK SHA-256:

```text
6510163DC36109421906F92DF6254D46D5EF395494EAB74B4574D023EF9B508B
```

The selected emulator suite comprises `CalendarRepositoryTest`, `ReminderNotificationsTest`, `CalendarUiTest`, `WidgetSmokeTest`, `AiOnboardingTest`, `SettingsPreferencesTest`, `CalendarExperienceTest`, `WidgetExperienceTest`, and `CalendarHandoffUiTest`. The French app-locale case requires API 33 and is excluded on API 30. Notification permission recovery runs separately with its explicit argument.

Local captures use running emulators and synthetic events. Widget captures use the actual Android widget in an instrumentation host. Captures are excluded from the repository pending phone testing. Google account sync, migration from existing calendars, physical-device reminder delivery, and manufacturer battery behavior still require testing on the intended phone. Emulator results do not establish universal AI client compatibility or a completed connection to an actual Google account.

## Version 0.1.1 package verification

The plain-version APK uses `versionName` `0.1.1` and `versionCode` `4`. All nine DEX files and the compiled resource table are byte-identical to the full behavior build recorded above. The manifest and signing metadata change with the version.

The Android build, JVM checks and lint passed. After installing over the previous app without uninstalling, the actual create/search/edit/delete UI test passed on API 30 (14.286 seconds) and API 36 (19.010 seconds). The signing certificate matches the previous published APK. Companion syntax checks and all 31 tests passed with server/package version `0.1.1`. The full 88 emulator checks above were not repeated for this metadata-only change.

Version 0.1.1 APK SHA-256:

```text
f97f4babcab9bf5fe7d8d6c766ecc42d8e3ed1c4c202a34c1ae721a4b023da36
```

## Version 0.1.2 artwork verification

This build replaces the launcher foreground, monochrome layer, background, and decorative setup mark with the supplied sun/calendar artwork. It uses version code `5` and the same signing certificate as the previous published APK.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| API 30 UI, setup, and real widget tests | 6 passed in 51.362 seconds |
| API 36 UI, setup, and real widget tests | 7 passed in 67.130 seconds |
| Android lint | 0 errors, 70 warnings, 4 hints |
| Companion syntax and tests | Passed, 31 tests |

The UI checks include real create/search/edit/delete, rendered light/dark themes, French on API 36, and landscape with large font sizes. Both emulators installed the new APK over the previous version without uninstalling. Actual launcher captures were inspected on both API levels. A private circle/rounded-square mask comparison and foreground alpha bounds confirm the artwork remains inside the centered 66dp adaptive safe circle. Its visible radius is below 32dp on the 108dp foreground canvas. The themed layer shares that padded alpha geometry. Physical Samsung launcher appearance remains for phone testing.

Current APK SHA-256:

```text
807b7c448f625e962be90777f9f4a794be35b90094b91361e945e235d6dc6c4c
```

The broader behavior suite recorded above belongs to the earlier source commit; it was not repeated for this artwork-only update. Screenshots remain private until phone approval.

## Version 0.2.0 direct connection verification

The APK uses version code `6` and the same signing certificate as v0.1.2. Installation over the existing app succeeded on API 30 and API 36.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| Android lint | 0 errors, 72 warnings, 5 hints |
| Desktop tests | 44 passed; opt-in Android fixture skipped in normal runs |
| Desktop syntax and dependency audit | Passed; 0 vulnerabilities |
| API 30 direct backend and connection UI | 27 passed across verification runs |
| API 30 existing calendar and UI cases | 34 passed |
| API 36 direct backend and connection UI | 27 passed across verification runs |
| API 36 existing provider, UI, reminder, widget and handoff cases | 29 passed |
| Actual desktop SDK to Android provider | Passed over pinned HTTPS through ADB forwarding |
| Full backup import | 215 imported, 0 skipped; retry detected 215 duplicates |

Direct backend cases cover strict HTTP/JSON parsing, real AndroidKeyStore TLS, access scope, CRUD, stale etags, create retries, conflicting payloads, DST/all-day dates, recurring-series safeguards, guest protection and imports. Desktop TLS tests verify that a wrong certificate receives no decrypted HTTP bytes or bearer header. Connection UI cases cover separate display/access choices, document-picker cancellation, actual service notification, activity recreation, rapid stop/start and revocation. The rendered pairing screen was inspected privately.

The final APK passed five independent lifecycle runs on API 30, covering fifteen immediate stop/start cycles, plus a final SAF/TLS run. The final API 36 connection UI and TLS run passed seven cases. One earlier automated document-picker wait was intermittent; focused and full reruns, screenshots and Android activity logs confirmed the actual picker opened. Phone-specific document-provider behavior remains part of physical-device testing.

The API 30 existing cases cover repository operations, calendar views, handoffs, onboarding, settings and the bounded agenda. API 36 also covers real reminder notifications and RemoteViews widgets. Initial repeated test runs reused static operation IDs against the persistent retry journal; test IDs now have a unique namespace so tests preserve within-request retries without conflicting with earlier runs. No production journal was cleared to make those tests pass. A quick stop/start failure on API 30 led to ordered service commands with start-ID-aware shutdown. A separate shutdown case checks that active and queued sockets close.

The opt-in external test connects the official MCP SDK over stdio to Node, then the real Android HTTPS server and CalendarProvider. It exercises all eight tools, excluded-calendar protection, timed/all-day creation, reads, edits, stale revisions, series scope, imports, duplicate detection and deletion. It operates in isolated emulator calendars that are removed afterward. Pairing files and calendar fixtures stay outside the repository.

To run it, first start the isolated Android fixture, then pull its private configuration and metadata to a private folder outside the repository:

```powershell
adb -s emulator-5556 forward tcp:39393 tcp:39393
adb -s emulator-5556 shell am instrument -w -e class io.github.sunsind.sunlith.ComputerConnectionFixtureTest -e deviceLinkExternal true io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner
```

While that command runs, use another terminal to pull `device-link-test.json` and `device-link-test-metadata.json` from `/sdcard/Android/data/io.github.sunsind.sunlith/files/`. Do not print the pairing contents. From `integrations/mcp`, set `SUNLITH_DEVICE_CONFIG` and `SUNLITH_ANDROID_TEST_METADATA` to the absolute private paths, then run:

```powershell
node --test test/android-device.test.js
adb -s emulator-5556 shell run-as io.github.sunsind.sunlith touch files/device-link-test-finished
```

The fixture waits up to three minutes, then removes its calendars and revokes pairing. An optional private import fixture can be supplied with `SUNLITH_ANDROID_IMPORT_FIXTURE` and `SUNLITH_ANDROID_IMPORT_COUNT`; no real event content belongs in source or logs.

Screen-off and forced deep-idle requests succeeded through ADB loopback forwarding on API 36. That verifies service/process behavior, not Wi-Fi or Tailscale delivery during physical-phone Doze. Real Samsung battery restrictions, Tailscale reachability, network changes and long idle periods still require phone testing. A foreground service alone does not guarantee network access through Doze. The app offers a link to its system settings for background battery use.

Version 0.2.0 APK SHA-256:

```text
b76ae486868cc1a0a624360e0c87c2a7da03a3a5083614cd425041da6870e9c9
```

## Computer activity and time alignment checks

The 0.3.0 regression cases cover a shared Day view time column for short and long clock labels, clock labels fitting on one line in 180dp and 340dp widget rows at normal and 200% font size, and French all-day labels fitting within two lines. Computer connection UI checks cover its details and activity pages and cancelling Clear history.

Backend checks cover successful changes, rejected requests, create retries, unchanged edits, import counts, persistence, the 100-entry bound, clearing, damaged history files and safe title truncation. TLS tests verify that only successful authenticated responses update recent contact, old pairing files remain valid, and a revoked pairing cannot update the new pairing's contact time.

History is a bounded activity record, not undo or a transactional audit trail. A process crash during a partial import can leave imported events without a completed import record. Storage failure can leave recent changes in memory only, and the last-contact timestamp saved to disk can lag by up to one minute. Existing event history cannot be reconstructed.

## Version 0.3.0 verification

Version code `7` uses the same signing certificate as v0.2.0 and was installed over the existing emulator app.

| Check | Result |
| --- | --- |
| Android JVM tests | 20 passed |
| Android lint | 0 errors, 74 warnings, 5 hints |
| API 36 full emulator suite | 82 passed in 422.609 seconds |
| API 30 full emulator suite | 80 cases; one test assertion corrected, as explained below |
| API 30 final widget suite | All 5 passed |
| Fold-sized API 36 at 200% text | Day card alignment passed at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | Passed |
| Desktop tests and syntax | 44 passed; opt-in Android fixture skipped in normal runs |
| Desktop dependency audit | 0 vulnerabilities |
| Actual desktop SDK to Android provider | Passed through pinned HTTPS and stdio MCP |

The first API 30 run passed 79 cases. Its only failure was a widget test counting invisible trailing whitespace as clipped text in a French all-day label. The assertion was corrected to measure visible glyphs. The focused case and then all five widget cases passed on API 30; the final test APK passed the complete API 36 suite. The full API 30 suite was not repeated after that test-only correction. Initial emulator runs interrupted by unresponsive System UI were discarded; the recorded suites used cold boots with one emulator at a time.

The desktop integration exercised all eight tools against isolated emulator calendars. Its resulting private history contained create, update, import and delete entries. The fixture removed its calendars and pairing afterward. The actual widget rendering and large-text landscape captures were inspected privately. No personal events or phone pairing were changed, and no screenshots were published.

Physical Samsung widget rendering, Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery behavior still need phone testing. A Fold-sized emulator check does not reproduce Samsung's launcher or folding hardware.

Version 0.3.0 APK SHA-256:

```text
ec76f249745b3b7e66f876d2060c0cb11eb7789eebdade474b8eb9b0e849a1c8
```

## Version 0.4.0 verification

Version code `8` uses the same signing certificate as v0.3.0. The APK was installed as an update on Android 11 (API 30) and Android 16 (API 36).

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed, including six timeline packing cases |
| Android lint | 0 errors, 81 warnings, 6 hints |
| API 30 broad emulator run | 98 cases; two timeline automation failures, corrected and rerun below |
| API 36 broad emulator run | 100 cases; two timeline automation failures, corrected and rerun below |
| API 30 focused customization suite | All 9 passed in 127.818 seconds |
| API 36 final customization suite | All 9 passed in 160.718 seconds |
| Fold-sized API 36 at 200% text | All 5 passed in 37.470 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | Passed in 8.478 seconds |
| Desktop syntax and tests after fresh dependency install | 44 passed; external Android fixture skipped in normal runs |
| Desktop dependency audit | 0 vulnerabilities |
| Actual desktop SDK to Android calendar provider | Passed through pinned HTTPS and stdio MCP |
| Release packaging | Signing certificate, source equality, local archive links and checksums verified |

The broad runs cover provider operations, reminders, handoffs, setup, layouts, animated navigation, continuous paging, widgets, connection lifecycle, strict protocol parsing and TLS. They each reported failures in the timeline UI automation: gestures could scroll past short events or tap the floating Add/Today controls over a partially visible event. The helpers now reset the hourly area through accessibility, wait for stable event bounds and bring targets clear of the floating controls. All nine customization cases then passed on each API level. Earlier test corrections recognize Android's checked state for selected date buttons and use smaller agenda gestures. The full broad suites were not repeated after the final test-only corrections; the production APK stayed unchanged throughout these runs.

The five Fold-sized cases were `CalendarExperienceTest.dayEventCardsShareTheSameBoundsForDifferentTimeLengths`, `CalendarCustomizationTest.presetsRenderDifferentMonthAndDayViewsWithTheSameCalendarEvents`, `CalendarCustomizationTest.dayPickerChangesTheShownEventsAndTheNewEventDateForEveryDayLayout`, `CalendarCustomizationTest.overlappingTimelineEventsHaveSeparateTapTargetsAndAllDayStaysAboveTheHours`, and `AiOnboardingTest.everyPresetIsSelectableAndPlannerChoiceSurvivesRecreationBeforeConfirmation`. The customization suite also verifies overnight hourly blocks on both dates, dense-event overflow, the saved three-day window, and actual dynamic and neutral accent colors. An extra test request left over from emulator startup failed to connect UiAutomation and was discarded; it is not included in the verification results above.

The external desktop test exercised all eight tools, allowed-calendar scope, creation retries, reads, updates, stale revisions, recurring-series protection, import deduplication and deletion. Its isolated calendars and pairing were removed afterward, along with the private temporary configuration files and ADB forwarding. No personal phone calendar or pairing was changed. Widget and large-text landscape captures were inspected privately; no screenshots or videos were published.

At initial publication, GitHub rejected workflow dispatch with HTTP 422, "Actions has been disabled for this user." After account recovery on November 6, [run 42276063211](https://github.com/SunSinD/Sunlith/actions/runs/42276063211) passed all four jobs on the exact `v0.4.0` commit, `9207786`: Android build/unit tests/lint and companion install/syntax/tests on Linux, Windows and macOS, plus the Linux dependency audit. CI does not run emulator instrumentation and its APK is signed separately from the published download; the emulator and published-APK checks above remain separate results.

Physical Samsung launcher rendering, Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery behavior still need phone testing. The hourly timeline places timed events lasting 24 hours or more in its banner area. Tapping an empty hour opens the editor at its default start time. These limits remain separate from the verified overlap, overnight and date-selection behavior.

Version 0.4.0 APK SHA-256:

```text
fab9ce155ee2b4316223a5fa967ba3d4395d3228a6535d3c4bcf61df4960354e
```

## Unreleased 0.4.1 candidate verification

Before the icon work, an unreleased v0.4.1 candidate with version code `9` fixed a three-day Week timeline loading gap at month boundaries. The checks in this section belong to that earlier candidate, not the final v0.5.0 build. The new regression failed against the published v0.4.0 APK, then passed against the packaged v0.4.1 APK for both Monday-first (January 30–February 1, 2027) and Sunday-first (October 30–November 1, 2026) calendars. It checks that all three dates and the first date's event remain available after selecting the next month's date, changing the Week layout, and switching between three and seven days. The layout-change path also failed against the initial candidate before adding the required range refreshes.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 81 warnings, 6 hints |
| Full Android 16 / API 36 suite | 99 passed, 2 skipped, 0 failures in 525.679 seconds |
| Fold-sized API 36 at 200% text | All 5 named cases from v0.4.0 repeated and passed in 38.278 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 8.306 seconds |
| Actual desktop SDK to Android calendar provider | All eight tools passed over pinned HTTPS and stdio MCP; fixture cleanup passed |
| Desktop syntax and tests after fresh dependency install | 44 passed, 1 opt-in fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |

The API 30 skips are wallpaper-based Material You colors, which require API 31 or newer, and the separately enabled external desktop fixture. API-level annotations exclude French app locales and notification-permission recovery on API 30. The API 36 full suite skips the external desktop fixture and permission recovery; both passed separately afterward. The external fixture removed its isolated calendars and pairing, and its temporary private desktop files and ADB forwarding were removed. No personal calendar or pairing was accessed.

An initial API 36 full run had 98 passes, two skips and one UI automation failure. Android's event log showed the test event's 15-minute reminder appearing at 14:45 and the automated Search tap clicking that notification, which opened event details. General UI fixtures now disable reminders; reminder behavior remains covered by the separate notification tests. All four focused UI cases passed before this correction. Final full-suite results use the corrected fixture. Startup runs blocked by an Android System UI ANR and runs stopped while extending the regression are excluded.

A later API 36 run had 98 passes, two skips and one shutdown-fixture failure. Its eight TCP connections could still be waiting in the operating system's accept backlog when shutdown was called. The test now waits until the server has accepted all eight, establishing its working/queued-socket precondition before stopping it. All seven focused TLS cases passed with that synchronization; production server code was unchanged.

The packaged APK was installed directly with `adb install -r`, followed by the final instrumentation APK (SHA-256 `f2146232e34b45a53f4a25f1ac60294db19d6c321cd0e3a0e76ef355c65e0bdc`). Its installed bytes match the packaged APK's SHA-256. Gradle connected tests were not used to replace it with another app build. The signing certificate matches v0.4.0; the old release binaries remain unchanged.

Version 0.4.1 APK SHA-256:

```text
d5c2738d0470ae3b54cd714c545d89d8be994bbaae7666047a5345502192e289
```

## Version 0.5.0 verification

Version code `10` uses the same signing certificate as v0.4.0. The earlier code-9 candidate was not published. All results below use the final packaged APK and the final instrumentation APK, installed directly with ADB on one emulator at a time.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 83 warnings, 6 hints |
| Full Android 11 / API 30 suite | 108 passed, 2 skipped, 0 failures in 534.879 seconds |
| Full Android 16 / API 36 suite | 110 passed, 2 skipped, 0 failures in 515.064 seconds |
| Fold-sized API 36 at 200% text | All 5 named cases from v0.4.0 passed in 34.431 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 6.404 seconds |
| Actual desktop SDK to Android calendar provider | All eight tools passed through pinned HTTPS and stdio MCP; fixture cleanup passed |
| Desktop syntax and tests after fresh dependency install | 44 passed, 1 opt-in Android fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |
| Release packaging | Source equality, local archive links, APK signing certificate and installed APK hash verified |

The API 30 skips are wallpaper-based Material You colors and the external desktop fixture. API-level annotations exclude the French app-locale and notification-permission cases there. The API 36 skips are the external desktop fixture and permission recovery; both passed separately afterward. Custom demo and performance status messages are excluded from test counts.

New regression coverage checks the three-day month boundary with fixed Monday-first and Sunday-first dates, date rollover without replacing an explicitly chosen date, Today in scrolling and fitting full-month layouts, launcher choices and disk persistence, interrupted-switch repair, recreation, rollback and reopening an active document picker from the launcher. The Appearance test taps the actual icon tiles and waits for the recreated activity. The fitting-grid Today case reproduced Opus's final review finding before the fix, then passed afterward. Both final full suites include that fix. Earlier stopped runs and the first document-picker test setup failure are excluded; the picker test now discovers the actual DocumentsUI window instead of requiring PackageManager visibility.

The running calendar has a stable main activity behind a short launcher activity. Disabling the original direct launcher alias on Android 11 had finished the open app despite `DONT_KILL_APP`; the separate launcher activity fixed that observed behavior. Removing `CLEAR_TOP` also preserves an open document picker when returning from Home. Tests cover both the sequential alias update on API 30 and the atomic update on API 36. Startup repairs an interrupted switch on activity recreation as well as a fresh launch.

A dense-calendar fixture inserts 1,460 reminder-free events over 365 days with roughly 1KB descriptions. It checks rendered initial loading, paging in both directions, a distant date, description search and title search. Times include provider work, UI rendering and input, and are observations from these emulators rather than performance thresholds.

| Dense-calendar operation | API 30 elapsed | API 36 elapsed |
| --- | --- | --- |
| Initial Month agenda | 1,468ms | 1,997ms |
| Page forward | 204ms | 161ms |
| Page backward | 204ms | 159ms |
| Select distant month | 316ms | 220ms |
| Cold description search | 1,318ms | 840ms |
| Warm title search | 1,558ms | 233ms |

A 25ms main-thread heartbeat observed maximum lateness of 281ms on API 30 and 193ms on API 36 across these operations. This is a coarse responsiveness sample, not a frame-jank benchmark. It does not establish performance for arbitrarily dense calendars or justify speculative rewrites. The fixture removed its events and calendar afterward.

The desktop integration exercised scope restrictions, timed/all-day creation, idempotent retries, reads, edits, stale revisions, recurring-series safeguards, import deduplication and deletion. Its isolated calendars and pairing were removed, along with private desktop fixture files and ADB forwarding. No personal events or phone pairing were accessed. Fold display size, density and font scale were restored after testing.

Opus 5.5 High and GPT 6.1 Sol High independently reviewed the changes and earlier requirements. Concrete findings were fixed and included in the final runs. Samsung home-screen shortcut migration, icon-launch/splash animation, physical Fold posture changes, Wi-Fi/Tailscale delivery and manufacturer battery restrictions still require a real phone. Some launchers may require adding the home-screen shortcut again after an icon change. The existing timeline limits described above remain.

Version 0.5.0 APK SHA-256:

```text
37333de456ffb9fc8314cb8987e83d6f005e870d9cd4b2ccc06a29b7592d81d9
```

Final instrumentation APK SHA-256:

```text
095b6ec24b046524d69af0c78f91d72461fa90d2b213951a5917252b7511d681
```

## Version 0.6.0 verification

Version code `11` uses the same signing certificate as v0.5.0. These results use the
final packaged APK and final instrumentation APK, installed directly with ADB on
one emulator at a time. Installed bytes matched the packaged files on both APIs.

| Check | Result |
| --- | --- |
| Android JVM tests | 26 passed |
| Android lint | 0 errors, 111 warnings, 7 hints |
| Full Android 11 / API 30 suite | 130 passed, 2 skipped, 0 failures in 784.245 seconds |
| Full Android 16 / API 36 suite | 132 passed, 2 skipped, 0 failures in 845.856 seconds |
| Focused API 36 regressions | 15 passed in 84.854 seconds |
| Narrow toolbar, English / fr-CA / Arabic RTL | 360dp passed in 10.329 seconds; 320dp passed in 9.113 seconds |
| Fold-sized API 36, 200% text | All 5 named cases from v0.4.0 passed in 33.496 seconds at 1768x2208, density 320 |
| Separate API 36 notification permission recovery | 1 passed in 7.883 seconds after revoking permission and clearing permission flags |
| Actual desktop SDK to Android calendar provider | All eight tools passed through pinned HTTPS and stdio MCP in 2.316 seconds; Android fixture passed in 24.831 seconds |
| Desktop syntax and tests | 44 passed, 1 opt-in Android fixture skipped |
| Desktop dependency audit | 0 vulnerabilities |
| Release packaging | Archive source equality, local links, signing certificate and installed APK hashes verified |

API 30 skipped wallpaper-based Material You colors (requires API 31+) and the
external-client fixture. French per-app locale and notification-recovery tests
are API 33+ and excluded there. API 36 skipped the external-client fixture and
permission-recovery cases in the full run; both passed separately afterward.
Custom demo, widget and performance status messages are excluded from counts.
Neither final full run encountered a System UI ANR.

Coverage includes date drilldown and Back, focus in all three Day layouts,
focus consumption across recreation, preserving the hour during day swipes,
diagonal/cancelled/repeated swipes, full-title month previews, provider colors,
start/end times and overlap targets, theme controls, icon switching, scoped
connection-file sharing and revocation, widget reconfiguration and preference
refresh. The distant Schedule regression uses many all-day entries before a
timed event and verifies that programmatic focus does not repeatedly load more
dates. Empty-date navigation advances to the next event when empty dates are
hidden. The French settings test also handles an already-selected radio button.

Earlier candidates exposed a Schedule focus/continuous-loading interaction and
two test-selector problems (a date tap hit the extra-event preview; a selected
radio lacked a redundant click action). Those were corrected before both final
full runs. Earlier failed candidates and an API 36 run obstructed by a System UI
ANR are retained locally as investigation evidence and are not counted as passes.

Screenshot inspection confirms complete dates at 320dp and 360dp, the two-line
French all-day label, readable normal-size French tabs and mirrored RTL Back.
Week titles still ellipsize inside narrow columns. Full-month titles remain
bounded by their cells, including at 200% text; holding the date or using its
extra-event count opens the full-title sheet. Short timeline blocks may devote
their available height to the time range; opening the event shows its full title.
These are layout limits, not evidence that every title fits in every overview.

The adaptive icon tests render the actual Android drawable with circle and
squircle masks. The calendar paper fills the mask: cream for Light and charcoal
for Dark, without the previous separate white tile and shadow. A truly
transparent adaptive layer rendered black on the tested Android implementation;
legacy icons were wrapped in white. See [the icon research](RESEARCH-REFINEMENT.md).

The dense-calendar fixture inserts 1,460 reminder-free events across 365 days.
These elapsed times include provider work, rendering and input on the emulators:

| Operation | API 30 | API 36 |
| --- | --- | --- |
| Initial Month agenda | 1,402ms | 1,701ms |
| Page forward | 226ms | 160ms |
| Page backward | 230ms | 148ms |
| Select distant month | 378ms | 239ms |
| Cold description search | 1,681ms | 841ms |
| Warm title search | 1,223ms | 960ms |

Maximum lateness of the 25ms main-thread heartbeat was 239ms on API 30 and 187ms
on API 36. This is a coarse responsiveness sample, not a frame-jank benchmark or
a guarantee for arbitrarily dense calendars. Loaded-event counts were
732, 1,088, 1,204 and 264; searches returned 10 description matches and 4 title
matches.

The external desktop fixture exercised all eight tools against isolated real
provider calendars. Private desktop files, Android exports, completion sentinel
and ADB forwarding were removed. Both emulators were stopped after restoring
display settings. No personal calendar or phone pairing was accessed. A
158-file fingerprint was unchanged throughout frozen runtime verification.

Opus 5.5 High, GPT 6.1 Sol High, Muse Wonder and Gemini 4 Argon independently
reviewed the final source, artifacts and runtime evidence after these runs.
All four reported no release blockers within their respective scopes: calendar
behavior and rendered layouts; lifecycle and regressions; connection, icons and
packaging; settings, accessibility and localization. These were read-only reviews,
not additional test executions. A minor degraded-path limitation remains: if the
FileProvider share route is unavailable and the save-file fallback is used,
recreating that screen loses its Share again shortcut, although the saved file
and pairing remain valid.

Real-device Taildrop delivery, manufacturer battery restrictions, launcher
shortcut refresh behavior and physical TalkBack interaction remain unverified.
Tailscale supplies network reachability; explicit app authorization still uses
chosen calendars, a bearer token and pinned TLS. Emulator results do not replace
those physical-device checks or prove the absence of all bugs.

Version 0.6.0 APK SHA-256:

```text
0469289ab9f6e545afae7a603379b61f7b6fc937ebd604b17bb8c24eeab02836
```

Final instrumentation APK SHA-256:

```text
3694b91eb10eabad9c8cf26aff25f892341a7cc32613e0574f1be8406b0193ec
```

Signing certificate SHA-256:

```text
059358bfcb9aeccb20258343b6bab48dfb88c8f30c4e19035f4c14d5b3dd9039
```
