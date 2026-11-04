# Contributing

Keep changes focused on a fast, accessible Android calendar controlled through AI tools and MCP. The native calendar must remain usable before AI pairing is complete. Do not add advertising, analytics, an in-app AI chat, or a proprietary event store.

Use JDK 17, Android SDK 36, and the checked-in Gradle wrapper. Run `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`. For provider, reminder, permission, or widget changes, also run the Android tests described in [TESTING](docs/TESTING.md).

For MCP changes, run `npm ci`, `npm run check`, and `npm test` in `integrations/mcp`. Never commit OAuth client files, tokens, signing keys, local SDK paths, or real calendar exports.

For dependency changes, review licenses and update [third-party documentation](docs/THIRD-PARTY.md) and the APK's bundled notices where required.

Preserve shared-provider data. Calendar filters must not alter another app's visibility preferences. Do not flatten recurring instances into separate events, shift UTC all-day dates into the previous local day, overwrite unknown recurrence rules, or silently send invitation emails.

Use small functions, plain names, localized visible text, and clear errors. Keep documentation honest about tested behavior and device-specific limits. Update screenshots only from the running application, using the isolated demo fixture rather than personal calendars.
