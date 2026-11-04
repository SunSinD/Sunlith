# Sunlith computer companion

This companion lets a supported assistant manage a Google calendar that your phone syncs. It runs on Windows, macOS, and Linux with Node.js 22 or newer.

This is a command-line companion. It does not include a desktop calendar or browser setup wizard.

1. Install Node.js 22 or newer from https://nodejs.org/.
2. Open a terminal in integrations/mcp inside this extracted folder.
3. Run npm ci.
4. Follow docs/AI-INTEGRATION.md to create your own Google Desktop OAuth client, authorize your account, and configure your assistant.

Use full paths to this extracted folder when configuring your assistant. Keep Google credentials and tokens in your private user profile, outside this folder.

The phone must sync the same Google calendar. Phone-only calendars, Outlook and CalDAV are not controlled by this companion.

Source and updates: https://github.com/SunSinD/Sunlith
