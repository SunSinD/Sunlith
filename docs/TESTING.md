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

It also checks that the agenda remains reachable by scrolling in landscape at 200% font size. Font size and rotation are restored afterward. `AiOnboardingTest` checks the fresh AI-first introduction, the skip route, all eight provider choices, the selected setup guide, the connection-status disclosure, Back, and Continue from both onboarding and Settings. Selecting an assistant is not treated as a completed connection.

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

This creates a dedicated **Sunlith demo** local calendar on the emulator, selects it, and adds clearly identified sample events relative to the emulator's current date. It never seeds events automatically in the production app. Repeated captures replace only that dedicated demo calendar.

Images are saved to `docs/images/month-agenda.png`, `docs/images/widget.png`, `docs/images/ai-intro.png`, and `docs/images/ai-setup.png`. The calendar and onboarding images show the running app. The widget image shows the real Android RemoteViews widget and collection service in an instrumentation-only AppWidgetHost, rather than a launcher screenshot. The widget test also checks that tapping an event opens its details and editor, and that its add button opens a new event editor. The test temporarily grants widget-binding access to the app and revokes it afterward.

Use `-SkipInstall` if both debug APKs are already installed, or `-OutputDirectory <path>` to choose a different image folder.

## Before relying on reminders

On the intended phone, test notification permission, the selected calendars, an event due in a few minutes, reminder edits, reboot behavior, and any manufacturer battery restrictions. Check whether another calendar app also posts the same reminders. Google account syncing and real-device delivery require separate account/device testing; emulator provider tests do not establish those results.

These are test instructions, not a claim that every check has passed. Record the actual results with the build being tested.

## Verified alpha build

For `0.0.1-alpha.1`, verified on November 4, 2026:

| Check | Result |
| --- | --- |
| Android JVM unit tests | 15 passed |
| Selected API 30 emulator suite | 19 passed |
| Selected API 36 emulator suite | 20 passed |
| Separate API 36 notification permission recovery | 1 passed |
| Desktop MCP tests | 26 passed |
| Desktop MCP syntax check | Passed |
| Desktop MCP dependency audit | 0 vulnerabilities |
| GitHub MCP checks on Windows, macOS, Linux (Node 22) | All passed |

The selected emulator suite comprises `CalendarRepositoryTest`, `ReminderNotificationsTest`, `CalendarUiTest`, `WidgetSmokeTest`, and `AiOnboardingTest`. The French app-locale case requires API 33 and is excluded on API 30. Notification permission recovery runs separately with its explicit argument, rather than being silently counted as verified in a normal run. All selected tests completed without failures.

Tested debug APK SHA-256:

```text
E37322BD0525DCB5673B7FA1DA6BE1D9D48567DA05B84A45F4F8741D42846565
```

The screenshots in `docs/images` were captured from the running API 36 emulator with synthetic events. Widget screenshots use the actual Android widget in an instrumentation host. Google account sync, a real DigiCal migration, physical-device reminder delivery, and manufacturer battery behavior still require testing on the intended phone. These results do not establish universal AI client compatibility or a completed connection to an actual Google account.
