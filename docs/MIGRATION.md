# Bring your DigiCal events into Sunlith

Keep DigiCal installed until you have checked your events and reminders in Sunlith.

## Events already synced to Google, Outlook or another account

1. In DigiCal, open one of your existing events and check which calendar/account owns it. Calendar names alone may be ambiguous, so check the account too.
2. Install Sunlith and allow calendar access.
3. Open Sunlith's calendar selection and enable the same calendar/account.
4. Check several past and future events, an all-day event, and a repeating event.

No import is needed when both apps use the same Android calendar. Creating an ICS copy in that calendar can create duplicates. DigiCal's [official website](https://digibites.nl/) confirms that it uses calendar servers supported by Android.

If an account is missing, add it in your phone's account settings and enable calendar sync. Some apps keep their own calendar storage rather than exposing it to Android. For example, an Outlook calendar is available to Sunlith only when an installed account adapter or calendar-sync feature exposes it to Android's provider.

## Events stored only on the phone

Local calendars do not automatically sync to Google. Sunlith may already see them in its calendar selection because both apps use Android's provider. Check this before exporting anything.

To copy a local calendar into a synced account:

1. Enable only the local calendar you want to copy in Sunlith.
2. Use Sunlith's ICS export and save the file somewhere you can find it.
3. Check the exported calendar before deleting or moving anything.
4. Import the file into the selected writable synced calendar. Treat this as a copy: the original local events remain.
5. Read the import report. This alpha skips recurring series with exceptions rather than changing their meaning. Move those through the calendar provider's own export/import tools where available.
6. Confirm event dates, recurrence, timezone and reminders. Hide the old local calendar to avoid seeing both copies.

Sunlith's on-device calendar is explicitly labeled “On this device.” Events stored there will not appear through a desktop Google Calendar MCP connection.

## Using an existing ICS file

Use Import in Sunlith, select the `.ics` file through Android's file picker, review its preview, and choose a writable destination calendar. Files are limited to 5 MB and 2,000 events in this alpha. Repeated source UIDs are skipped; records without UIDs also use matching event details for deduplication. Distinct supplied UIDs are preserved even when the details match. The report distinguishes added events, duplicates and skipped events.

Imported guest meetings become personal copies without sending invitations. Advanced alarms, tasks, attachments and complex recurrence exceptions are not fully imported. Read the messages before relying on the result.

## Reminders from multiple calendar apps

DigiCal, Google Calendar and Sunlith may all notify for the same shared event. Choose which app should notify and disable event notifications in the others. Disabling an app's notifications does not delete its calendar data. Confirm a test reminder with the screen locked before trusting time-sensitive alerts.

## Control the same calendar from your PC

Use the recommended MCP connection described in the repository's integration instructions. Connect it to the same synced calendar used by Sunlith. A computer action changes the calendar service; Android's normal account sync brings that change to Sunlith and its widget. Sync can take time and requires the phone's account sync to be enabled.

Android's calendar permission and desktop Google OAuth permission are separate. Local-only calendars are not exposed through a Google Calendar MCP server.

## Source and verification limits

Sources: [DigiCal](https://digibites.nl/), [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider), [ICS standard](https://datatracker.ietf.org/doc/html/rfc5545), [Google Calendar MCP](https://github.com/nspady/google-calendar-mcp).

DigiCal's help center was inaccessible during research, so this guide does not invent a DigiCal-specific export menu. Your actual account and calendar list must be checked on your phone.
