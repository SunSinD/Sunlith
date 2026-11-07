# Sunlith computer companion

This companion lets a supported assistant manage Sunlith calendars directly on your phone. It runs on Windows, macOS, and Linux with Node.js 22 or newer.

This is a command-line companion. It does not include a desktop calendar or browser setup wizard.

1. Install Node.js 22 or newer from https://nodejs.org/.
2. Open a terminal in integrations/mcp inside this extracted folder.
3. Run npm ci.
4. In Sunlith, open Settings > Computer connection, choose calendars and tap Connect computer. In the share list, choose Tailscale, then your computer, or use another private transfer method on the same Wi-Fi.
5. Keep the received connection file outside this extracted folder, in your private user profile. Tap Start connection on the phone, then Open calendar.
6. Follow the direct phone setup section in docs/AI-INTEGRATION.md to configure your assistant with SUNLITH_DEVICE_CONFIG pointing to that file.

Use full paths to this extracted folder when configuring your assistant. Your phone and computer need the same Wi-Fi or private Tailscale network. No Google account or second calendar app is required for direct mode.

A separate Google mode remains available in the setup guide at docs/AI-INTEGRATION.md. It requires Google authorization and phone account sync.

Source and updates: https://github.com/SunSinD/Sunlith
