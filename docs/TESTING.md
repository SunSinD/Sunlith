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

`AiOnboardingTest` checks the introduction, the skip route, all eight provider choices, the selected setup guide, the connection-status disclosure, Back, and the pinned Continue action from both onboarding and Settings. Selecting an assistant is not treated as a completed connection.

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

## Verified beta build

For `0.1.1-beta.1`, verified against the APK hash below:

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
