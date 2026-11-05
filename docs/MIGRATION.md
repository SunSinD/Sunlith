# Use your existing calendars

Sunlith reads calendars available through Android's shared calendar provider. Your existing calendar app and Sunlith can display the same events without making another copy.

## Select calendars already on your phone

1. Allow calendar access when Sunlith asks.
2. Open Settings > Calendar > Calendars shown in Sunlith and select the calendars you want to display. Check the account as well as the calendar name.
3. Compare several events with your existing calendar app, including an all-day event and a repeating event.

If the same events already appear, no import is needed. Importing a backup into that calendar can create duplicates.

The app name does not tell you where an event is stored. Samsung Calendar, for example, can show Google, Samsung-account, or phone-only calendars. Check the calendar/account listed on the event. Sunlith can use phone-only calendars without a Google account; the optional computer companion currently controls Google calendars only.

If an account is missing, check your phone's account settings and enable calendar sync. A calendar stored privately inside another app is available to Sunlith only if that app or an installed account adapter exposes it to Android's provider.

Calendars stored only on the phone do not automatically sync to an account. Keep the existing app and data until you have checked your events and saved a backup.

## Save an ICS backup

1. Open Settings > Import and export > Export .ics file.
2. Select the calendars to export, then choose where to save the `.ics` file through Android's file picker.
3. Read the result and any warnings. Confirm that the backup contains the events you expect before deleting or moving the originals.

An export includes supported event fields. It is not a guarantee of a complete copy of every provider's proprietary data. Guest lists, attachments, tasks and some complex recurrence formats are outside this release's portable fields. Malformed provider records can be skipped with a warning.

## Import an ICS file

1. Open Settings > Import and export > Import .ics file and select a `.ics` file through Android's file picker.
2. Review the preview and choose a writable destination calendar. For a first test, use a separate empty calendar where possible.
3. Read the import report, then check dates, timezones, repeating events and reminders in the destination.

You can also open or share a supported `.ics` attachment with Sunlith to reach the same preview. This works with files from a mobile assistant that can generate them, without a computer or Google account. You still choose a destination and confirm Import. See [phone assistant handoff](AI-INTEGRATION.md#on-your-phone-without-a-computer).

Import creates copies and does not remove the original events. This release accepts files up to 5 MB and 2,000 events. It skips repeated source UIDs in the destination. Events without source UIDs also use matching details to detect duplicates. Distinct supplied UIDs remain separate events even when their details match, so duplicate detection cannot prevent every duplicate across unrelated exports.

Meetings with guests are imported as personal copies without sending invitations. Unsupported exception-heavy series and timezone formats are reported and skipped. Unsupported or excessive reminders can be trimmed with a message while keeping the event. Check the report before relying on the result.

## Choose which app sends reminders

Several calendar apps may notify for the same shared event. Choose which app should notify and disable event notifications in the others. Disabling notifications does not delete calendar data. Check a test reminder with the screen locked.

## References

- [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)
- [iCalendar standard](https://datatracker.ietf.org/doc/html/rfc5545)

Account settings and export controls in other apps vary. This guide does not assume a particular menu exists in another calendar app.
