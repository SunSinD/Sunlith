# Security

Do not include real calendar content, OAuth tokens, client secrets, or signing keys in issues or screenshots. Revoke exposed Google credentials immediately through your Google account.

The Android application requests access to the shared calendar provider and optionally notifications/exact alarms. It has no Internet permission. Import files are parsed locally with size/event limits; imported guests, attachments, and executable content are not acted on.

The MCP companion can read and change events permitted by the authorized Google account. Its OAuth event scope is broad; calendar IDs in prompts are not an account-level permission boundary. The Android app's display filters do not restrict the companion's Google authorization. Run it only with trusted MCP clients under your own OS account. Keep credential/token files in your private profile. Windows token files use the profile's filesystem permissions rather than an encrypted credential vault.

Use GitHub's private vulnerability reporting when available, or contact the repository owner privately. Report steps, affected version, and expected impact without publishing secrets.
