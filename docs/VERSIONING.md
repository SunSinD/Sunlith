# Versioning

The first public test build is **v0.0.1-alpha.1**. The Android display version and MCP companion version are `0.0.1-alpha.1`; the initial Android `versionCode` is `1`.

[Semantic Versioning](https://semver.org/) uses three numeric components, `MAJOR.MINOR.PATCH`, with a hyphenated prerelease suffix. Major zero denotes initial development. `alpha.1` makes the first testing stage explicit; four numeric components and `~` are unnecessary. The `v` prefix is a Git tag convention, not part of the semantic version itself.

An alpha is earlier than a beta. This build still needs real-phone and account testing, so alpha is appropriate. Later iterations can be `0.0.1-alpha.2`, then a beta when the feature set and device behavior are more settled. Increase Android's integer versionCode for every distributed update, regardless of the visible version label.

Published release assets and tags must not be silently replaced with changed code. Make a new prerelease for fixes so testers can identify exactly what they installed.

Sunlith was selected after checking GitHub repository search and Google Play app-name search. No exact app-name match was found in those checked sources. This is a limited naming check, not a guarantee that the word has never been used anywhere.
