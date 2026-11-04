# Use your existing calendars

Sunlith reads calendars available through Android's shared calendar provider. Your existing calendar app and Sunlith can display the same events without making another copy.

## Select calendars already on your phone

1. Allow calendar access when Sunlith asks.
2. Open Sunlith's calendar selection and choose the calendars you want to display. Check the account as well as the calendar name.
3. Compare several events with your existing calendar app, including an all-day event and a repeating event.

If the same events already appear, no import is needed. Importing a backup into that calendar can create duplicates.

If an account is missing, check your phone's account settings and enable calendar sync. A calendar stored privately inside another app is available to Sunlith only if that app or an installed account adapter exposes it to Android's provider.

Calendars stored only on the phone do not automatically sync to an account. Keep the existing app and data until you have checked your events and saved a backup.

## Save an ICS backup

1. Select the calendars you want to export in Sunlith.
2. Use the export action and choose where to save the `.ics` file through Android's file picker.
3. Read the result and any warnings. Confirm that the backup contains the events you expect before deleting or moving the originals.

An export includes supported event fields. It is not a guarantee of a complete copy of every provider's proprietary data. Guest lists, attachments, tasks and some complex recurrence formats are outside this alpha's portable fields. Malformed provider records can be skipped with a warning.

## Import an ICS file

1. Use the import action and select a `.ics` file through Android's file picker.
2. Review the preview and choose a writable destination calendar. For a first test, use a separate empty calendar where possible.
3. Read the import report, then check dates, timezones, repeating events and reminders in the destination.

Import creates copies and does not remove the original events. This alpha accepts files up to 5 MB and 2,000 events. It skips repeated source UIDs in the destination. Events without source UIDs also use matching details to detect duplicates. Distinct supplied UIDs remain separate events even when their details match, so duplicate detection cannot prevent every duplicate across unrelated exports.

Meetings with guests are imported as personal copies without sending invitations. Unsupported exception-heavy series and timezone formats are reported and skipped. Unsupported or excessive reminders can be trimmed with a message while keeping the event. Check the report before relying on the result.

## Choose which app sends reminders

Several calendar apps may notify for the same shared event. Choose which app should notify and disable event notifications in the others. Disabling notifications does not delete calendar data. Check a test reminder with the screen locked.

## References

- [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)
- [iCalendar standard](https://datatracker.ietf.org/doc/html/rfc5545)

Account settings and export controls in other apps vary. This guide does not assume a particular menu exists in another calendar app.
