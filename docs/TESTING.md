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

It also checks that the agenda remains reachable in landscape at 200% font size. `CalendarExperienceTest` checks month-grid geometry, centered selected dates, launch views, grouped settings and returning from the calendar selector, first/last and immediately outside month/week boundaries, Month agenda display modes, and all seven Week rows regardless of the Month preference. It also checks that the Day tab returns to today, a fixed calendar stays visible by default, the collapse switch restores it, Today gives visible feedback, and a sixth-row date can be selected in landscape at 200% font size before Today brings its selected cell back into view. A separate case confirms that horizontal drags do not change dates. Stable accessibility resource IDs identify the calendar viewport and agenda separately. Font size, rotation, and Android animation settings are restored afterward.

`SettingsPreferencesTest` checks defaults, limits, and persisted values in isolated preferences. `WidgetExperienceTest` uses Android's real widget host to check empty days, the date window, optional calendar names, background opacity, native dividers, multi-day events, and opening a draft from an empty date. `CalendarHandoffUiTest` checks Android event-insert intents and calendar-file sharing, editable drafts, import previews, rejection of unsupported input, and deferral through onboarding/recreation/an existing draft. The content provider used for shared-file fixtures exists only in the test APK.

`AiOnboardingTest` checks the computer-focused introduction, the skip route, direct navigation to Computer connection, and all eight assistant guides from Settings. Selecting a guide is not treated as a completed connection.

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

Images are saved to `.toolchain/screenshots/month-agenda.png`, `.toolchain/screenshots/widget.png`, `.toolchain/screenshots/ai-intro.png`, and `.toolchain/screenshots/ai-setup.png`. The calendar and onboarding images show the running app. The widget image shows the real Android RemoteViews widget and collection service in an instrumentation-only AppWidgetHost, rather than a launcher screenshot. The widget test also checks that tapping an event opens its details and editor, and that its add button opens a new event editor. The test temporarily grants widget-binding access to the app and revokes it afterward.

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
